package com.w1w1.ktop

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.valterc.ki2.views.DrivetrainView
import com.valterc.ki2.views.GearsView
import com.valterc.ki2.views.battery.BatteryView
import com.valterc.ki2.views.fill.FillView
import io.hammerhead.karooext.models.ViewConfig

/*
 * Ports of Ki2's graphical field views (karoo/views/...ExtensionView.kt,
 * https://github.com/valterc/ki2, MIT License, see LICENSE-ki2.txt).
 * Same layouts (view_karoo_gears*.xml, view_karoo_drivetrain.xml,
 * view_karoo_battery.xml), same widgets, same colors and texts. The only
 * change is the data source: instead of Ki2's ServiceClient listeners,
 * every view gets a KtopShiftingState snapshot via update().
 */

abstract class KtopExtensionView(protected val context: Context) {

    protected val karooTheme: KarooTheme get() = karooTheme(context)

    /**
     * Selected gear color. Ki2 uses grey-blue (hh_gears_active_dark) on the
     * dark theme; ktop uses white like the native Karoo gear fields for
     * better contrast. Light theme unchanged.
     */
    protected val accentColor: Int
        get() = if (karooTheme == KarooTheme.WHITE) context.getColor(R.color.hh_gears_active_light)
        else context.getColor(R.color.white)

    abstract fun createView(layoutInflater: LayoutInflater, viewConfig: ViewConfig): View
    abstract fun update(state: KtopShiftingState)

    fun createView(viewConfig: ViewConfig): View = createView(LayoutInflater.from(context), viewConfig)

    @SuppressLint("InflateParams")
    protected fun inflate(layoutInflater: LayoutInflater, layout: Int, viewConfig: ViewConfig): View {
        val inflatedView = layoutInflater.inflate(layout, null)
        val params = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
            ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        )
        params.matchConstraintMinWidth = viewConfig.viewSize.first
        params.matchConstraintMinHeight = viewConfig.viewSize.second
        inflatedView.layoutParams = params
        return inflatedView
    }

    /** Ki2's battery coloring (critical red / low yellow / ok green / unknown grey). */
    protected fun applyBattery(batteryView: BatteryView, percent: Int?) {
        if (percent != null) {
            batteryView.value = percent.toFloat() / 100
            val color = when {
                percent <= WheeltopConfig.batteryLevelCritical(context) -> R.color.hh_red
                percent <= WheeltopConfig.batteryLevelLow(context) -> R.color.hh_yellow_darker
                else -> R.color.hh_success_green_600
            }
            batteryView.setForegroundColor(context.getColor(color))
            batteryView.setBorderColor(context.getColor(color))
        } else {
            batteryView.setForegroundColor(context.getColor(R.color.battery_background_dark))
            batteryView.setBorderColor(context.getColor(R.color.battery_border_dark))
        }
    }
}

/** Ki2 GearsExtensionView: "Gears Index (visual)" / "Gears Size (visual)". */
class GearsExtensionView(context: Context, private val showGearSize: Boolean = false) : KtopExtensionView(context) {
    private var textViewWaitingForData: TextView? = null
    private var gearsView: GearsView? = null
    private var batteryView: BatteryView? = null
    private var textViewGears: TextView? = null

    override fun createView(layoutInflater: LayoutInflater, viewConfig: ViewConfig): View {
        val v = inflate(layoutInflater, R.layout.view_karoo_gears, viewConfig)
        textViewWaitingForData = v.findViewById(R.id.textview_karoo_gears_waiting_for_data)
        batteryView = v.findViewById(R.id.batteryview_karoo_gears)
        textViewGears = v.findViewById(R.id.textview_karoo_gears)
        gearsView = v.findViewById(R.id.gearsview_karoo_gears)

        if (karooTheme == KarooTheme.WHITE) {
            textViewWaitingForData?.setTextColor(context.getColor(R.color.hh_black))
            textViewGears?.setTextColor(context.getColor(R.color.hh_black))
            gearsView?.setUnselectedGearBorderColor(context.getColor(R.color.hh_gears_border_light))
            gearsView?.setSelectedGearColor(context.getColor(R.color.hh_gears_active_light))
        } else {
            textViewWaitingForData?.setTextColor(context.getColor(R.color.white))
            textViewGears?.setTextColor(context.getColor(R.color.white))
            gearsView?.setUnselectedGearBorderColor(context.getColor(R.color.white))
            gearsView?.setSelectedGearColor(context.getColor(R.color.white))
        }
        return v
    }

