package com.w1w1.ktop.ui

import android.content.Context
import android.widget.TextView
import com.w1w1.ktop.R
import com.w1w1.ktop.WheeltopBleClient

enum class ConnStatus { CONNECTED, CONNECTING, NOT_CONNECTED }

fun connectionStatus(ble: WheeltopBleClient, mac: String): ConnStatus =
    if (ble.getConnectedUid()?.equals(mac, ignoreCase = true) == true) {
        if (ble.isReady()) ConnStatus.CONNECTED else ConnStatus.CONNECTING
    } else ConnStatus.NOT_CONNECTED

/** Ki2's connection status text + color (green when connected). */
fun TextView.showStatus(context: Context, status: ConnStatus) {
    when (status) {
        ConnStatus.CONNECTED -> {
            setText(R.string.text_connected)
            setTextColor(context.getColor(R.color.hh_success_green_600))
        }
        ConnStatus.CONNECTING -> {
            setText(R.string.text_connecting)
            setTextColor(context.getColor(R.color.hh_faded_gray))
        }
        ConnStatus.NOT_CONNECTED -> {
            setText(R.string.text_not_connected)
            setTextColor(context.getColor(R.color.hh_faded_gray))
        }
    }
}
