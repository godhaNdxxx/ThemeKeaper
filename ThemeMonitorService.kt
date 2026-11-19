package com.themekeeper.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Timer
import java.util.TimerTask

/**
 * Foreground service that monitors theme settings and prevents HyperOS rollback.
 *
 * Detection strategy (multi-layered):
 * 1. ContentObserver on Settings.Secure — catches settings changes in real time
 * 2. Periodic polling every 15 seconds — catches changes the observer might miss
 * 3. On detection of rollback → immediately restore saved settings via Shizuku
 *
 * HyperOS typically rolls back themes within 30-60 seconds of application.
 * This service responds within seconds to any rollback attempt.
 */
class ThemeMonitorService : Service() {

    companion object {
        private const val TAG = "ThemeMonitorService"
        private const val CHANNEL_ID = "theme_keeper_channel"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 15_000L // 15 seconds
        private const val RESTORE_COOLDOWN_MS = 5_000L // 5 seconds between restores

        const val ACTION_START = "com.themekeeper.START"
        const val ACTION_STOP = "com.themekeeper.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ThemeMonitorService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ThemeMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun isRunning(context: Context): Boolean {
            val prefs = context.getSharedPreferences("service_state", MODE_PRIVATE)
            return prefs.getBoolean("running", false)
        }
    }

    private lateinit var snapshot: ThemeSnapshot
    private lateinit var handler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollingTimer: Timer? = null
    private var lastRestoreTime = 0L
    private var restoreCount = 0
    private val observers = mutableListOf<ContentObserver>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        snapshot = ThemeSnapshot(this)
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Защита темы активна"))
                startMonitoring()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    private fun startMonitoring() {
        setRunningState(true)

        // Acquire partial wake lock to keep CPU alive for quick response
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ThemeKeeper::MonitorLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L) // 24 hours max
        }

        // Register ContentObservers on theme-related URIs
        registerThemeObservers()

        // Start periodic polling as backup
        startPolling()

        Log.i(TAG, "Theme monitoring started")
        updateNotification("Защита темы активна | Откатов предотвращено: $restoreCount")
    }

    private fun stopMonitoring() {
        setRunningState(false)

        // Remove all observers
        for (observer in observers) {
            try {
                contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister observer", e)
            }
        }
        observers.clear()

        // Stop polling
        pollingTimer?.cancel()
        pollingTimer = null

        // Release wake lock
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock", e)
        }
        wakeLock = null

        Log.i(TAG, "Theme monitoring stopped")
    }

    /**
     * Register ContentObservers on Settings.Secure and Settings.System.
     * These fire when any setting in that namespace changes.
     */
    private fun registerThemeObservers() {
        val themeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val key = uri?.lastPathSegment ?: ""
                if (key.contains("theme", ignoreCase = true) ||
                    key.contains("miui", ignoreCase = true)) {
                    Log.d(TAG, "Settings change detected: $uri")
                    checkAndRestore("observer:$key")
                }
            }
        }

        // Watch all Secure settings changes
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.CONTENT_URI,
                true, // notifyForDescendants
                themeObserver
            )
            observers.add(themeObserver)
            Log.d(TAG, "Registered observer on Settings.Secure")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Secure observer", e)
        }

        // Watch all System settings changes
        val systemObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val key = uri?.lastPathSegment ?: ""
                if (key.contains("theme", ignoreCase = true) ||
                    key.contains("miui", ignoreCase = true)) {
                    Log.d(TAG, "System settings change detected: $uri")
                    checkAndRestore("system_observer:$key")
                }
            }
        }

        try {
            contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                systemObserver
            )
            observers.add(systemObserver)
            Log.d(TAG, "Registered observer on Settings.System")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register System observer", e)
        }
    }

    /**
     * Periodic polling as a fallback mechanism.
     * Some HyperOS changes may not trigger ContentObserver.
     */
    private fun startPolling() {
        pollingTimer?.cancel()
        pollingTimer = Timer("ThemePolling", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    checkAndRestore("poll")
                }
            }, POLL_INTERVAL_MS, POLL_INTERVAL_MS)
        }
    }

    /**
     * Core logic: check if rollback occurred and restore if needed.
     */
    @Synchronized
    private fun checkAndRestore(trigger: String) {
        if (!snapshot.isLocked()) return

        // Cooldown to prevent restore loops
        val now = System.currentTimeMillis()
        if (now - lastRestoreTime < RESTORE_COOLDOWN_MS) {
            return
        }

        // Check Shizuku availability
        if (!ShizukuHelper.isShizukuAvailable() || !ShizukuHelper.hasPermission()) {
            Log.w(TAG, "Shizuku unavailable — cannot check/restore theme")
            updateNotification("⚠ Shizuku недоступен — перезапустите Shizuku")
            return
        }

        try {
            val changed = snapshot.detectRollback()
            if (changed.isNotEmpty()) {
                Log.w(TAG, "Rollback detected via $trigger! Changed keys: $changed")

                val restored = snapshot.restoreLockedTheme()
                if (restored > 0) {
                    lastRestoreTime = System.currentTimeMillis()
                    restoreCount++
                    Log.i(TAG, "Theme restored successfully ($restored keys)")
                    updateNotification(
                        "Защита темы активна | Откатов предотвращено: $restoreCount"
                    )

                    // Send broadcast so UI can update
                    sendBroadcast(Intent("com.themekeeper.THEME_RESTORED").apply {
                        setPackage(packageName)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during check/restore", e)
        }
    }

    // --- Notification management ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Защита темы",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Уведомление фонового сервиса ThemeKeeper"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ThemeMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ThemeKeeper")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Остановить", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    private fun setRunningState(running: Boolean) {
        getSharedPreferences("service_state", MODE_PRIVATE)
            .edit().putBoolean("running", running).apply()
    }
}
