package com.themekeeper.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var snapshot: ThemeSnapshot

    // UI elements
    private lateinit var tvStatus: TextView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvThemeInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnLock: Button
    private lateinit var btnUnlock: Button
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnScanSettings: Button
    private lateinit var btnBattery: Button

    private val logLines = mutableListOf<String>()

    // Shizuku permission callback
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuHelper.REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    addLog("✓ Shizuku разрешение получено")
                    updateUI()
                } else {
                    addLog("✗ Shizuku разрешение отклонено")
                }
            }
        }

    // Shizuku binder lifecycle
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        addLog("Shizuku binder получен")
        runOnUiThread { updateUI() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        addLog("⚠ Shizuku binder потерян")
        runOnUiThread { updateUI() }
    }

    // Receiver for theme restore events from service
    private val restoreReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            addLog("🔄 Тема восстановлена сервисом!")
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        snapshot = ThemeSnapshot(this)
        initViews()
        requestNotificationPermission()
        registerShizukuListeners()

        registerReceiver(
            restoreReceiver,
            IntentFilter("com.themekeeper.THEME_RESTORED"),
            RECEIVER_NOT_EXPORTED
        )

        updateUI()
        addLog("ThemeKeeper запущен")
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Exception) {}

        try {
            unregisterReceiver(restoreReceiver)
        } catch (_: Exception) {}

        super.onDestroy()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        tvThemeInfo = findViewById(R.id.tvThemeInfo)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnLock = findViewById(R.id.btnLock)
        btnUnlock = findViewById(R.id.btnUnlock)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
        btnScanSettings = findViewById(R.id.btnScanSettings)
        btnBattery = findViewById(R.id.btnBattery)

        btnLock.setOnClickListener { lockTheme() }
        btnUnlock.setOnClickListener { unlockTheme() }
        btnStartService.setOnClickListener { startProtection() }
        btnStopService.setOnClickListener { stopProtection() }
        btnScanSettings.setOnClickListener { scanThemeSettings() }
        btnBattery.setOnClickListener { requestBatteryOptimization() }

        // Shizuku permission button
        findViewById<Button>(R.id.btnShizukuPermission).setOnClickListener {
            if (ShizukuHelper.isShizukuAvailable()) {
                if (!ShizukuHelper.hasPermission()) {
                    ShizukuHelper.requestPermission()
                } else {
                    Toast.makeText(this, "Разрешение уже получено", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Shizuku не запущен!", Toast.LENGTH_SHORT).show()
                // Open Shizuku app if installed
                try {
                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun registerShizukuListeners() {
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Shizuku listeners", e)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    private fun updateUI() {
        // Shizuku status
        val shizukuAvailable = ShizukuHelper.isShizukuAvailable()
        val shizukuPermission = if (shizukuAvailable) ShizukuHelper.hasPermission() else false

        tvShizukuStatus.text = when {
            !shizukuAvailable -> "Shizuku: ❌ не запущен"
            !shizukuPermission -> "Shizuku: ⚠ нет разрешения"
            else -> "Shizuku: ✅ подключён"
        }

        // Theme lock status
        val isLocked = snapshot.isLocked()
        val serviceRunning = ThemeMonitorService.isRunning(this)

        tvStatus.text = when {
            isLocked && serviceRunning -> "🛡 Тема защищена"
            isLocked && !serviceRunning -> "🔒 Тема зафиксирована (сервис остановлен)"
            else -> "🔓 Тема не защищена"
        }

        tvThemeInfo.text = snapshot.getSummary()

        // Button states
        btnLock.isEnabled = shizukuPermission && !isLocked
        btnUnlock.isEnabled = isLocked
        btnStartService.isEnabled = isLocked && !serviceRunning
        btnStopService.isEnabled = serviceRunning
        btnScanSettings.isEnabled = shizukuPermission

        findViewById<Button>(R.id.btnShizukuPermission).visibility =
            if (!shizukuPermission) View.VISIBLE else View.GONE
    }

    private fun lockTheme() {
        addLog("Фиксирую текущую тему...")

        Thread {
            val success = snapshot.lockCurrentTheme()
            runOnUiThread {
                if (success) {
                    addLog("✓ Тема зафиксирована!")
                    updateUI()

                    // Auto-start service
                    AlertDialog.Builder(this)
                        .setTitle("Тема зафиксирована")
                        .setMessage("Запустить защиту от отката?")
                        .setPositiveButton("Да") { _, _ -> startProtection() }
                        .setNegativeButton("Позже", null)
                        .show()
                } else {
                    addLog("✗ Не удалось зафиксировать тему. Проверьте Shizuku.")
                    Toast.makeText(this, "Ошибка! Проверьте Shizuku.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun unlockTheme() {
        AlertDialog.Builder(this)
            .setTitle("Снять защиту?")
            .setMessage("Тема больше не будет защищена от отката HyperOS.")
            .setPositiveButton("Снять") { _, _ ->
                snapshot.unlock()
                stopProtection()
                addLog("Защита темы снята")
                updateUI()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startProtection() {
        addLog("Запускаю сервис защиты...")
        ThemeMonitorService.start(this)
        // Small delay to let service start
        tvStatus.postDelayed({ updateUI() }, 1000)
    }

    private fun stopProtection() {
        addLog("Останавливаю сервис защиты...")
        ThemeMonitorService.stop(this)
        tvStatus.postDelayed({ updateUI() }, 1000)
    }

    private fun scanThemeSettings() {
        addLog("Сканирую настройки темы...")

        Thread {
            val settings = ShizukuHelper.discoverThemeSettings()
            runOnUiThread {
                if (settings.isEmpty()) {
                    addLog("Настройки темы не найдены (тема может быть стандартная)")
                } else {
                    addLog("Найдено ${settings.size} параметров темы:")
                    for ((key, value) in settings) {
                        val displayValue = if (value.length > 80) value.take(80) + "..." else value
                        addLog("  $key = $displayValue")
                    }
                }
            }
        }.start()
    }

    private fun requestBatteryOptimization() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                addLog("Запрос на отключение оптимизации батареи")
            } else {
                Toast.makeText(this, "Уже отключено", Toast.LENGTH_SHORT).show()
                addLog("Оптимизация батареи уже отключена")
            }
        } catch (e: Exception) {
            addLog("⚠ Не удалось запросить отключение оптимизации: ${e.message}")
        }
    }

    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "[$time] $message"
        logLines.add(line)
        if (logLines.size > 100) logLines.removeAt(0)

        runOnUiThread {
            tvLog.text = logLines.joinToString("\n")
            scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