    override fun update(state: KtopShiftingState) {
        val gearsView = gearsView ?: return
        val batteryView = batteryView ?: return
        val textViewGears = textViewGears ?: return
        val textViewWaitingForData = textViewWaitingForData ?: return

        if (!state.established) {
            gearsView.visibility = View.INVISIBLE
            batteryView.visibility = View.INVISIBLE
            textViewGears.visibility = View.INVISIBLE
            textViewWaitingForData.visibility = View.VISIBLE
            return
        }
        textViewWaitingForData.visibility = View.INVISIBLE
        gearsView.visibility = View.VISIBLE
        batteryView.visibility = View.VISIBLE
        textViewGears.visibility = View.VISIBLE

        gearsView.setGears(state.frontGearMax, state.frontGear, state.rearGearMax, state.rearGear)
        textViewGears.text = if (showGearSize) {
            context.getString(R.string.text_param_gearing, state.frontTeeth, state.rearTeeth)
        } else {
            context.getString(R.string.text_param_gearing, state.frontGear, state.rearGear)
        }
        gearsView.selectedGearColor = accentColor
        applyBattery(batteryView, state.batteryPercent)
    }
}

/** Ki2 GearsDetailedExtensionView: "Gears Detailed (visual)". */
class GearsDetailedExtensionView(context: Context) : KtopExtensionView(context) {
    private var textViewWaitingForData: TextView? = null
    private var gearsView: GearsView? = null
    private var batteryView: BatteryView? = null
    private var textViewGears: TextView? = null
    private var textViewBattery: TextView? = null

    override fun createView(layoutInflater: LayoutInflater, viewConfig: ViewConfig): View {
        val v = inflate(layoutInflater, R.layout.view_karoo_gears_detailed, viewConfig)
        textViewWaitingForData = v.findViewById(R.id.textview_karoo_gears_detailed_waiting_for_data)
        batteryView = v.findViewById(R.id.batteryview_karoo_gears_detailed)
        textViewGears = v.findViewById(R.id.textview_karoo_gears_detailed)
        textViewBattery = v.findViewById(R.id.textview_karoo_gears_detailed_battery)
        gearsView = v.findViewById(R.id.gearsview_karoo_gears_detailed)

        if (karooTheme == KarooTheme.WHITE) {
            textViewWaitingForData?.setTextColor(context.getColor(R.color.hh_black))
            textViewGears?.setTextColor(context.getColor(R.color.hh_black))
            textViewBattery?.setTextColor(context.getColor(R.color.hh_black))
            gearsView?.setUnselectedGearBorderColor(context.getColor(R.color.hh_gears_border_light))
            gearsView?.setSelectedGearColor(context.getColor(R.color.hh_gears_active_light))
        } else {
            textViewWaitingForData?.setTextColor(context.getColor(R.color.white))
            textViewGears?.setTextColor(context.getColor(R.color.white))
            textViewBattery?.setTextColor(context.getColor(R.color.white))
            gearsView?.setUnselectedGearBorderColor(context.getColor(R.color.white))
            gearsView?.setSelectedGearColor(context.getColor(R.color.white))
        }
        return v
    }

    override fun update(state: KtopShiftingState) {
        val gearsView = gearsView ?: return
        val batteryView = batteryView ?: return
        val textViewGears = textViewGears ?: return
        val textViewBattery = textViewBattery ?: return
        val textViewWaitingForData = textViewWaitingForData ?: return

        if (!state.established) {
            gearsView.visibility = View.INVISIBLE
            batteryView.visibility = View.INVISIBLE
            textViewGears.visibility = View.INVISIBLE
            textViewBattery.visibility = View.INVISIBLE
            textViewWaitingForData.visibility = View.VISIBLE
            return
        }
        textViewWaitingForData.visibility = View.INVISIBLE
        gearsView.visibility = View.VISIBLE
        batteryView.visibility = View.VISIBLE
        textViewGears.visibility = View.VISIBLE
        textViewBattery.visibility = View.VISIBLE

        gearsView.setGears(state.frontGearMax, state.frontGear, state.rearGearMax, state.rearGear)
        textViewGears.text = context.getString(
            R.string.text_param_gearing_detailed,
            state.frontGear, state.frontTeeth, state.rearGear, state.rearTeeth
        )
        gearsView.selectedGearColor = accentColor

        applyBattery(batteryView, state.batteryPercent)
        val pct = state.batteryPercent
        if (pct != null) {
            textViewBattery.text = context.getString(R.string.text_param_percentage, pct)
            textViewBattery.visibility = View.VISIBLE
        } else {
            textViewBattery.visibility = View.INVISIBLE
        }
    }
}

