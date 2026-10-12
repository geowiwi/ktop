package com.w1w1.ktop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg
import kotlin.math.roundToInt

/**
 * Writes ktop's own values into the ride's FIT file as developer fields
 * (extension_info.xml: fitFile="true", WheeltopExtension.startFit).
 *
 * Gear changes themselves are recorded by Karoo, because ktop is a native
 * shifting source (KtopNativeShifting). This adds what Karoo doesn't know:
 *
 * Record messages, once a minute while recording:
 *   ktop_rd_battery_v, ktop_fd_battery_v (2x only), ktop_left_shifter_v,
 *   ktop_right_shifter_v, ktop_rd_battery (estimated %)
 * Session message, every 5 minutes, on pause and at the end:
 *   ktop_shifts, ktop_front_shifts, ktop_rear_shifts (during this ride),
 *   ktop_rd_battery_start, ktop_rd_battery_end (%), ktop_race_mode (0/1)
 */
class KtopFitRecorder(
    private val context: Context,
    private val karooSystem: KarooSystemService,
    private val bleProvider: () -> WheeltopBleClient,
) {
    companion object {
        private const val TAG = "ktop-ble"
        private const val RECORD_INTERVAL_MS = 60_000L
        private const val SESSION_EVERY_N_RECORDS = 5

        private const val UINT8: Short = 2
        private const val UINT16: Short = 132
        private const val FLOAT32: Short = 136

        private val RD_V = DeveloperField(0, FLOAT32, "ktop_rd_battery_v", "V")
        private val FD_V = DeveloperField(1, FLOAT32, "ktop_fd_battery_v", "V")
        private val LEFT_V = DeveloperField(2, FLOAT32, "ktop_left_shifter_v", "V")
        private val RIGHT_V = DeveloperField(3, FLOAT32, "ktop_right_shifter_v", "V")
        private val RD_PCT = DeveloperField(4, UINT8, "ktop_rd_battery", "percent")
        private val SHIFTS = DeveloperField(5, UINT16, "ktop_shifts", "shifts")
        private val FRONT_SHIFTS = DeveloperField(6, UINT16, "ktop_front_shifts", "shifts")
        private val REAR_SHIFTS = DeveloperField(7, UINT16, "ktop_rear_shifts", "shifts")
        private val RD_PCT_START = DeveloperField(8, UINT8, "ktop_rd_battery_start", "percent")
        private val RD_PCT_END = DeveloperField(9, UINT8, "ktop_rd_battery_end", "percent")
        private val RACE_MODE = DeveloperField(10, UINT8, "ktop_race_mode", "")
    }

    fun start(emitter: Emitter<FitEffect>) {
        val handler = Handler(Looper.getMainLooper())
        var rideState: RideState = RideState.Idle
        var startShifts: Int? = null
        var startFrontShifts: Int? = null
        var startBattery: Int? = null
        var records = 0

        fun ble() = bleProvider()

        fun batteryPct(): Int? = ble().getLastRearBatteryV()?.takeIf { it > 0.0 }
            ?.let { (BatteryCurves.mainPackPct(it) * 100f).roundToInt() }

        fun captureStart() {
            if (startShifts != null) return
            startShifts = ble().getShiftCount()
            startFrontShifts = ble().getFrontShiftCount()
            startBattery = batteryPct()
            Log.i(TAG, "FIT: ride start shifts=$startShifts battery=$startBattery")
        }

        fun writeRecord() {
            val b = ble()
            val values = listOfNotNull(
                b.getLastRearBatteryV()?.takeIf { it > 0.0 }?.let { FieldValue(RD_V, it) },
                b.getLastFrontBatteryV()?.takeIf { it > 0.0 && b.hasFrontDerailleur() }?.let { FieldValue(FD_V, it) },
                b.getLastLeftBatteryV()?.takeIf { it > 0.0 }?.let { FieldValue(LEFT_V, it) },
                b.getLastRightBatteryV()?.takeIf { it > 0.0 }?.let { FieldValue(RIGHT_V, it) },
                batteryPct()?.let { FieldValue(RD_PCT, it.toDouble()) },
            )
            if (values.isNotEmpty()) emitter.onNext(WriteToRecordMesg(values))
        }

        fun writeSession() {
            if (startShifts == null) return
            val b = ble()
            val shifts = (b.getShiftCount() - (startShifts ?: 0)).coerceAtLeast(0)
            val front = (b.getFrontShiftCount() - (startFrontShifts ?: 0)).coerceAtLeast(0)
            val values = listOfNotNull(
                FieldValue(SHIFTS, shifts.toDouble()),
                FieldValue(FRONT_SHIFTS, front.toDouble()),
                FieldValue(REAR_SHIFTS, (shifts - front).coerceAtLeast(0).toDouble()),
                startBattery?.let { FieldValue(RD_PCT_START, it.toDouble()) },
                batteryPct()?.let { FieldValue(RD_PCT_END, it.toDouble()) },
                b.raceMode?.let { FieldValue(RACE_MODE, if (it) 1.0 else 0.0) },
            )
            emitter.onNext(WriteToSessionMesg(values))
            Log.d(TAG, "FIT: session shifts=$shifts front=$front battery=$startBattery->${batteryPct()}")
        }

        val tick = object : Runnable {
            override fun run() {
                try {
                    if (rideState is RideState.Recording && ble().getConnectedUid() != null) {
                        captureStart()
                        writeRecord()
                        records++
                        if (records % SESSION_EVERY_N_RECORDS == 0) writeSession()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "FIT: write failed", t)
                }
                handler.postDelayed(this, RECORD_INTERVAL_MS)
            }
        }

        val consumerId = karooSystem.addConsumer<RideState> { newState ->
            handler.post {
                val old = rideState
                rideState = newState
                try {
                    when {
                        newState is RideState.Recording && old is RideState.Idle -> {
                            startShifts = null
                            records = 0
                            if (ble().getConnectedUid() != null) captureStart()
                        }
                        newState is RideState.Paused -> writeSession()
                        newState is RideState.Idle && old !is RideState.Idle -> {
                            writeSession()
                            startShifts = null
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "FIT: ride state handling failed", t)
                }
            }
        }

        handler.postDelayed(tick, RECORD_INTERVAL_MS)
        Log.i(TAG, "FIT: started")

        emitter.setCancellable {
            Log.i(TAG, "FIT: stopped")
            karooSystem.removeConsumer(consumerId)
            handler.removeCallbacks(tick)
        }
    }
}
