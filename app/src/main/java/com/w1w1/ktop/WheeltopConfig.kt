package com.w1w1.ktop

import android.content.Context

/**
 * App-wide settings (Ki2 "Settings" tab). Per-device settings (name,
 * custom gearing) live in DeviceStore. Front derailleur presence is no
 * longer a manual setting: it is detected from the groupset itself
 * (Q_TOTAL / FD battery / frontStatusReport, WheeltopBleClient.
 * hasFrontDerailleur()).
 */
object WheeltopConfig {
    /** SharedPreferences file, also used by the Settings tab (SettingsFragment). */
    const val PREFS = "wheeltop_config"
    const val KEY_BATTERY_LOW = "battery_level_low"
    const val KEY_BATTERY_CRITICAL = "battery_level_critical"

    /** Ki2 defaults (preferences_global.xml). */
    const val DEFAULT_BATTERY_LEVEL_LOW = 20
    const val DEFAULT_BATTERY_LEVEL_CRITICAL = 10

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Stored as strings (androidx ListPreference). 0 = off.
    private fun level(context: Context, key: String, default: Int): Int =
        runCatching { prefs(context).getString(key, null)?.toIntOrNull() }.getOrNull() ?: default

    fun batteryLevelLow(context: Context): Int = level(context, KEY_BATTERY_LOW, DEFAULT_BATTERY_LEVEL_LOW)

    fun batteryLevelCritical(context: Context): Int = level(context, KEY_BATTERY_CRITICAL, DEFAULT_BATTERY_LEVEL_CRITICAL)

    /** "50, 34" -> [50, 34]; anything unparsable or out of range is dropped. */
    fun parseTeeth(raw: String): List<Int> =
        raw.split(',', ';', ' ', '/', '-')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 5..80 }
}
