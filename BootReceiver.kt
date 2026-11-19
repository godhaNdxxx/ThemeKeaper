package com.themekeeper.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-starts ThemeMonitorService on device boot.
 * After a reboot, Shizuku needs to be restarted by the user
 * (via Wireless Debugging auto-start or manual pairing).
 * The service starts and waits for Shizuku to become available.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                "com.htc.intent.action.QUICKBOOT_POWERON"
            )) {
            Log.i(TAG, "Boot completed — checking if theme protection should start")

            val snapshot = ThemeSnapshot(context)
            if (snapshot.isLocked()) {
                Log.i(TAG, "Theme is locked — starting monitor service")
                try {
                    ThemeMonitorService.start(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service on boot", e)
                }
            } else {
                Log.i(TAG, "Theme is not locked — skipping auto-start")
            }
        }
    }
}
