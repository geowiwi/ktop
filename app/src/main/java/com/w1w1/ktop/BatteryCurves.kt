package com.w1w1.ktop

/**
 * Rough voltage -> 0..1 fill curves for BatteryView bars, shared between
 * KtopPreviewActivity's dashboard and the graphical battery data types
 * (BatteryGraphicVisualType) so there's one place to correct these once
 * real-world low-battery behavior has been observed. Not validated
 * against either battery type's real discharge curve - the main pack
 * (rear/front derailleur) and the shifter coin cells have very
 * different nominal voltages, so they need separate curves, but both
 * are linear approximations between a rough "empty" and "full" voltage,
 * good enough for an at-a-glance indicator, not a precise fuel gauge.
 */
object BatteryCurves {
    fun mainPackPct(v: Double): Float = ((v - 6.6) / (8.2 - 6.6)).coerceIn(0.0, 1.0).toFloat()
    fun coinCellPct(v: Double): Float = ((v - 2.0) / (3.2 - 2.0)).coerceIn(0.0, 1.0).toFloat()
}
