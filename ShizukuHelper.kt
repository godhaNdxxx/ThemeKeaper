package com.themekeeper.app

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Helper for executing shell commands via Shizuku (ADB-level privileges).
 * Used to read/write system settings and interact with ThemeManager.
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val REQUEST_CODE = 1001

    /** Check if Shizuku service is running and bound. */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku not available: ${e.message}")
            false
        }
    }

    /** Check if this app has Shizuku permission. */
    fun hasPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            val result = Shizuku.checkSelfPermission()
            result == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Permission check failed: ${e.message}")
            false
        }
    }

    /** Request Shizuku permission from user. */
    fun requestPermission() {
        try {
            if (isShizukuAvailable()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request permission: ${e.message}")
        }
    }

    /**
     * Execute a shell command via Shizuku and return stdout.
     * Runs with ADB (shell) identity.
     */
    fun executeCommand(command: String): CommandResult {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            val exitCode = process.waitFor()
            process.destroy()

            Log.d(TAG, "CMD: $command -> exit=$exitCode, out=${stdout.take(200)}")
            CommandResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: $command", e)
            CommandResult(-1, "", e.message ?: "Unknown error")
        }
    }

    /**
     * Read a value from Settings.Secure via shell.
     */
    fun getSecureSetting(key: String): String? {
        val result = executeCommand("settings get secure $key")
        return if (result.exitCode == 0 && result.stdout != "null" && result.stdout.isNotEmpty()) {
            result.stdout
        } else null
    }

    /**
     * Write a value to Settings.Secure via shell (requires ADB/root).
     */
    fun putSecureSetting(key: String, value: String): Boolean {
        val result = executeCommand("settings put secure '$key' '$value'")
        return result.exitCode == 0
    }

    /**
     * Read a value from Settings.System via shell.
     */
    fun getSystemSetting(key: String): String? {
        val result = executeCommand("settings get system $key")
        return if (result.exitCode == 0 && result.stdout != "null" && result.stdout.isNotEmpty()) {
            result.stdout
        } else null
    }

    /**
     * Write a value to Settings.System via shell.
     */
    fun putSystemSetting(key: String, value: String): Boolean {
        val result = executeCommand("settings put system '$key' '$value'")
        return result.exitCode == 0
    }

    /**
     * List all settings containing "theme" (case-insensitive).
     * Used to discover which keys MIUI/HyperOS uses for themes.
     */
    fun discoverThemeSettings(): Map<String, String> {
        val results = mutableMapOf<String, String>()

        // Search in secure settings
        val secure = executeCommand("settings list secure 2>/dev/null | grep -i theme")
        if (secure.exitCode == 0 && secure.stdout.isNotEmpty()) {
            secure.stdout.lines().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    results["secure:${parts[0]}"] = parts[1]
                }
            }
        }

        // Search in system settings
        val system = executeCommand("settings list system 2>/dev/null | grep -i theme")
        if (system.exitCode == 0 && system.stdout.isNotEmpty()) {
            system.stdout.lines().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    results["system:${parts[0]}"] = parts[1]
                }
            }
        }

        // Search in global settings
        val global = executeCommand("settings list global 2>/dev/null | grep -i theme")
        if (global.exitCode == 0 && global.stdout.isNotEmpty()) {
            global.stdout.lines().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    results["global:${parts[0]}"] = parts[1]
                }
            }
        }

        return results
    }

    /**
     * Force-stop and restart the ThemeManager to pick up restored settings.
     */
    fun restartThemeManager(): Boolean {
        val result = executeCommand("am force-stop com.android.thememanager")
        return result.exitCode == 0
    }

    /**
     * Send broadcast to notify system of theme change.
     */
    fun notifyThemeChanged(): Boolean {
        // Try MIUI-specific broadcasts
        val r1 = executeCommand(
            "am broadcast -a miui.intent.action.THEME_CHANGED " +
            "--ei theme_change_type 0 " +
            "-p com.android.thememanager"
        )
        return r1.exitCode == 0
    }

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )
}
