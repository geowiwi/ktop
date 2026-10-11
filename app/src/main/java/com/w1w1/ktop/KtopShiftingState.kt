package com.w1w1.ktop

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt

/** Ki2's KarooTheme: dark (white text) or white (dark text) ride theme. */
enum class KarooTheme { DARK, WHITE }

fun karooTheme(context: Context): KarooTheme {
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return if (night == Configuration.UI_MODE_NIGHT_YES) KarooTheme.DARK else KarooTheme.WHITE
}

/**
 * Snapshot of everything the Ki2 fields display - the ktop equivalent of
 * Ki2's ShiftingGearingHelper + BatteryInfo + ShiftCountHandler, filled
 * from WheeltopBleClient (live BLE data) and WheeltopConfig (rider setup).
 *
 * Wheeltop specifics vs. Ki2/Di2:
 * - Rear gear: serverReportGear (0x98). Front gear (2x systems such as
 *   EDS TX): frontStatusReport (0x88) / Q_NUM. A front derailleur is
 *   detected automatically (WheeltopBleClient.hasFrontDerailleur());
 *   until its first position report the front gear is unknown (0 -> N/A).
 *   On 1x systems the front gear is simply 1 of 1.
 * - Teeth counts come only from the custom gearing of the connected
 *   device (DeviceStore, Ki2-style gearing editor in the app).
 * - "Shifting battery" = rear derailleur main pack, voltage converted to
 *   % via BatteryCurves (rough linear estimate).
 * - Shift counts: rear from serverReportGear finishes, front from
 *   frontStatusReport changes; total = front + rear (like Ki2).
 */
data class KtopShiftingState(
    val established: Boolean,
    val frontGearMax: Int,
    val frontGear: Int,
    val rearGearMax: Int,
    val rearGear: Int,
    val frontTeeth: Int,
    val rearTeeth: Int,
    val batteryPercent: Int?,
    val shiftCount: Int,
    val frontShiftCount: Int?,
    val rearShiftCount: Int,
) {
    val frontGearKnown: Boolean get() = frontGear in 1..frontGearMax
    val hasFrontGearSize: Boolean get() = frontTeeth != 0
    val hasRearGearSize: Boolean get() = rearTeeth != 0
    val hasGearSizes: Boolean get() = frontTeeth != 0 && rearTeeth != 0
    val gearRatio: Double get() = if (rearTeeth == 0) 0.0 else frontTeeth.toDouble() / rearTeeth

    companion object {
        fun current(context: Context, ble: WheeltopBleClient): KtopShiftingState {
            val rearMax = ble.getTotalGears() ?: 0
            val rear = ble.getLastGear()?.takeIf { it in 1..rearMax } ?: 0
            val hasFd = ble.hasFrontDerailleur()
            val frontMax = if (hasFd) 2 else 1
            val front = if (hasFd) (ble.getLastFrontGear() ?: 0) else 1

            val device = DeviceStore.get(context, ble.getConnectedUid())
            val frontTeethList = device?.frontTeeth ?: emptyList()
            val rearTeethList = device?.rearTeeth ?: emptyList()
            val frontTeeth = if (front >= 1) frontTeethList.getOrElse(front - 1) { 0 } else 0
            val rearTeeth = if (rear >= 1) rearTeethList.getOrElse(rear - 1) { 0 } else 0

            val rdV = ble.getLastRearBatteryV()
            val battery = if (rdV != null && rdV > 0.0) (BatteryCurves.mainPackPct(rdV) * 100f).roundToInt() else null

            val totalShifts = ble.getShiftCount()   // rear + front, like Ki2
            val frontShifts = ble.getFrontShiftCount()
            val rearShifts = (totalShifts - frontShifts).coerceAtLeast(0)
            return KtopShiftingState(
                established = rearMax > 0 && rear > 0,
                frontGearMax = frontMax,
                frontGear = front,
                rearGearMax = rearMax,
                rearGear = rear,
                frontTeeth = frontTeeth,
                rearTeeth = rearTeeth,
                batteryPercent = battery,
                shiftCount = totalShifts,
                frontShiftCount = frontShifts,
                rearShiftCount = rearShifts,
            )
        }

        /** Fixed sample shown in Karoo's page-editor preview. */
        fun preview(): KtopShiftingState = KtopShiftingState(
            established = true,
            frontGearMax = 1, frontGear = 1,
            rearGearMax = 13, rearGear = 6,
            frontTeeth = 40, rearTeeth = 21,
            batteryPercent = 80,
            shiftCount = 12, frontShiftCount = 0, rearShiftCount = 12,
        )
    }
}
