package com.w1w1.ktop.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.valterc.ki2.views.DrivetrainView
import com.w1w1.ktop.BatteryCurves
import com.w1w1.ktop.DeviceStore
import com.w1w1.ktop.KtopShiftingState
import com.w1w1.ktop.R
import com.w1w1.ktop.WheeltopBleClient
import com.w1w1.ktop.WheeltopConfig
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ki2's device details screen (fragments/devices/details, MIT License),
 * adapted to Wheeltop EDS: live drivetrain view + gears, gearing,
 * race mode / sleep mode, batteries (RD, FD, both shifters), model and
 * firmware versions, rename / remove.
 *
 * Opening this screen connects to the device (shared BLE connection, see
 * WheeltopBleClient.connect - if Karoo is already connected to it the
 * existing connection is reused). Leaving releases only this screen's claim.
 */
class DeviceDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAC = "mac"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ble: WheeltopBleClient
    private lateinit var mac: String
    private val subs = mutableListOf<WheeltopBleClient.Subscription>()

    private lateinit var name: TextView
    private lateinit var macText: TextView
    private lateinit var status: TextView
    private lateinit var drivetrain: DrivetrainView
    private lateinit var rearGear: TextView
    private lateinit var frontGear: TextView
    private lateinit var shiftCount: TextView
    private lateinit var totalGears: TextView
    private lateinit var gearing: TextView
    private lateinit var race: SwitchMaterial
    private lateinit var sleep: SwitchMaterial

    // true while refresh() sets the switches, so their listeners ignore it
    private var updatingSwitches = false
    private lateinit var battRd: TextView
    private lateinit var battFd: TextView
    private lateinit var battLeft: TextView
    private lateinit var battRight: TextView
    private lateinit var model: TextView
    private lateinit var firmware: TextView
    private lateinit var reconnect: MaterialButton

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_details)
        ble = WheeltopBleClient.getInstance(applicationContext)
        mac = intent.getStringExtra(EXTRA_MAC) ?: run { finish(); return }

        name = findViewById(R.id.textview_device_details_name)
        macText = findViewById(R.id.textview_device_details_mac)
        status = findViewById(R.id.textview_device_details_connection_status)
        drivetrain = findViewById(R.id.drivetrainview_device_details_gears)
        rearGear = findViewById(R.id.textview_device_details_rear_gear)
        frontGear = findViewById(R.id.textview_device_details_front_gear)
        shiftCount = findViewById(R.id.textview_device_details_shift_count)
        totalGears = findViewById(R.id.textview_device_details_total_gears)
        gearing = findViewById(R.id.textview_device_details_gearing)
        race = findViewById(R.id.switch_device_details_race_mode)
        sleep = findViewById(R.id.switch_device_details_sleep_mode)
        battRd = findViewById(R.id.textview_device_details_battery_rd)
        battFd = findViewById(R.id.textview_device_details_battery_fd)
        battLeft = findViewById(R.id.textview_device_details_battery_left)
        battRight = findViewById(R.id.textview_device_details_battery_right)
        model = findViewById(R.id.textview_device_details_model)
        firmware = findViewById(R.id.textview_device_details_firmware)
        reconnect = findViewById(R.id.button_device_details_reconnect)

        drivetrain.setSelectedGearColor(getColor(R.color.hh_gears_active_light))
        drivetrain.setTextColor(getColor(R.color.hh_black))
        drivetrain.setChainColor(getColor(R.color.hh_black))

        findViewById<ExtendedFloatingActionButton>(R.id.button_back).setOnClickListener { finish() }
        findViewById<View>(R.id.constraintlayout_device_details_gearing).setOnClickListener {
            startActivity(Intent(this, DeviceGearingActivity::class.java).putExtra(EXTRA_MAC, mac))
        }
        findViewById<MaterialButton>(R.id.button_device_details_rename).setOnClickListener { rename() }
        findViewById<MaterialButton>(R.id.button_device_details_remove).setOnClickListener { remove() }
        reconnect.setOnClickListener { connect() }

        // Switches (tap or slide): independent settings, both can be on at once.
        race.setOnCheckedChangeListener { _, checked -> if (!updatingSwitches) onRaceChanged(checked) }
        sleep.setOnCheckedChangeListener { _, checked -> if (!updatingSwitches) onSleepChanged(checked) }

        connect()
    }

    override fun onResume() {
        super.onResume()
        subs += ble.gearFlow.subscribe { main.post { refresh() } }
        subs += ble.frontGearFlow.subscribe { main.post { refresh() } }
        subs += ble.infoFlow.subscribe { main.post { refresh() } }
        subs += ble.rearBatteryVFlow.subscribe { main.post { refresh() } }
        subs += ble.frontBatteryVFlow.subscribe { main.post { refresh() } }
        subs += ble.leftBatteryVFlow.subscribe { main.post { refresh() } }
        subs += ble.rightBatteryVFlow.subscribe { main.post { refresh() } }
        subs += ble.shiftCountFlow.subscribe { main.post { refresh() } }
        main.post(ticker)
    }

    override fun onPause() {
        subs.forEach { it.cancel() }
        subs.clear()
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        if (::ble.isInitialized) ble.disconnect(this)
        super.onDestroy()
    }

    private fun connect() {
        ble.connect(mac, token = this,
            onConnected = { main.post { refresh() } },
            onDisconnected = { main.post { refresh() } },
            onFailed = { main.post { refresh() } })
        refresh()
    }

    private fun isThisDevice(): Boolean = ble.getConnectedUid()?.equals(mac, ignoreCase = true) == true

    private fun refresh() {
        val device = DeviceStore.get(this, mac)
        name.text = device?.name ?: mac
        macText.text = mac

        val st = connectionStatus(ble, mac)
        status.showStatus(this, st)
        reconnect.isEnabled = st == ConnStatus.NOT_CONNECTED

        // Gearing summary (Ki2: "Front: 52-36 / Rear: 11-34")
        val front = device?.frontTeeth.orEmpty().filter { it > 0 }
        val rear = device?.rearTeeth.orEmpty().filter { it > 0 }
        gearing.text = if (front.isEmpty() && rear.isEmpty()) getString(R.string.text_gearing_not_set)
        else getString(R.string.text_param_gearing_summary,
            if (front.isEmpty()) "-" else front.joinToString("-"),
            if (rear.isEmpty()) "-" else "${rear.first()}-${rear.last()} (${rear.size})")

        val connected = st == ConnStatus.CONNECTED && isThisDevice()
        if (!connected) {
            listOf(rearGear, frontGear, shiftCount, totalGears, battRd, battFd, battLeft, battRight, firmware)
                .forEach { it.setText(R.string.text_na); it.setCompoundDrawables(null, null, null, null) }
            model.text = device?.type?.ifBlank { null } ?: getString(R.string.text_unknown)
            race.isEnabled = false
            sleep.isEnabled = false
            return
        }

        val s = KtopShiftingState.current(this, ble)
        if (s.rearGearMax > 0) {
            drivetrain.setGears(s.frontGearMax, s.frontGear, s.rearGearMax, s.rearGear)
        }
        rearGear.text = if (s.rearGear > 0) String.format(Locale.US, "%02d/%02d", s.rearGear, s.rearGearMax) else getString(R.string.text_na)
        frontGear.text = if (s.frontGear > 0) String.format(Locale.US, "%02d/%02d", s.frontGear, s.frontGearMax) else getString(R.string.text_na)
        shiftCount.text = s.shiftCount.toString()
        totalGears.text = if (s.rearGearMax > 0) s.rearGearMax.toString() else getString(R.string.text_na)

        showPack(battRd, ble.getLastRearBatteryV())
        showPack(battFd, ble.getLastFrontBatteryV())
        showCell(battLeft, ble.getLastLeftBatteryV())
        showCell(battRight, ble.getLastRightBatteryV())

        model.text = ble.connectedName ?: device?.type?.ifBlank { null } ?: getString(R.string.text_unknown)
        val v = ble.deviceInfo.versions
        firmware.text = if (v.isEmpty()) getString(R.string.text_na) else v.entries.joinToString("  ") { (k, value) ->
            val label = when (k) {
                "GEARS_V", "H_Ver" -> "RD"
                "Q_Ver" -> "FD"
                "L_Ver" -> "L"
                "R_Ver" -> "R"
                "REMOTE_V" -> getString(R.string.text_firmware_shifter)
                "ANT_Ver" -> "ANT"
                else -> k
            }
            "$label $value"
        }

        updatingSwitches = true
        race.isEnabled = ble.raceMode != null
        race.isChecked = ble.raceMode == true
        sleep.isEnabled = ble.sleepEnabled != null
        sleep.isChecked = ble.sleepEnabled == true
        updatingSwitches = false
    }

    /** Main pack (RD/FD): "82% · 7.82 V" with Ki2's battery icon. */
    private fun showPack(view: TextView, volts: Double?) {
        if (volts == null || volts <= 0.0) {
            view.setText(R.string.text_na)
            view.setCompoundDrawables(null, null, null, null)
            return
        }
        val pct = (BatteryCurves.mainPackPct(volts) * 100f).roundToInt()
        view.text = String.format(Locale.US, "%d%%  ·  %.2f V", pct, volts)
        setBatteryIcon(view, pct)
    }

    /** Shifter coin cell: "2.50 V (75%)". */
    private fun showCell(view: TextView, volts: Double?) {
        if (volts == null || volts <= 0.0) {
            view.setText(R.string.text_na)
            view.setCompoundDrawables(null, null, null, null)
            return
        }
        val pct = (BatteryCurves.coinCellPct(volts) * 100f).roundToInt()
        view.text = String.format(Locale.US, "%d%%  ·  %.2f V", pct, volts)
        setBatteryIcon(view, pct)
    }

    private fun setBatteryIcon(view: TextView, pct: Int) {
        val icon = when {
            pct >= 90 -> R.drawable.ic_battery_5
            pct >= 70 -> R.drawable.ic_battery_4
            pct >= 50 -> R.drawable.ic_battery_3
            pct >= 30 -> R.drawable.ic_battery_2
            pct >= 10 -> R.drawable.ic_battery_1
            else -> R.drawable.ic_battery_0
        }
        val color = when {
            pct <= WheeltopConfig.batteryLevelCritical(this) -> R.color.hh_red
            pct <= WheeltopConfig.batteryLevelLow(this) -> R.color.hh_yellow_darker
            else -> R.color.hh_success_green_600
        }
        val d = ContextCompat.getDrawable(this, icon)?.mutate() ?: return
        d.setTint(getColor(color))
        d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
        view.setCompoundDrawables(null, null, d, null)
    }

    // Race mode: like the reference app - enabling asks for confirmation
    // (it drains the RD battery faster), disabling doesn't.
    private fun onRaceChanged(wanted: Boolean) {
        showRealSwitchStates() // keep showing the real state until the RD confirmed
        if (wanted) {
            AlertDialog.Builder(this)
                .setTitle(R.string.text_race_mode)
                .setMessage(R.string.text_warning_race_mode)
                .setPositiveButton(R.string.text_ok) { _, _ -> applyRace(true) }
                .setNegativeButton(R.string.text_cancel, null)
                .show()
        } else applyRace(false)
    }

    private fun showRealSwitchStates() {
        updatingSwitches = true
        race.isChecked = ble.raceMode == true
        sleep.isChecked = ble.sleepEnabled == true
        updatingSwitches = false
    }

    private fun applyRace(on: Boolean) {
        race.isEnabled = false
        ble.setRaceMode(on) { ok -> main.post { if (!ok) toastFailed(); refresh() } }
    }

    // Sleep mode: disabling asks for confirmation (drains the battery faster).
    private fun onSleepChanged(wanted: Boolean) {
        showRealSwitchStates()
        if (!wanted) {
            AlertDialog.Builder(this)
                .setTitle(R.string.text_sleep_mode)
                .setMessage(R.string.text_warning_sleep_mode)
                .setPositiveButton(R.string.text_ok) { _, _ -> applySleep(false) }
                .setNegativeButton(R.string.text_cancel, null)
                .show()
        } else applySleep(true)
    }

    private fun applySleep(enabled: Boolean) {
        sleep.isEnabled = false
        ble.setSleepEnabled(enabled) { ok -> main.post { if (!ok) toastFailed(); refresh() } }
    }

    private fun toastFailed() {
        Toast.makeText(this, R.string.text_command_failed, Toast.LENGTH_SHORT).show()
    }

    private fun rename() {
        val device = DeviceStore.get(this, mac) ?: return
        val view = layoutInflater.inflate(R.layout.dialog_device_rename, null)
        val edit = view.findViewById<EditText>(R.id.edittext_dialog_device_name)
        edit.setText(device.name)
        AlertDialog.Builder(this)
            .setTitle(R.string.text_rename)
            .setView(view)
            .setPositiveButton(R.string.text_ok) { _, _ ->
                val n = edit.text.toString().trim()
                if (n.isNotEmpty()) DeviceStore.update(this, device.copy(name = n))
                refresh()
            }
            .setNegativeButton(R.string.text_cancel, null)
            .show()
    }

    private fun remove() {
        AlertDialog.Builder(this)
            .setTitle(R.string.text_remove)
            .setMessage(R.string.text_remove_device_confirm)
            .setPositiveButton(R.string.text_remove) { _, _ ->
                ble.disconnect(this)
                DeviceStore.remove(this, mac)
                finish()
            }
            .setNegativeButton(R.string.text_cancel, null)
            .show()
    }
}
