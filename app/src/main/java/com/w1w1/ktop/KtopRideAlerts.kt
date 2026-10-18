package com.w1w1.ktop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.PlayBeepPattern.Tone
import io.hammerhead.karooext.models.RideState
import kotlin.math.roundToInt

/**
 * Karoo beep patterns, taken from Ki2's AudioManager
 * (https://github.com/valterc/ki2, MIT License, see LICENSE-ki2.txt).
 */
object KtopSounds {
    const val OFF = "off"

    fun pattern(name: String?): List<Tone>? = when (name) {
        "single_beep" -> listOf(Tone(5000, 350))
        "double_beep" -> listOf(Tone(5000, 350), Tone(null, 150), Tone(5000, 350))
        "karoo_generic" -> listOf(
            Tone(5000, 350), Tone(null, 100), Tone(4250, 100), Tone(null, 50),
            Tone(4250, 100), Tone(null, 50), Tone(4250, 100),
        )
        "bell_old" -> listOf(Tone(3750, 50), Tone(5800, 200), Tone(null, 50), Tone(3750, 50), Tone(5800, 300))
        "bell_new" -> listOf(Tone(2349, 31), Tone(3520, 125), Tone(null, 31), Tone(2349, 31), Tone(3520, 406))
        else -> null
    }

    /** Ki2's "device warning" pattern, used with the low-battery alert. */
    val DEVICE_WARNING = listOf(Tone(1975, 125), Tone(null, 125), Tone(1975, 125), Tone(null, 125), Tone(1975, 125))

    fun play(karooSystem: KarooSystemService, name: String?) {
        val tones = pattern(name) ?: return
        karooSystem.dispatch(PlayBeepPattern(tones))
    }
}

/**
 * In-ride alerts, like Ki2's AudioManager / BatteryAlertHandler:
 *
 * - Sound when the rear derailleur reaches the lowest (largest cog) or
 *   highest (smallest cog) gear. Wheeltop reports nothing when the rider
 *   presses past the last cog, so Ki2's "shifting limit" sound is not
 *   possible.
 * - Karoo in-ride alert + warning beep when a shifter coin cell or the
 *   front derailleur battery drops to the low / critical level (Settings ->
 *   Battery). The rear derailleur battery is reported to Karoo as the
 *   native shifting battery, so Karoo warns about that one itself.
 *   Each battery alerts once per level per ride.
 */
class KtopRideAlerts(
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val ble: WheeltopBleClient,
) {
    companion object {
        private const val TAG = "ktop-ble"

    }

    private enum class Level { LOW, CRITICAL }

    private val handler = Handler(Looper.getMainLooper())
    private val subs = mutableListOf<WheeltopBleClient.Subscription>()
    private var consumerId: String? = null
    private var rideState: RideState = RideState.Idle
    private var lastGearAlerted: Int? = null
    private val alerted = mutableMapOf<String, Level>()

    fun start() {
        if (consumerId != null) return
        consumerId = karooSystem.addConsumer<RideState> { newState ->
            handler.post {
                val old = rideState
                rideState = newState
                if (newState is RideState.Recording && old !is RideState.Recording) checkBatteries()
                if (newState is RideState.Idle && old !is RideState.Idle) {
                    alerted.clear()
                    lastGearAlerted = null
                }
            }
        }
        subs += ble.shiftCountFlow.subscribe { handler.post { onShift() } }
        val onBattery: (Double) -> Unit = { handler.post { checkBatteries() } }
        subs += ble.leftBatteryVFlow.subscribe(onBattery)
        subs += ble.rightBatteryVFlow.subscribe(onBattery)
        subs += ble.frontBatteryVFlow.subscribe(onBattery)
        Log.i(TAG, "ride alerts started")
    }

    fun stop() {
        consumerId?.let { karooSystem.removeConsumer(it) }
        consumerId = null
        subs.forEach { it.cancel() }
        subs.clear()
    }

    private fun onShift() {
        if (rideState is RideState.Idle) return
        if (!WheeltopConfig.audioAlertsEnabled(context)) return
        val gear = ble.getLastGear() ?: return
        val max = ble.getTotalGears() ?: return
        if (gear == lastGearAlerted) return
        val sound = when (gear) {
            1 -> WheeltopConfig.audioLowestGear(context)
            max -> WheeltopConfig.audioHighestGear(context)
            else -> null
        }
        lastGearAlerted = if (sound != null) gear else null
        if (sound != null && sound != KtopSounds.OFF) {
            try {
                KtopSounds.play(karooSystem, sound)
            } catch (t: Throwable) {
                Log.w(TAG, "beep failed", t)
            }
        }
    }

    private fun checkBatteries() {
        if (rideState !is RideState.Recording) return
        if (!WheeltopConfig.batteryAlertsEnabled(context)) return
        check("left", R.string.text_battery_left, ble.getLastLeftBatteryV()) { BatteryCurves.coinCellPct(it) }
        check("right", R.string.text_battery_right, ble.getLastRightBatteryV()) { BatteryCurves.coinCellPct(it) }
        if (ble.hasFrontDerailleur()) {
            check("fd", R.string.text_battery_fd, ble.getLastFrontBatteryV()) { BatteryCurves.mainPackPct(it) }
        }
    }

    private fun check(key: String, nameRes: Int, volts: Double?, curve: (Double) -> Float) {
        val v = volts?.takeIf { it > 0.0 } ?: return
        val pct = (curve(v) * 100f).roundToInt()
        val low = WheeltopConfig.batteryLevelLow(context)
        val critical = WheeltopConfig.batteryLevelCritical(context)
        val level = when {
            critical > 0 && pct <= critical -> Level.CRITICAL
            low > 0 && pct <= low -> Level.LOW
            else -> return
        }
        val prev = alerted[key]
        if (prev != null && prev.ordinal >= level.ordinal) return
        alerted[key] = level

        val title = context.getString(R.string.text_alert_battery_title)
        val detail = context.getString(R.string.text_alert_battery_detail, context.getString(nameRes), pct, v)
        Log.i(TAG, "battery alert $key $level ($pct%, ${"%.2f".format(v)} V)")
        try {
            karooSystem.dispatch(
                InRideAlert(
                    "ktop-battery-$key",
                    R.drawable.ic_hh_battery,
                    title,
                    detail,
                    12_000,
                    backgroundColor = if (level == Level.CRITICAL) R.color.hh_red_600 else R.color.hh_yellow_darker,
                    textColor = R.color.white,
                )
            )
            karooSystem.dispatch(PlayBeepPattern(KtopSounds.DEVICE_WARNING))
        } catch (t: Throwable) {
            Log.w(TAG, "battery alert failed", t)
        }
    }
}
