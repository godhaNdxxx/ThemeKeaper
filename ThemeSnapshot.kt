package com.themekeeper.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * Captures and restores the full theme configuration from system settings.
 * When the user applies a custom theme and "locks" it, we save all theme-related
 * settings keys. When HyperOS rolls back to default, we detect the change and
 * restore these saved values via Shizuku shell commands.
 */
class ThemeSnapshot(private val context: Context) {

    companion object {
        private const val TAG = "ThemeSnapshot"
        private const val PREFS_NAME = "theme_snapshot"
        private const val KEY_SNAPSHOT_JSON = "snapshot_json"
        private const val KEY_SNAPSHOT_TIME = "snapshot_time"
        private const val KEY_LOCKED = "is_locked"

        // Known MIUI/HyperOS theme-related settings keys
        // These are discovered at runtime via `settings list` + grep
        val KNOWN_THEME_KEYS = listOf(
            // Secure settings keys commonly used by MIUI/HyperOS
            "secure:theme_changed_time",
            "secure:theme_id",
            "secure:CURRENT_THEME",
            "secure:miui_theme",
            "secure:theme_name",
            "secure:selected_theme",
            "secure:miui_lockscreen_theme",
            "secure:thememanager",

            // System settings
            "system:theme_changed_time",
            "system:miui_theme",
            "system:current_theme",

            // AOSP theme overlay key (used on stock Android 12+)
            "secure:theme_customization_overlay_packages"
        )
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Discover and capture ALL current theme-related settings.
     * This performs a live scan of system settings.
     */
    fun captureCurrentState(): Map<String, String> {
        if (!ShizukuHelper.isShizukuAvailable() || !ShizukuHelper.hasPermission()) {
            Log.w(TAG, "Shizuku not available for capture")
            return emptyMap()
        }

        // Discover all theme-related settings dynamically
        val discovered = ShizukuHelper.discoverThemeSettings()
        Log.i(TAG, "Discovered ${discovered.size} theme settings keys")

        // Also check a broader set of MIUI-specific keys
        val extraKeys = listOf(
            "miui_theme_name", "theme_pkg_name", "theme_applied_time",
            "miui_current_theme_id", "current_miui_theme",
            "applied_theme", "theme_package_name"
        )

        for (key in extraKeys) {
            for (namespace in listOf("secure", "system")) {
                val cmd = "settings get $namespace $key"
                val result = ShizukuHelper.executeCommand(cmd)
                if (result.exitCode == 0 && result.stdout != "null" && result.stdout.isNotEmpty()) {
                    discovered["$namespace:$key"] = result.stdout
                }
            }
        }

        return discovered
    }

    /**
     * Save the current theme state as the "desired" state.
     * Called when user clicks "Lock Theme".
     */
    fun lockCurrentTheme(): Boolean {
        val state = captureCurrentState()
        if (state.isEmpty()) {
            Log.w(TAG, "No theme state captured — nothing to lock")
            return false
        }

        val json = JSONObject()
        for ((key, value) in state) {
            json.put(key, value)
        }

        prefs.edit()
            .putString(KEY_SNAPSHOT_JSON, json.toString())
            .putLong(KEY_SNAPSHOT_TIME, System.currentTimeMillis())
            .putBoolean(KEY_LOCKED, true)
            .apply()

        Log.i(TAG, "Theme locked with ${state.size} settings keys")
        return true
    }

    /**
     * Get the saved "desired" theme state.
     */
    fun getLockedState(): Map<String, String>? {
        val jsonStr = prefs.getString(KEY_SNAPSHOT_JSON, null) ?: return null
        if (!prefs.getBoolean(KEY_LOCKED, false)) return null

        return try {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) {
                map[key] = json.getString(key)
            }
            map
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse locked state", e)
            null
        }
    }

    /**
     * Check if theme is currently locked.
     */
    fun isLocked(): Boolean {
        return prefs.getBoolean(KEY_LOCKED, false) &&
               prefs.getString(KEY_SNAPSHOT_JSON, null) != null
    }

    /**
     * Unlock — stop protecting the theme.
     */
    fun unlock() {
        prefs.edit().putBoolean(KEY_LOCKED, false).apply()
    }

    /**
     * Compare current state against locked state and detect rollback.
     * Returns list of keys that were changed (rolled back).
     */
    fun detectRollback(): List<String> {
        val locked = getLockedState() ?: return emptyList()
        val current = captureCurrentState()
        val changed = mutableListOf<String>()

        for ((key, savedValue) in locked) {
            val currentValue = current[key]
            if (currentValue != savedValue) {
                Log.w(TAG, "Rollback detected on $key: '$savedValue' -> '$currentValue'")
                changed.add(key)
            }
        }

        return changed
    }

    /**
     * Restore ALL locked theme settings via Shizuku shell commands.
     * Returns the number of settings successfully restored.
     */
    fun restoreLockedTheme(): Int {
        val locked = getLockedState()
        if (locked.isNullOrEmpty()) {
            Log.w(TAG, "No locked state to restore")
            return 0
        }

        var restored = 0

        for ((qualifiedKey, value) in locked) {
            val parts = qualifiedKey.split(":", limit = 2)
            if (parts.size != 2) continue

            val namespace = parts[0]  // "secure", "system", or "global"
            val key = parts[1]

            val success = when (namespace) {
                "secure" -> ShizukuHelper.putSecureSetting(key, value)
                "system" -> ShizukuHelper.putSystemSetting(key, value)
                "global" -> {
                    val r = ShizukuHelper.executeCommand("settings put global '$key' '$value'")
                    r.exitCode == 0
                }
                else -> false
            }

            if (success) {
                restored++
                Log.i(TAG, "Restored $qualifiedKey = $value")
            } else {
                Log.e(TAG, "Failed to restore $qualifiedKey")
            }
        }

        // Notify system about theme change
        ShizukuHelper.notifyThemeChanged()

        Log.i(TAG, "Restored $restored / ${locked.size} theme settings")
        return restored
    }

    /**
     * Get human-readable summary of locked state.
     */
    fun getSummary(): String {
        val locked = getLockedState()
        if (locked.isNullOrEmpty()) return "Тема не зафиксирована"

        val time = prefs.getLong(KEY_SNAPSHOT_TIME, 0)
        val dateStr = if (time > 0) {
            java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(time))
        } else "—"

        return "Зафиксировано ${locked.size} параметров ($dateStr)"
    }
}
