package com.w1w1.ktop

import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
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
 * Graphical Ki2 field: equivalent of Ki2's RenderedVisualDataType
 * (https://github.com/valterc/ki2, MIT License). Inflates the Ki2 view,
 * draws it into a Bitmap of exactly config.viewSize and hands it to Karoo
 * through a RemoteViews ImageView (remote_view_image.xml). Like Ki2 it
 * hides Karoo's header (the Ki2 layouts draw their own) and blanks the
 * standard stream container.
 *
 * Update loop: same pattern as the text fields that are confirmed working
 * on-device (Main-thread coroutine, re-render at most every ~1s when the
 * state changed, emitter.updateView() called directly). Ki2's
 * ThrottledViewEmitter is deliberately not used - a port of it was the
 * prime suspect when the text fields got stuck at "0" on-device.
 */
class KtopRenderedVisualType(
    ext: String,
    typeId: String,
    private val bleProvider: () -> WheeltopBleClient,
    private val viewFactory: (Context) -> KtopExtensionView,
) : DataTypeImpl(ext, typeId) {

    private val localTypeId = typeId

    companion object {
        private const val TAG = "ktop-ble"
        private const val MIN_UPDATE_MS = 1050L
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.i(TAG, "KtopRenderedVisualType[$localTypeId].startView(preview=${config.preview} size=${config.viewSize})")
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        emitter.onNext(ShowCustomStreamState(message = "", color = null))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val loopJob: Job = scope.launch {
            val extensionView = viewFactory(context)
            val view = extensionView.createView(config)
            var last: KtopShiftingState? = null

            while (true) {
                val state = if (config.preview) KtopShiftingState.preview()
                else KtopShiftingState.current(context, bleProvider())

                if (state != last) {
                    try {
                        extensionView.update(state)
                        val bitmap = BitmapViewRenderer.render(view, config.viewSize.first, config.viewSize.second)
                        val rv = RemoteViews(context.packageName, R.layout.remote_view_image)
                        rv.setImageViewBitmap(R.id.imageView_remote, bitmap)
                        emitter.updateView(rv)
                        last = state
                    } catch (t: Throwable) {
                        Log.e(TAG, "KtopRenderedVisualType[$localTypeId] render failed", t)
                    }
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
