package com.w1w1.ktop

import android.util.Log
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ki2's "Shifting Battery %" (ShiftingBatteryPercentageDataType,
 * https://github.com/valterc/ki2, MIT License): a plain numeric field
 * rendered natively by Karoo via startStream() - exactly as Ki2 does it
 * (no graphical flag in extension_info.xml). Emits every 5s like Ki2.
 * Source: rear derailleur main pack, voltage -> % via BatteryCurves.
 *
 * Note: this is a NEW typeId, so Karoo has no cached graphical metadata
 * for it (the reason startStream() never got called for the old rear_gear
 * slot). If it nevertheless stays empty on-device, that's the thing to
 * look at - log line "ShiftingBatteryPercentageType.startStream()".
 */
class ShiftingBatteryPercentageType(
    ext: String,
    private val bleProvider: () -> WheeltopBleClient,
) : DataTypeImpl(ext, "DATATYPE_SHIFTING_BATTERY_PERCENTAGE") {

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.i("ktop-ble", "ShiftingBatteryPercentageType.startStream()")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val job = scope.launch {
            while (true) {
                val v = bleProvider().getLastRearBatteryV()
                if (v != null && v > 0.0) {
                    val pct = Math.round(BatteryCurves.mainPackPct(v) * 100f).toDouble()
                    emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to pct))))
                } else {
                    emitter.onNext(StreamState.Searching)
                }
                delay(5_000L)
            }
        }
        emitter.setCancellable {
            job.cancel()
            scope.cancel()
        }
    }
}