/** Ki2 DrivetrainExtensionView: "Drivetrain Index (visual)" / "Drivetrain Size (visual)". */
class DrivetrainExtensionView(context: Context, private val showGearSize: Boolean = false) : KtopExtensionView(context) {
    private var textViewWaitingForData: TextView? = null
    private var drivetrainView: DrivetrainView? = null
    private var batteryView: BatteryView? = null
    private var textViewGears: TextView? = null

    override fun createView(layoutInflater: LayoutInflater, viewConfig: ViewConfig): View {
        val v = inflate(layoutInflater, R.layout.view_karoo_drivetrain, viewConfig)
        textViewWaitingForData = v.findViewById(R.id.textview_karoo_drivetrain_waiting_for_data)
        drivetrainView = v.findViewById(R.id.drivetrainview_karoo_drivetrain)
        batteryView = v.findViewById(R.id.batteryview_karoo_drivetrain)
        textViewGears = v.findViewById(R.id.textview_karoo_drivetrain)

        if (karooTheme == KarooTheme.WHITE) {
            textViewWaitingForData?.setTextColor(context.getColor(R.color.hh_black))
            textViewGears?.setTextColor(context.getColor(R.color.hh_black))
            drivetrainView?.setTextColor(context.getColor(R.color.hh_black))
            drivetrainView?.setChainColor(context.getColor(R.color.hh_black))
        } else {
            textViewWaitingForData?.setTextColor(context.getColor(R.color.white))
            textViewGears?.setTextColor(context.getColor(R.color.white))
            drivetrainView?.setTextColor(context.getColor(R.color.white))
            drivetrainView?.setChainColor(context.getColor(R.color.hh_divider_color))
        }
        return v
    }

    override fun update(state: KtopShiftingState) {
        val drivetrainView = drivetrainView ?: return
        val batteryView = batteryView ?: return
        val textViewGears = textViewGears ?: return
        val textViewWaitingForData = textViewWaitingForData ?: return

        if (!state.established) {
            drivetrainView.visibility = View.INVISIBLE
            batteryView.visibility = View.INVISIBLE
            textViewGears.visibility = View.INVISIBLE
            textViewWaitingForData.visibility = View.VISIBLE
            return
        }
        textViewWaitingForData.visibility = View.INVISIBLE
        batteryView.visibility = View.VISIBLE
        textViewGears.visibility = View.VISIBLE
        drivetrainView.visibility = View.VISIBLE

        drivetrainView.setGears(state.frontGearMax, state.frontGear, state.rearGearMax, state.rearGear)
        textViewGears.text = if (showGearSize) {
            context.getString(R.string.text_param_gearing, state.frontTeeth, state.rearTeeth)
        } else {
            context.getString(R.string.text_param_gearing, state.frontGear, state.rearGear)
        }
        drivetrainView.selectedGearColor = accentColor
        applyBattery(batteryView, state.batteryPercent)
    }
}

/** Ki2 BikeBatteryExtensionView: "Bike Battery (visual)". */
class BikeBatteryExtensionView(context: Context) : KtopExtensionView(context) {
    private var textView: TextView? = null
    private var fillView: FillView? = null

    override fun createView(layoutInflater: LayoutInflater, viewConfig: ViewConfig): View {
        val v = inflate(layoutInflater, R.layout.view_karoo_battery, viewConfig)
        textView = v.findViewById(R.id.textview_karoo_battery)
        fillView = v.findViewById(R.id.fillview_karoo_battery)
        if (karooTheme == KarooTheme.WHITE) {
            textView?.setTextColor(context.getColor(R.color.hh_black))
            textView?.setBackgroundColor(context.getColor(R.color.white))
        }
        return v
    }

    override fun update(state: KtopShiftingState) {
        val fillView = fillView ?: return
        val textView = textView ?: return
        val pct = state.batteryPercent

        if (pct == null) {
            fillView.setForegroundColor(context.getColor(R.color.hh_grey))
            fillView.value = 0f
            textView.setText(R.string.text_na)
        } else {
            val color = when {
                pct <= WheeltopConfig.batteryLevelCritical(context) -> R.color.hh_red_600
                pct <= WheeltopConfig.batteryLevelLow(context) -> R.color.hh_yellow
                else -> R.color.hh_success_green_600
            }
            fillView.setForegroundColor(context.getColor(color))
            textView.text = context.getString(R.string.text_param_percentage, pct)
            fillView.value = pct * 0.01f
        }
    }
}
