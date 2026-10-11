package com.w1w1.ktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.core.graphics.createBitmap

/**
 * Renders a plain (non-Glance) Android [View] - the vendored
 * com.valterc.ki2.views.{GearsView,BatteryView} - into an off-screen
 * [Bitmap], the same technique the real KI2 app uses for its graphical
 * data types (karoo/datatypes/RenderedVisualDataType.kt): karoo-ext's
 * RemoteViews-based field rendering can't host a custom View directly,
 * so it's drawn into a Bitmap and shown via an ImageView
 * (remote_view_image.xml).
 *
 * Must be called on the main thread (View.measure/layout/draw) - the
 * graphical data types call this from a Dispatchers.Main.immediate
 * coroutine, same as the confirmed-working text fields.
 */
object BitmapViewRenderer {
    fun render(view: View, widthPx: Int, heightPx: Int): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bitmap = createBitmap(w, h)
        bitmap.eraseColor(Color.TRANSPARENT)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
        view.draw(Canvas(bitmap))
        return bitmap
    }
}
