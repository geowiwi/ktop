package com.w1w1.ktop.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.w1w1.ktop.DeviceStore
import com.w1w1.ktop.R
import com.w1w1.ktop.SavedDevice
import com.w1w1.ktop.WheeltopBleClient

/**
 * Ki2's device list (fragments/devices/list, MIT License): saved Wheeltop
 * groupsets with connection status, drag handle to reorder, "+" to add,
 * tap to open the device details.
 */
class DevicesFragment : Fragment() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ble: WheeltopBleClient
    private var devices: MutableList<SavedDevice> = mutableListOf()
    private var adapter: DevicesAdapter? = null
    private var emptyText: TextView? = null
    private var storeSub: WheeltopBleClient.Subscription? = null

    private val ticker = object : Runnable {
        @SuppressLint("NotifyDataSetChanged")
        override fun run() {
            adapter?.notifyDataSetChanged()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_devices_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ble = WheeltopBleClient.getInstance(requireContext())
        emptyText = view.findViewById(R.id.textview_list_devices_no_devices)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerview_list_devices)

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                devices.add(to, devices.removeAt(from))
                adapter?.notifyItemMoved(from, to)
                DeviceStore.move(requireContext(), from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = false
        })
        touchHelper.attachToRecyclerView(recycler)

        adapter = DevicesAdapter(
            onClick = { d -> startActivity(Intent(requireContext(), DeviceDetailsActivity::class.java).putExtra(DeviceDetailsActivity.EXTRA_MAC, d.mac)) },
            onStartDrag = { vh -> touchHelper.startDrag(vh) },
        )
        recycler.adapter = adapter

        view.findViewById<ExtendedFloatingActionButton>(R.id.button_list_devices_add).setOnClickListener {
            startActivity(Intent(requireContext(), AddDeviceActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        storeSub = DeviceStore.changes.subscribe { main.post { reload() } }
        main.post(ticker)
    }

    override fun onPause() {
        storeSub?.cancel(); storeSub = null
        main.removeCallbacks(ticker)
        super.onPause()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun reload() {
        val ctx = context ?: return
        devices = DeviceStore.all(ctx).toMutableList()
        emptyText?.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        adapter?.notifyDataSetChanged()
    }

    private inner class DevicesAdapter(
        val onClick: (SavedDevice) -> Unit,
        val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    ) : RecyclerView.Adapter<DevicesAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.textview_list_devices_item_name)
            val status: TextView = v.findViewById(R.id.textview_list_devices_item_connection_status)
            val drag: ImageView = v.findViewById(R.id.imageview_list_devices_item_drag)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.view_item_list_devices, parent, false))

        override fun getItemCount(): Int = devices.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val d = devices[position]
            holder.name.text = d.name
            holder.status.showStatus(holder.itemView.context, connectionStatus(ble, d.mac))
            holder.itemView.setOnClickListener { onClick(d) }
            holder.drag.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
                false
            }
        }
    }
}
