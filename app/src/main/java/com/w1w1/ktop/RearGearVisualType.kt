package com.w1w1.ktop

import android.content.Context
import android.os.Bundle
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

/**
 * Rear gear position, 1..14.
 *
 * RESTORED (2026-10-11) to the exact version confirmed on-device ("rear
 * gear and shifts are back"). A KI2-style rewrite (centered NumericText +
 * ThrottledViewEmitter, no polling loop) was tried on top of this and
 * on-device showed this field stuck at "0" again - reverted wholesale
 * rather than patched blind. Do not change the rendering here without
 * an on-device test.
 */
class RearGearVisualType(
    ext: String,
    private val bleProvider: () -> WheeltopBleClient
) : DataTypeImpl(ext, "rear_gear") {

    companion object {
        private const val TAG = "ktop-ble"
        private const val MAX_GEAR = 14

        // Karoo drops view updates faster than ~1Hz.
        private const val MIN_UPDATE_MS = 1050L

        private val END_PAD = 2.dp
        private val BOTTOM_PAD = (-18).dp

        @OptIn(ExperimentalGlanceRemoteViewsApi::class)
        private val glance = GlanceRemoteViews()
    }

    private fun normalizeGear(g: Int?): Int? {
        val v = g ?: return null
        return if (v in 1..MAX_GEAR) v else null
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.i(
            TAG,
            "RearGearVisualType.startView(preview=${config.preview} grid=${config.gridSize} size=${config.viewSize} textSize=${config.textSize})"
        )
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val sizeSp = config.textSize.toFloat() * 0.92f

        var latest: Int? = if (config.preview) 5 else normalizeGear(bleProvider().getLastGear())

        val sub = if (!config.preview) {
            bleProvider().gearFlow.subscribe { v -> latest = normalizeGear(v) }
        } else null

        fun fmt(v: Int?): String = v?.toString() ?: "-"

        suspend fun renderNow(v: Int?) {
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
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Text(
                        text = txt, style = TextStyle(
                            fontSize = sizeSp.sp,
                            textAlign = TextAlign.End,
                            fontFamily = FontFamily.SansSerif,
                            color = ColorProvider(Color.White, Color.White)
                        )
                    )
                }
            }
            emitter.updateView(result.remoteViews)
        }

        val loopJob: Job = scope.launch {
            if (config.preview) {
                var phase = 1
                while (true) {
                    renderNow(((phase - 1) % MAX_GEAR) + 1)
                    phase++
                    delay(1000)
                }
            } else {
                renderNow(latest)
                var lastRendered: Int? = latest
                while (true) {
                    if (latest != lastRendered) {
                        renderNow(latest)
                        lastRendered = latest
                    }
                    delay(MIN_UPDATE_MS)
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
