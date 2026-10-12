package com.w1w1.ktop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnDataPoint

/**
 * Feeds Karoo's built-in shifting data types from the connected groupset,
 * like Ki2's ShiftingDevice (https://github.com/valterc/ki2, MIT License).
 *
 * The ktop sensor is announced with DataType.Source.SHIFTING_FRONT_GEAR,
 * SHIFTING_REAR_GEAR and SHIFTING_BATTERY (WheeltopExtension.startScan).
 * Karoo then treats it as a shifting sensor: its own gear fields work and
 * it records gear changes in the FIT file (standard gear-change events).
 *
 * Data points are emitted when gear or battery status change, and again
 * every ~50 s (Ki2 does the same) so Karoo doesn't consider them stale.
 * Gear numbering as in ktop/Ki2: rear 1 = largest cog, front 1 = small
 * chainring; 1x systems report front gear 1 of 1.
 */
class KtopNativeShifting(
    private val context: Context,
    private val uid: String,
    private val ble: WheeltopBleClient,
    private val emitter: Emitter<DeviceEvent>,
) {
    companion object {
        private const val TAG = "ktop-ble"
        private const val KEEPALIVE_CHECK_MS = 10_000L
        private const val KEEPALIVE_MS = 50_000L

        /** Ki2's mapping from battery % to Karoo's BatteryStatus. */
        fun batteryStatus(context: Context, percent: Int): BatteryStatus {
            val low = WheeltopConfig.batteryLevelLow(context)
            val critical = WheeltopConfig.batteryLevelCritical(context)
            return when {
                percent >= 80 -> BatteryStatus.NEW
                percent >= 60 -> BatteryStatus.GOOD
                critical > 0 && percent <= critical -> BatteryStatus.CRITICAL
                low > 0 && percent <= low -> BatteryStatus.LOW
                else -> BatteryStatus.OK
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val subs = mutableListOf<WheeltopBleClient.Subscription>()
    private var running = false

    private var lastFront: Pair<Int, Int>? = null
    private var lastRear: Pair<Int, Int>? = null
    private var lastTeeth: Pair<Int, Int>? = null
    private var lastStatus: BatteryStatus? = null
    private var lastEmitAt = 0L

    private val keepalive = object : Runnable {
        override fun run() {
            if (!running) return
            if (System.currentTimeMillis() - lastEmitAt >= KEEPALIVE_MS) emit(force = true)
            handler.postDelayed(this, KEEPALIVE_CHECK_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        val onChange: (Any) -> Unit = { handler.post { emit(force = false) } }
        subs += ble.gearFlow.subscribe(onChange)
        subs += ble.frontGearFlow.subscribe(onChange)
        subs += ble.rearBatteryVFlow.subscribe(onChange)
        subs += ble.infoFlow.subscribe(onChange)
        handler.post { emit(force = true) }
        handler.postDelayed(keepalive, KEEPALIVE_CHECK_MS)
    }

    fun stop() {
        running = false
        subs.forEach { it.cancel() }
        subs.clear()
        handler.removeCallbacks(keepalive)
    }

    private fun emit(force: Boolean) {
        if (!running || ble.getConnectedUid() != uid) return
        val s = KtopShiftingState.current(context, ble)
        if (!s.established) return

        val front = s.frontGear to s.frontGearMax
        val rear = s.rearGear to s.rearGearMax
        val teeth = s.frontTeeth to s.rearTeeth
        val status = s.batteryPercent?.let { batteryStatus(context, it) }
        val changed = front != lastFront || rear != lastRear || teeth != lastTeeth || status != lastStatus
        if (!force && !changed) return

        try {
            if (s.frontGearKnown) {
                val f = mutableMapOf(
                    DataType.Field.SHIFTING_FRONT_GEAR to s.frontGear.toDouble(),
                    DataType.Field.SHIFTING_FRONT_GEAR_MAX to s.frontGearMax.toDouble(),
                )
                if (s.hasFrontGearSize) f[DataType.Field.SHIFTING_FRONT_GEAR_TEETH] = s.frontTeeth.toDouble()
                emitter.onNext(OnDataPoint(DataPoint(DataType.Type.SHIFTING_FRONT_GEAR, f, uid)))
            }

            val r = mutableMapOf(
                DataType.Field.SHIFTING_REAR_GEAR to s.rearGear.toDouble(),
                DataType.Field.SHIFTING_REAR_GEAR_MAX to s.rearGearMax.toDouble(),
            )
            if (s.hasRearGearSize) r[DataType.Field.SHIFTING_REAR_GEAR_TEETH] = s.rearTeeth.toDouble()
            emitter.onNext(OnDataPoint(DataPoint(DataType.Type.SHIFTING_REAR_GEAR, r, uid)))

            if (status != null) {
                emitter.onNext(OnBatteryStatus(status))
                emitter.onNext(
                    OnDataPoint(
                        DataPoint(
                            DataType.Type.SHIFTING_BATTERY,
                            mapOf(DataType.Field.SHIFTING_BATTERY_STATUS to status.ordinal.toDouble()),
                            uid
                        )
                    )
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "native shifting data point failed", t)
            return
        }

        if (changed) Log.d(TAG, "native shifting: front=$front rear=$rear teeth=$teeth battery=$status")
        lastFront = front
        lastRear = rear
        lastTeeth = teeth
        lastStatus = status
        lastEmitAt = System.currentTimeMillis()
    }
}
