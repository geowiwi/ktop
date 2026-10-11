package com.w1w1.ktop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved Wheeltop groupsets (Ki2's DeviceStore equivalent): several
 * Wheeltop EDS systems (e.g. two bikes, OX/TX/GeX variants) can be
 * added, renamed, reordered and removed in the app. Per device it also
 * keeps Ki2's "custom gearing" (teeth counts), since the EDS protocol
 * doesn't report teeth.
 *
 * Gearing format (Ki2 order): front = chainrings small -> big
 * (front gear 1 = small ring), rear = cassette by ktop gear number,
 * gear 1 first.
 */
data class SavedDevice(
    val mac: String,
    val name: String,
    val type: String,
    val frontTeeth: List<Int> = emptyList(),
    val rearTeeth: List<Int> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mac", mac).put("name", name).put("type", type)
        .put("front", JSONArray(frontTeeth)).put("rear", JSONArray(rearTeeth))

    companion object {
        fun fromJson(o: JSONObject): SavedDevice {
            fun ints(key: String): List<Int> {
                val a = o.optJSONArray(key) ?: return emptyList()
                // 0 = not entered for that gear (kept so indices stay aligned)
                return (0 until a.length()).map { a.optInt(it).coerceAtLeast(0) }
            }
            return SavedDevice(
                mac = o.getString("mac"),
                name = o.optString("name", o.getString("mac")),
                type = o.optString("type", ""),
                frontTeeth = ints("front"),
                rearTeeth = ints("rear"),
            )
        }
    }
}

object DeviceStore {
    private const val PREFS = "wheeltop_devices"
    private const val KEY = "devices"

    /** Fires (on any thread) whenever the stored list changes. */
    val changes = WheeltopBleClient.SimpleBus<Unit>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<SavedDevice> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { SavedDevice.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(ctx: Context, list: List<SavedDevice>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY, a.toString()).apply()
        changes.emit(Unit)
    }

    fun get(ctx: Context, mac: String?): SavedDevice? =
        if (mac == null) null else all(ctx).firstOrNull { it.mac.equals(mac, ignoreCase = true) }

    /** Adds the device if unknown (e.g. paired from Karoo's sensor screen). */
    @Synchronized
    fun ensure(ctx: Context, mac: String, name: String?) {
        val list = all(ctx)
        if (list.any { it.mac.equals(mac, ignoreCase = true) }) return
        val n = name?.takeIf { it.isNotBlank() } ?: "EDS ${mac.takeLast(5)}"
        save(ctx, list + SavedDevice(mac = mac, name = n, type = name ?: ""))
    }

    /**
     * One-time migration: teeth counts entered in the old single-device
     * settings row (wheeltop_config custom_gearing_front/rear) are moved to
     * this device if it has no gearing yet.
     */
    @Synchronized
    fun migrateLegacyGearing(ctx: Context, mac: String) {
        val legacy = ctx.applicationContext.getSharedPreferences("wheeltop_config", Context.MODE_PRIVATE)
        val f = legacy.getString("custom_gearing_front", null)
        val r = legacy.getString("custom_gearing_rear", null)
        if (f == null && r == null) return
        val d = get(ctx, mac) ?: return
        if (d.frontTeeth.isEmpty() && d.rearTeeth.isEmpty()) {
            update(ctx, d.copy(
                frontTeeth = WheeltopConfig.parseTeeth(f ?: ""),
                rearTeeth = WheeltopConfig.parseTeeth(r ?: ""),
            ))
        }
        legacy.edit().remove("custom_gearing_front").remove("custom_gearing_rear")
            .remove("has_front_derailleur").remove("chainring_count").apply()
    }

    @Synchronized
    fun update(ctx: Context, device: SavedDevice) {
        save(ctx, all(ctx).map { if (it.mac.equals(device.mac, ignoreCase = true)) device else it })
    }

    @Synchronized
    fun remove(ctx: Context, mac: String) {
        save(ctx, all(ctx).filterNot { it.mac.equals(mac, ignoreCase = true) })
    }

    @Synchronized
    fun move(ctx: Context, from: Int, to: Int) {
        val list = all(ctx).toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        save(ctx, list)
    }
}
