package com.w1w1.ktop

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.round

/**
 * Battery voltage fields (RD/L/R/FD).
 *
 * RESTORED (2026-10-11) to the exact rendering confirmed on-device ("die
 * batteriewerte passen wieder"): textSize * 0.90, bottom padding -22dp,
 * bottom-anchored box with config.alignment mapping. A KI2-style rewrite
 * (centered NumericText, 0.97x + top padding) shifted these fields
 * on-device and was reverted wholesale.
 *
 * Only addition vs. the confirmed version: [isFrontDerailleurField]. For
 * front_batt_v, if the rider hasn't ticked "Front derailleur installed"
 * in the app's settings (WheeltopConfig), the text shown is "N/A"
 * instead of "0.00". Same box, font size and padding - only the string
 * changes, so this can't affect layout.
 */
class VoltageVisualType(
    ext: String,
    typeId: String,
    private val bleProvider: () -> WheeltopBleClient,
    private val getInitial: (WheeltopBleClient) -> Double?,
    private val flow: (WheeltopBleClient) -> WheeltopBleClient.SimpleBus<Double>,
    private val isFrontDerailleurField: Boolean = false,
) : DataTypeImpl(ext, typeId) {

    private val localTypeId: String = typeId

    companion object {
        private const val TAG = "ktop-ble"

        // Karoo drops view updates faster than ~1Hz.
        private const val MIN_UPDATE_MS = 1050L

        // Confirmed on-device: full unscaled textSize overflows the box,
        // 0.90x + -22dp bottom padding fits cleanly.
        private val END_PAD = 2.dp
        private val BOTTOM_PAD = (-22).dp

        @OptIn(ExperimentalGlanceRemoteViewsApi::class)
        private val glance = GlanceRemoteViews()
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.i(
            TAG,
            "VoltageVisualType[$localTypeId].startView(preview=${config.preview} grid=${config.gridSize} size=${config.viewSize} textSize=${config.textSize} alignment=${config.alignment})"
        )
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val sizeSp = config.textSize.toFloat() * 0.90f

        val (boxAlign, textAlign) = when (config.alignment) {
            ViewConfig.Alignment.LEFT -> Alignment.BottomStart to TextAlign.Start
            ViewConfig.Alignment.CENTER -> Alignment.BottomCenter to TextAlign.Center
            ViewConfig.Alignment.RIGHT -> Alignment.BottomEnd to TextAlign.End
        }

        var latest: Double? = if (config.preview) 8.17 else getInitial(bleProvider())

        val sub = if (!config.preview) {
            flow(bleProvider()).subscribe { v -> latest = v }
        } else null

        var lastUpdateAt = 0L
        var lastRendered: Double? = null
        var lastFdMissing: Boolean? = null

        fun fdMissing(): Boolean =
            isFrontDerailleurField && !config.preview && !bleProvider().hasFrontDerailleur()

        fun fmt(v: Double?): String = when {
            fdMissing() -> context.getString(R.string.text_na)
            v != null -> String.format(Locale.US, "%.2f", v)
            else -> "0.00"
        }

        fun norm(v: Double?): Double? {
            if (v == null) return null
            return round(v * 100.0) / 100.0
        }

        suspend fun renderNow(v: Double?) {
            val txt = fmt(v)
            val result = glance.compose(
                context = context,
                size = DpSize.Unspecified,
                state = null,
                appWidgetOptions = Bundle()
            ) {
                Box(
                    modifier = GlanceModifier.fillMaxSize()
                        .padding(end = END_PAD, bottom = BOTTOM_PAD),
                    contentAlignment = boxAlign
                ) {
                    Text(
                        text = txt, style = TextStyle(
                            fontSize = sizeSp.sp,
                            textAlign = textAlign,
                            fontFamily = FontFamily.SansSerif,
                            color = ColorProvider(Color.White, Color.White)
                        )
                    )
                }
            }

            emitter.updateView(result.remoteViews)
            lastUpdateAt = SystemClock.elapsedRealtime()
            lastRendered = v
            lastFdMissing = fdMissing()
        }

        suspend fun renderThrottled(v: Double?) {
            val vv = norm(v)
            val prev = norm(lastRendered)
            val fdChanged = lastFdMissing != fdMissing()

            if (!fdChanged) {
                if (prev != null && vv != null && kotlin.math.abs(prev - vv) < 0.0001) return
                if (prev == null && vv == null) return
            }

            val now = SystemClock.elapsedRealtime()
            val delayMs = max(0L, (lastUpdateAt + MIN_UPDATE_MS) - now)
            if (delayMs > 0) delay(delayMs)
            renderNow(vv)
        }

        val loopJob: Job = scope.launch {
            if (config.preview) {
                var phase = 0
                while (true) {
                    val demo = 8.10 + (phase % 8) * 0.01
                    renderThrottled(demo)
                    phase++
                    delay(1000)
                }
            } else {
                renderThrottled(latest)
                while (true) {
                    renderThrottled(latest)
                    delay(1000)
                }
            }
        }

        emitter.setCancellable {
            loopJob.cancel()
            sub?.cancel()
            scope.cancel()
        }
    }
}
