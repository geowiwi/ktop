package com.w1w1.ktop.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.w1w1.ktop.Cassettes
import com.w1w1.ktop.DeviceStore
import com.w1w1.ktop.R
import com.w1w1.ktop.WheeltopBleClient
import com.w1w1.ktop.WheeltopConfig

/**
 * Ki2's custom gearing editor (fragments/devices/gearing, MIT License):
 * number of front/rear gears + one teeth field per gear. Saved per device
 * when leaving the screen. Front: small -> big chainring; rear: gear 1 -> N
 * in ktop's gear numbering.
 */
class DeviceGearingActivity : AppCompatActivity() {

    private lateinit var mac: String
    private val front = mutableListOf<Int>()
    private val rear = mutableListOf<Int>()
    private lateinit var frontAdapter: GearAdapter
    private lateinit var rearAdapter: GearAdapter
    private lateinit var rearSpinner: Spinner
    private var reportedRearGears: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_gearing)
        mac = intent.getStringExtra(DeviceDetailsActivity.EXTRA_MAC) ?: run { finish(); return }
        val device = DeviceStore.get(this, mac) ?: run { finish(); return }

        findViewById<TextView>(R.id.textview_device_gearing_title).text =
            "${getString(R.string.text_gearing)} - ${device.name}"

        val ble = WheeltopBleClient.getInstance(applicationContext)
        val connectedHere = ble.getConnectedUid()?.equals(mac, ignoreCase = true) == true
        val defaultFront = if (connectedHere && ble.hasFrontDerailleur()) 2 else 1
        val defaultRear = if (connectedHere) ble.getTotalGears() ?: 12 else 12
        reportedRearGears = if (connectedHere) ble.getTotalGears() else null

        front += device.frontTeeth.ifEmpty { List(defaultFront) { 0 } }
        rear += device.rearTeeth.ifEmpty { List(defaultRear) { 0 } }

        frontAdapter = GearAdapter(front)
        rearAdapter = GearAdapter(rear)
        findViewById<RecyclerView>(R.id.recyclerview_device_gearing_front).adapter = frontAdapter
        findViewById<RecyclerView>(R.id.recyclerview_device_gearing_rear).adapter = rearAdapter

        setupCountSpinner(findViewById(R.id.spinner_device_gearing_number_gears_front), 1..2, front, frontAdapter)
        rearSpinner = findViewById(R.id.spinner_device_gearing_number_gears_rear)
        setupCountSpinner(rearSpinner, 1..14, rear, rearAdapter)

        findViewById<View>(R.id.button_device_gearing_cassette_preset).setOnClickListener { chooseCassette() }
        findViewById<View>(R.id.button_device_gearing_cassette_list).setOnClickListener { enterCassetteList() }

        findViewById<ExtendedFloatingActionButton>(R.id.button_back).setOnClickListener { finish() }
    }

    override fun onPause() {
        currentFocus?.clearFocus()
        DeviceStore.get(this, mac)?.let {
            DeviceStore.update(this, it.copy(frontTeeth = front.toList(), rearTeeth = rear.toList()))
        }
        super.onPause()
    }

    /** Preset list (Cassettes.ALL); with a connected RD only matching cog counts first. */
    private fun chooseCassette() {
        val total = reportedRearGears
        val items = Cassettes.ALL.sortedWith(
            compareBy<Cassettes.Cassette> { if (total != null && it.speeds == total) 0 else 1 }
                .thenBy { it.speeds }
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.text_cassette_preset)
            .setItems(items.map { it.label }.toTypedArray()) { _, which -> applyRear(items[which].teeth) }
            .setNegativeButton(R.string.text_cancel, null)
            .show()
    }

    /** Free input for cassettes without preset (e.g. 14-speed). */
    private fun enterCassetteList() {
        val edit = EditText(this).apply {
            hint = getString(R.string.text_cassette_list_hint)
            setSingleLine()
            val current = rear.filter { it > 0 }
            if (current.isNotEmpty()) setText(current.sorted().joinToString("-"))
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.text_cassette_list)
            .setMessage(R.string.text_cassette_list_message)
            .setView(box)
            .setPositiveButton(R.string.text_ok) { _, _ ->
                val teeth = WheeltopConfig.parseTeeth(edit.text.toString())
                if (teeth.size in 1..14) applyRear(teeth)
                else Toast.makeText(this, R.string.text_cassette_list_invalid, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.text_cancel, null)
            .show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyRear(teeth: List<Int>) {
        rear.clear()
        rear.addAll(Cassettes.rearGearsFor(teeth))
        rearAdapter.notifyDataSetChanged()
        rearSpinner.setSelection((rear.size - 1).coerceIn(0, 13))
        DeviceStore.get(this, mac)?.let { DeviceStore.update(this, it.copy(rearTeeth = rear.toList())) }
        val total = reportedRearGears
        if (total != null && total != rear.size) {
            Toast.makeText(this, getString(R.string.text_cassette_count_mismatch, rear.size, total), Toast.LENGTH_LONG).show()
        }
    }

    private fun setupCountSpinner(spinner: Spinner, range: IntRange, list: MutableList<Int>, adapter: GearAdapter) {
        val values = range.toList()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values.map { it.toString() })
        spinner.setSelection(values.indexOf(list.size.coerceIn(range.first, range.last)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            @SuppressLint("NotifyDataSetChanged")
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val n = values[position]
                if (n == list.size) return
                while (list.size < n) list.add(0)
                while (list.size > n) list.removeAt(list.size - 1)
                adapter.notifyDataSetChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private class GearAdapter(val values: MutableList<Int>) : RecyclerView.Adapter<GearAdapter.VH>() {
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val edit: EditText = v.findViewById(R.id.edittext_gearing_gear)
            val index: TextView = v.findViewById(R.id.textview_gearing_index)
            var watcher: TextWatcher? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.view_item_device_gearing_gear, parent, false))

        override fun getItemCount(): Int = values.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.watcher?.let { holder.edit.removeTextChangedListener(it) }
            holder.index.text = (position + 1).toString()
            val v = values[position]
            holder.edit.setText(if (v > 0) v.toString() else "")
            val w = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val p = holder.bindingAdapterPosition
                    if (p in values.indices) {
                        values[p] = s?.toString()?.trim()?.toIntOrNull()?.takeIf { it in 5..80 } ?: 0
                    }
                }
            }
            holder.edit.addTextChangedListener(w)
            holder.watcher = w
        }
    }
}
