package com.w1w1.ktop

import android.content.Context

class LastDeviceStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("wheeltop_prefs", Context.MODE_PRIVATE)

    fun setLastUid(uid: String) {
        prefs.edit().putString("last_uid", uid).apply()
    }

    fun getLastUid(): String? {
        return prefs.getString("last_uid", null)
    }
}