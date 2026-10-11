package com.w1w1.ktop.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.w1w1.ktop.DeviceStore
import com.w1w1.ktop.R
import com.w1w1.ktop.WheeltopBleClient

/**
 * Ki2's "Add device" screen (fragments/devices/add, MIT License): scans
 * for Wheeltop EDS groupsets (any "EDS ..." advertiser: OX, OX2, TX, GeX),
 * lists the ones not saved yet, "Add" saves one. Wake the groupset first
 * (shift once / move the bike) - the RD only advertises while awake.
 */
class AddDeviceActivity : AppCompatActivity() {

    companion object {
        private const val SCAN_DURATION_MS = 30_000L
    }

    private data class Found(val mac: String, val name: String)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ble: WheeltopBleClient
    private val results = mutableListOf<Found>()
    private lateinit var adapter: ResultsAdapter
    private lateinit var progress: CircularProgressIndicator
    private lateinit var topMessage: TextView
    private lateinit var noDevices: TextView
    private lateinit var scanButton: ExtendedFloatingActionButton
    private val stopRunnable = Runnable { stopScanning() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_add)
        ble = WheeltopBleClient.getInstance(applicationContext)

        progress = findViewById(R.id.progressindicator_add_devices_scanning)
        topMessage = findViewById(R.id.textview_add_device_top_message)
        noDevices = findViewById(R.id.textview_add_device_no_devices_found)
        scanButton = findViewById(R.id.button_add_devices_scan)
        adapter = ResultsAdapter()
        findViewById<RecyclerView>(R.id.recyclerview_add_devices_scan_results).adapter = adapter

        scanButton.setOnClickListener { startScanning() }
        findViewById<ExtendedFloatingActionButton>(R.id.button_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        startScanning()
    }

    override fun onPause() {
        stopScanning()
        super.onPause()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun startScanning() {
        results.clear()
        adapter.notifyDataSetChanged()
        noDevices.visibility = View.GONE
        progress.visibility = View.VISIBLE
        topMessage.setText(R.string.text_searching_for_devices)
        scanButton.isEnabled = false

        ble.scanForGeX { dev, name ->
            val mac = dev.address
            main.post {
                if (DeviceStore.get(this, mac) == null && results.none { it.mac.equals(mac, true) }) {
                    results.add(Found(mac, name))
                    adapter.notifyItemInserted(results.size - 1)
                }
            }
        }
        main.removeCallbacks(stopRunnable)
        main.postDelayed(stopRunnable, SCAN_DURATION_MS)
    }

    private fun stopScanning() {
        main.removeCallbacks(stopRunnable)
        ble.stopScan()
        progress.visibility = View.GONE
        topMessage.setText(R.string.text_search_for_devices)
        scanButton.isEnabled = true
        noDevices.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.textview_add_device_item_name)
            val add: Button = v.findViewById(R.id.button_add_device_item_add)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.view_item_add_device, parent, false))

        override fun getItemCount(): Int = results.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = results[position]
            holder.name.text = "${f.name}  (${f.mac.takeLast(5)})"
            holder.add.setOnClickListener {
                DeviceStore.ensure(this@AddDeviceActivity, f.mac, f.name)
                finish()
            }
        }
    }
}
