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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.w1w1.ktop.DeviceStore
import com.w1w1.ktop.R
import com.w1w1.ktop.WheeltopBleClient

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

        front += device.frontTeeth.ifEmpty { List(defaultFront) { 0 } }
        rear += device.rearTeeth.ifEmpty { List(defaultRear) { 0 } }

        frontAdapter = GearAdapter(front)
        rearAdapter = GearAdapter(rear)
        findViewById<RecyclerView>(R.id.recyclerview_device_gearing_front).adapter = frontAdapter
        findViewById<RecyclerView>(R.id.recyclerview_device_gearing_rear).adapter = rearAdapter

        setupCountSpinner(findViewById(R.id.spinner_device_gearing_number_gears_front), 1..2, front, frontAdapter)
        setupCountSpinner(findViewById(R.id.spinner_device_gearing_number_gears_rear), 1..14, rear, rearAdapter)

        findViewById<ExtendedFloatingActionButton>(R.id.button_back).setOnClickListener { finish() }
    }

    override fun onPause() {
        currentFocus?.clearFocus()
        DeviceStore.get(this, mac)?.let {
            DeviceStore.update(this, it.copy(frontTeeth = front.toList(), rearTeeth = rear.toList()))
        }
        super.onPause()
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
