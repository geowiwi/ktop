package com.w1w1.ktop

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.valterc.ki2.views.GearsView
import com.valterc.ki2.views.battery.BatteryView
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RideState

/**
 * Gear overlay on top of every ride page (also the map), like Ki2's
 * primary overlay (https://github.com/valterc/ki2, MIT License).
 *
 * - Shown while the Ride app is open (RIDE_APP_OPENED broadcast or a
 *   recording ride), removed when the ride ends (RIDE_STOP / Idle).
 * - Pops up on every gear change for the configured time (or stays
 *   visible). Tap to hide.
 * - Needs "Display over other apps" (SYSTEM_ALERT_WINDOW), granted from
 *   ktop's Settings tab.
 *
 * Runs in the extension service; the Settings tab uses showTest() to
 * preview the look with the current settings.
 */
class KtopOverlay(
    private val context: Context,
    private val karooSystem: KarooSystemService?,
    private val ble: WheeltopBleClient,
) {
    companion object {
        private const val TAG = "ktop-ble"
        private const val TEST_DURATION_MS = 4000L

        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** Preview from the Settings tab (works without a ride). */
        fun showTest(context: Context) {
            val app = context.applicationContext
            if (!canDraw(app)) return
            val overlay = KtopOverlay(app, null, WheeltopBleClient.getInstance(app))
            overlay.show(force = true, test = true)
            overlay.handler.postDelayed({ overlay.close() }, TEST_DURATION_MS + 500)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val subs = mutableListOf<WheeltopBleClient.Subscription>()
    private var consumerId: String? = null
    private var rideAppOpen = false
    private var wasRiding = false
    private var started = false

    private var root: View? = null
    private var appliedConfig: String? = null
    private var lastShownAt = 0L
    private val hideRunnable = Runnable { root?.visibility = View.GONE }

    private val rideAppOpened = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            rideAppOpen = true
        }
    }
    private val rideStopped = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            rideAppOpen = false
            close()
        }
    }

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(
            context, rideAppOpened, IntentFilter("io.hammerhead.intent.action.RIDE_APP_OPENED"),
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            context, rideStopped, IntentFilter("io.hammerhead.hx.intent.action.RIDE_STOP"),
            ContextCompat.RECEIVER_EXPORTED
        )
        consumerId = karooSystem?.addConsumer<RideState> { state ->
            handler.post {
                if (state is RideState.Idle) {
                    // Ride ended (Idle after recording/pause). RIDE_STOP
                    // usually closes the overlay already, this is the fallback.
                    if (wasRiding) { close(); rideAppOpen = false }
                    wasRiding = false
                } else {
                    rideAppOpen = true
                    wasRiding = true
                }
            }
        }
        // gearFlow fires at the start of a shift too - the overlay pops up
        // as soon as the derailleur starts moving.
        subs += ble.gearFlow.subscribe { handler.post { onGear() } }
        val refresh: (Double) -> Unit = { handler.post { if (root?.visibility == View.VISIBLE) bind(false) } }
        subs += ble.rearBatteryVFlow.subscribe(refresh)
        Log.i(TAG, "overlay handler started")
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(rideAppOpened) }
        runCatching { context.unregisterReceiver(rideStopped) }
        consumerId?.let { karooSystem?.removeConsumer(it) }
        consumerId = null
        subs.forEach { it.cancel() }
        subs.clear()
        close()
    }

    private fun onGear() {
        if (!rideAppOpen) return
        if (!WheeltopConfig.overlayEnabled(context)) {
            close()
            return
        }
        show(force = false, test = false)
    }

    private fun configSignature(): String =
        listOf(
            WheeltopConfig.overlayPosition(context),
            WheeltopConfig.overlayTheme(context),
            WheeltopConfig.overlayOpacity(context),
            karooTheme(context),
        ).joinToString("|")

    private fun isDark(): Boolean = when (WheeltopConfig.overlayTheme(context)) {
        "dark" -> true
        "light" -> false
        else -> karooTheme(context) == KarooTheme.DARK
    }

    @SuppressLint("InflateParams")
    private fun open(): Boolean {
        val sig = configSignature()
        if (root != null && sig == appliedConfig) return true
        close()
        if (!canDraw(context)) {
            Log.w(TAG, "overlay: 'display over other apps' not granted")
            return false
        }
        return try {
            val v = LayoutInflater.from(context).inflate(R.layout.view_ktop_overlay, null)
            val dark = isDark()
            v.findViewById<View>(R.id.ktop_overlay_card).setBackgroundResource(
                if (dark) R.drawable.background_ktop_overlay_dark else R.drawable.background_ktop_overlay_light
            )
            val text = context.getColor(if (dark) R.color.white else R.color.hh_black)
            v.findViewById<TextView>(R.id.ktop_overlay_gear).setTextColor(text)
            v.findViewById<TextView>(R.id.ktop_overlay_teeth).setTextColor(text)
            v.findViewById<TextView>(R.id.ktop_overlay_battery).setTextColor(text)
            v.findViewById<GearsView>(R.id.ktop_overlay_gears).apply {
                setUnselectedGearBorderColor(context.getColor(if (dark) R.color.white else R.color.hh_gears_border_light))
                setSelectedGearColor(context.getColor(if (dark) R.color.white else R.color.hh_gears_active_light))
            }
            v.alpha = WheeltopConfig.overlayOpacity(context) / 100f
            v.setOnClickListener {
                handler.removeCallbacks(hideRunnable)
                v.visibility = View.GONE
            }
            v.visibility = View.GONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = when (WheeltopConfig.overlayPosition(context)) {
                "center" -> Gravity.CENTER_VERTICAL
                "bottom" -> Gravity.BOTTOM
                else -> Gravity.TOP
            }
            windowManager.addView(v, params)
            root = v
            appliedConfig = sig
            true
        } catch (t: Throwable) {
            Log.w(TAG, "overlay: could not add window", t)
            root = null
            false
        }
    }

    private fun close() {
        handler.removeCallbacks(hideRunnable)
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        appliedConfig = null
    }

    private fun show(force: Boolean, test: Boolean) {
        if (!open()) return
        val v = root ?: return
        if (!bind(test) && !force) return
        lastShownAt = System.currentTimeMillis()
        v.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)
        val duration = if (test) TEST_DURATION_MS else WheeltopConfig.overlayDurationMs(context).toLong()
        if (duration > 0) handler.postDelayed(hideRunnable, duration)
    }

    /** Fills the overlay; false if there is nothing to show yet. */
    private fun bind(test: Boolean): Boolean {
        val v = root ?: return false
        var s = KtopShiftingState.current(context, ble)
        if (!s.established) {
            if (!test) return false
            s = KtopShiftingState.preview()
        }

        val gear = v.findViewById<TextView>(R.id.ktop_overlay_gear)
        gear.text = if (s.frontGearMax > 1) {
            context.getString(R.string.text_overlay_gear_2x, if (s.frontGearKnown) s.frontGear.toString() else "-", s.rearGear, s.rearGearMax)
        } else {
            context.getString(R.string.text_overlay_gear, s.rearGear, s.rearGearMax)
        }

        val teeth = v.findViewById<TextView>(R.id.ktop_overlay_teeth)
        if (s.hasGearSizes) {
            teeth.text = context.getString(R.string.text_overlay_teeth, s.frontTeeth, s.rearTeeth, s.gearRatio)
            teeth.visibility = View.VISIBLE
        } else if (s.hasRearGearSize) {
            teeth.text = context.getString(R.string.text_overlay_rear_teeth, s.rearTeeth)
            teeth.visibility = View.VISIBLE
        } else {
            teeth.visibility = View.GONE
        }

        v.findViewById<GearsView>(R.id.ktop_overlay_gears)
            .setGears(s.frontGearMax, s.frontGear, s.rearGearMax, s.rearGear)

        val batteryView = v.findViewById<BatteryView>(R.id.ktop_overlay_battery_view)
        val batteryText = v.findViewById<TextView>(R.id.ktop_overlay_battery)
        val pct = s.batteryPercent
        if (pct != null) {
            batteryView.value = pct / 100f
            val color = context.getColor(
                when {
                    pct <= WheeltopConfig.batteryLevelCritical(context) -> R.color.hh_red
                    pct <= WheeltopConfig.batteryLevelLow(context) -> R.color.hh_yellow_darker
                    else -> R.color.hh_success_green_600
                }
            )
            batteryView.setForegroundColor(color)
            batteryView.setBorderColor(color)
            batteryText.text = context.getString(R.string.text_param_percentage, pct)
            batteryView.visibility = View.VISIBLE
            batteryText.visibility = View.VISIBLE
        } else {
            batteryView.visibility = View.GONE
            batteryText.visibility = View.GONE
        }
        return true
    }
}
