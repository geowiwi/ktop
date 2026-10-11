package com.w1w1.ktop

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
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
import java.text.DecimalFormat

/**
 * Ki2's text fields (karoo/datatypes/text/..., https://github.com/valterc/ki2,
 * MIT License): same field set, same values and number formats
 * ("00-00" gears, "#.00" ratio, plain counts), "…" while waiting for data,
 * "N/A" when the value isn't available (e.g. no custom gearing entered, or
 * front gear on a 2x system - EDS doesn't report it).
 *
 * Rendering is NOT Ki2's TextView/ThrottledViewEmitter: that combination was
 * tried here and on-device the fields got stuck at "0" / shifted. Instead
 * this uses exactly the geometry and update loop of RearGearVisualType,
 * confirmed working on-device (0.92x textSize, bottom-anchored, -18dp).
 */
class KtopTextType(
    ext: String,
    typeId: String,
    private val bleProvider: () -> WheeltopBleClient,
    /** true: show "…" until gear data has arrived (Ki2 behaviour for gear fields). */
    private val needsGearData: Boolean,
    /** value to show, or null for "N/A". */
    private val value: (KtopShiftingState) -> String?,
) : DataTypeImpl(ext, typeId) {

    private val localTypeId = typeId

    companion object {
        private const val TAG = "ktop-ble"
        private const val MIN_UPDATE_MS = 1050L
        private const val END_PAD_DP = 2f
        private const val BOTTOM_PAD_DP = -18f
        /** Horizontal room kept free next to the text (header icon side, rounding). */
        private const val SIDE_MARGIN_DP = 10f

        @OptIn(ExperimentalGlanceRemoteViewsApi::class)
        private val glance = GlanceRemoteViews()

        private val twoDigits = DecimalFormat("00")
        private val ratio = DecimalFormat("#.00")

        /** All Ki2 text fields except Rear Gear / Shift Count (ktop's existing rear_gear / shift_count). */
        fun ki2Fields(ext: String, ble: () -> WheeltopBleClient): List<KtopTextType> = listOf(
            KtopTextType(ext, "DATATYPE_GEAR_RATIO", ble, true) { s ->
                if (s.hasGearSizes) ratio.format(s.gearRatio) else null
            },
            KtopTextType(ext, "DATATYPE_GEARS_INDEX", ble, true) { s ->
                if (s.frontGearKnown) "${twoDigits.format(s.frontGear)}-${twoDigits.format(s.rearGear)}" else null
            },
            KtopTextType(ext, "DATATYPE_GEARS_SIZE", ble, true) { s ->
                if (s.hasGearSizes) "${twoDigits.format(s.frontTeeth)}-${twoDigits.format(s.rearTeeth)}" else null
            },
            KtopTextType(ext, "DATATYPE_FRONT_GEAR_INDEX", ble, true) { s ->
                if (s.frontGearKnown) s.frontGear.toString() else null
            },
            KtopTextType(ext, "DATATYPE_FRONT_GEAR_SIZE", ble, true) { s ->
                if (s.hasFrontGearSize) s.frontTeeth.toString() else null
            },
            KtopTextType(ext, "DATATYPE_REAR_GEAR_SIZE", ble, true) { s ->
                if (s.hasRearGearSize) s.rearTeeth.toString() else null
            },
            KtopTextType(ext, "DATATYPE_FRONT_SHIFT_COUNT", ble, false) { s ->
                s.frontShiftCount?.toString()
            },
            KtopTextType(ext, "DATATYPE_REAR_SHIFT_COUNT", ble, false) { s ->
                s.rearShiftCount.toString()
            },
        )
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.i(TAG, "KtopTextType[$localTypeId].startView(preview=${config.preview} size=${config.viewSize} textSize=${config.textSize})")
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sizeSp = config.textSize.toFloat() * 0.92f

        val (boxAlign, textAlign) = when (config.alignment) {
            ViewConfig.Alignment.LEFT -> Alignment.BottomStart to TextAlign.Start
            ViewConfig.Alignment.CENTER -> Alignment.BottomCenter to TextAlign.Center
            ViewConfig.Alignment.RIGHT -> Alignment.BottomEnd to TextAlign.End
        }

        fun text(): String {
            val state = if (config.preview) KtopShiftingState.preview()
            else KtopShiftingState.current(context, bleProvider())
            if (needsGearData && !state.established) return "…"
            return value(state) ?: context.getString(R.string.text_na)
        }

        // Longer Ki2 values ("01-03", "40-21", "N/A") don't fit a half-width
        // field at the confirmed 0.92x size (on-device: "01-03" was cut to
        // "01-0"). Shrink the font just enough to fit the field width, and
        // scale the (font-size dependent) negative bottom padding with it so
        // the baseline stays where the confirmed geometry puts it.
        val dm = context.resources.displayMetrics
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
        fun fittedSp(txt: String): Float {
            val availablePx = config.viewSize.first - (END_PAD_DP + SIDE_MARGIN_DP) * dm.density
            paint.textSize = sizeSp * dm.scaledDensity
            val w = paint.measureText(txt)
            return if (w <= 0f || w <= availablePx) sizeSp else sizeSp * (availablePx / w) * 0.97f
        }

        suspend fun renderNow(txt: String) {
            val sp = fittedSp(txt)
            val bottomPad = (BOTTOM_PAD_DP * (sp / sizeSp)).dp
            val result = glance.compose(
                context = context,
                size = DpSize.Unspecified,
                state = null,
                appWidgetOptions = Bundle()
            ) {
                Box(
                    modifier = GlanceModifier.fillMaxSize()
                        .padding(end = END_PAD_DP.dp, bottom = bottomPad),
                    contentAlignment = boxAlign
                ) {
                    Text(
                        text = txt, style = TextStyle(
                            fontSize = sp.sp,
                            textAlign = textAlign,
                            fontFamily = FontFamily.SansSerif,
                            color = ColorProvider(Color.White, Color.White)
                        )
                    )
                }
            }
            emitter.updateView(result.remoteViews)
        }

        val loopJob: Job = scope.launch {
            var lastRendered: String? = null
            while (true) {
                val txt = text()
                if (txt != lastRendered) {
                    renderNow(txt)
                    lastRendered = txt
                }
                delay(MIN_UPDATE_MS)
            }
        }

        emitter.setCancellable {
            loopJob.cancel()
            scope.cancel()
        }
    }
}
