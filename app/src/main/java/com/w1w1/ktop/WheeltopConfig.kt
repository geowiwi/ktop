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

    // ---- In-ride alerts (KtopRideAlerts) ----
    const val KEY_BATTERY_ALERTS = "battery_alerts_enabled"
    const val KEY_AUDIO_ENABLED = "audio_alerts_enabled"
    const val KEY_AUDIO_LOWEST_GEAR = "audio_alert_lowest_gear"
    const val KEY_AUDIO_HIGHEST_GEAR = "audio_alert_highest_gear"

    // ---- Overlay (KtopOverlay) ----
    const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    const val KEY_OVERLAY_DURATION = "overlay_duration"
    const val KEY_OVERLAY_POSITION = "overlay_position"
    const val KEY_OVERLAY_THEME = "overlay_theme"
    const val KEY_OVERLAY_OPACITY = "overlay_opacity"
    const val KEY_OVERLAY_TEST = "overlay_test"

    private fun bool(context: Context, key: String, default: Boolean): Boolean =
        runCatching { prefs(context).getBoolean(key, default) }.getOrDefault(default)

    private fun string(context: Context, key: String, default: String): String =
        runCatching { prefs(context).getString(key, null) }.getOrNull() ?: default

    fun batteryAlertsEnabled(context: Context): Boolean = bool(context, KEY_BATTERY_ALERTS, true)
    fun audioAlertsEnabled(context: Context): Boolean = bool(context, KEY_AUDIO_ENABLED, true)
    /** Gear 1 = largest cog (easiest). Value: a KtopSounds name, "off" = no sound. */
    fun audioLowestGear(context: Context): String = string(context, KEY_AUDIO_LOWEST_GEAR, KtopSounds.OFF)
    /** Last gear = smallest cog (hardest). */
    fun audioHighestGear(context: Context): String = string(context, KEY_AUDIO_HIGHEST_GEAR, KtopSounds.OFF)

    fun overlayEnabled(context: Context): Boolean = bool(context, KEY_OVERLAY_ENABLED, false)
    /** ms, -1 = always visible. */
    fun overlayDurationMs(context: Context): Int =
        string(context, KEY_OVERLAY_DURATION, "3000").toIntOrNull() ?: 3000
    /** "top", "center" or "bottom". */
    fun overlayPosition(context: Context): String = string(context, KEY_OVERLAY_POSITION, "top")
    /** "auto" (follows the Karoo theme), "dark" or "light". */
    fun overlayTheme(context: Context): String = string(context, KEY_OVERLAY_THEME, "auto")
    /** 0..100 %. */
    fun overlayOpacity(context: Context): Int =
        (string(context, KEY_OVERLAY_OPACITY, "100").toIntOrNull() ?: 100).coerceIn(20, 100)

    /** "50, 34" -> [50, 34]; anything unparsable or out of range is dropped. */
    fun parseTeeth(raw: String): List<Int> =
        raw.split(',', ';', ' ', '/', '-')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 5..80 }
}
