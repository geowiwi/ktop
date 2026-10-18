package com.w1w1.ktop

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.*

class WheeltopExtension : KarooExtension("wheeltop", BuildConfig.VERSION_NAME) {

    private val karooSystem by lazy { KarooSystemService(this) }
    private val btResourceId = "wheeltop"

    private lateinit var ble: WheeltopBleClient
    private fun bleProvider(): WheeltopBleClient = ble

    override val types = listOf(
        RearGearVisualType(extension, ::bleProvider),
        ShiftCountVisualType(extension, ::bleProvider),
        VoltageVisualType(
            extension,
            "rear_batt_v",
            ::bleProvider,
            { it.getLastRearBatteryV() },
            { it.rearBatteryVFlow }),
        VoltageVisualType(
            extension,
            "left_batt_v",
            ::bleProvider,
            { it.getLastLeftBatteryV() },
            { it.leftBatteryVFlow }),
        VoltageVisualType(
            extension,
            "right_batt_v",
            ::bleProvider,
            { it.getLastRightBatteryV() },
            { it.rightBatteryVFlow }),
        VoltageVisualType(
            extension,
            "front_batt_v",
            ::bleProvider,
            { it.getLastFrontBatteryV() },
            { it.frontBatteryVFlow },
            isFrontDerailleurField = true),
        // Ki2 field set (https://github.com/valterc/ki2, MIT License), see
        // extension_info.xml. rear_gear / shift_count above are Ki2's
        // "Rear Gear" / "Shift Count". Ki2's "Shifting Mode" is Di2-only
        // (synchro modes) - Wheeltop EDS has no equivalent, not ported.
        ShiftingBatteryPercentageType(extension, ::bleProvider),
    ) + KtopTextType.ki2Fields(extension, ::bleProvider) + listOf(
        KtopRenderedVisualType(extension, "DATATYPE_VISUAL_GEARS_INDEX", ::bleProvider) { GearsExtensionView(it, showGearSize = false) },
        KtopRenderedVisualType(extension, "DATATYPE_VISUAL_GEARS_SIZE", ::bleProvider) { GearsExtensionView(it, showGearSize = true) },
        KtopRenderedVisualType(extension, "DATATYPE_VISUAL_GEARS_DETAILED", ::bleProvider) { GearsDetailedExtensionView(it) },
        KtopRenderedVisualType(extension, "DATATYPE_VISUAL_DRIVETRAIN_INDEX", ::bleProvider) { DrivetrainExtensionView(it, showGearSize = false) },
        KtopRenderedVisualType(extension, "DATATYPE_VISUAL_DRIVETRAIN_SIZE", ::bleProvider) { DrivetrainExtensionView(it, showGearSize = true) },
        KtopRenderedVisualType(extension, "DATATYPE_VISUAL_BIKE_BATTERY", ::bleProvider) { BikeBatteryExtensionView(it) },
    )

    private lateinit var lastDeviceStore: LastDeviceStore

    // Ride data in the FIT file (developer fields), see KtopFitRecorder.
    private val fitRecorder by lazy { KtopFitRecorder(applicationContext, karooSystem, ::bleProvider) }

    // Ki2-style in-ride helpers: sounds + battery alerts, gear overlay.
    private var rideAlerts: KtopRideAlerts? = null
    private var overlay: KtopOverlay? = null

    private fun startRideHelpers() {
        if (rideAlerts != null) return
        rideAlerts = KtopRideAlerts(applicationContext, karooSystem, ble).also { it.start() }
        overlay = KtopOverlay(applicationContext, karooSystem, ble).also { it.start() }
    }

    override fun startFit(emitter: Emitter<FitEffect>) {
        Log.i("ktop-ble", "startFit()")
        fitRecorder.start(emitter)
    }

    override fun onCreate() {
        super.onCreate()
        ble = WheeltopBleClient.getInstance(applicationContext)
        // BUG FIX: this was never initialized, so the very first call to
        // connectDevice() -> lastDeviceStore.setLastUid() crashed the
        // extension with an UninitializedPropertyAccessException every
        // time the rider tried to pair/connect the EDS groupset from the
        // Karoo sensor screen. That looked like "Bluetooth doesn't work".
        lastDeviceStore = LastDeviceStore(applicationContext)
        karooSystem.connect { connected ->
            karooSystem.dispatch(RequestBluetooth(resourceId = btResourceId))
            if (connected) mainHandler.post {
                try {
                    startRideHelpers()
                } catch (t: Throwable) {
                    Log.e("ktop-ble", "ride helpers failed to start", t)
                }
            }
        }
        // KEIN auto-connect hier, sonst race mit connectDevice()
    }

    override fun onDestroy() {
        overlay?.stop()
        rideAlerts?.stop()
        overlay = null
        rideAlerts = null
        karooSystem.dispatch(ReleaseBluetooth(resourceId = btResourceId))
        karooSystem.disconnect()
        ble.close()
        super.onDestroy()
    }

    override fun startScan(emitter: Emitter<Device>) {
        Log.i("ktop-ble", "startScan()")
        ble.scanForGeX { dev, name ->
            emitter.onNext(
                Device(
                    extension, dev.address,
                    // Native shifting source (like Ki2): Karoo's own gear
                    // fields and FIT gear-change events, see KtopNativeShifting.
                    listOf(
                        DataType.Source.SHIFTING_FRONT_GEAR,
                        DataType.Source.SHIFTING_REAR_GEAR,
                        DataType.Source.SHIFTING_BATTERY,
                    ) + types.map { DataType.dataTypeId(extension, it.typeId) },
                    name
                )
            )
        }
        emitter.setCancellable { ble.stopScan() }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private companion object {
        const val RECONNECT_MIN_MS = 10_000L
        const val RECONNECT_MAX_MS = 60_000L
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        Log.i("ktop-ble", "connectDevice(uid=$uid)")
        Log.i("ktop-ble", "connectDevice: scheduling connect on main")

        lastDeviceStore.setLastUid(uid)
        // Devices paired from Karoo's Sensors screen also appear in the
        // app's device list (Ki2 behaviour: one list for both).
        DeviceStore.ensure(applicationContext, uid, null)
        DeviceStore.migrateLegacyGearing(applicationContext, uid)
        emitter.onNext(OnConnectionStatus(statusConnecting()))
        val nativeShifting = KtopNativeShifting(applicationContext, uid, ble, emitter)

        // Karoo can call connectDevice() for this same device from more
        // than one context at once - e.g. the Sensors/pairing screen and
        // an active profile/activity each get their own Emitter here.
        // ble.connect() shares a single BLE link across every caller
        // (keyed by `emitter`) instead of tearing it down whenever just
        // one of them disconnects - see WheeltopBleClient.connect() for
        // why that mattered: it used to kill the connection for every
        // other caller, which is why a fresh Karoo boot needed a manual
        // connect from the extension plus a force-stop before an
        // activity would show data.
        //
        // WheeltopExtension is a headless Service and can never call
        // requestPermissions() itself (Activity-only API) - if
        // BLUETOOTH_SCAN/BLUETOOTH_CONNECT is missing, ble.connect()
        // below fails cleanly via onFailed() rather than crashing.
        // Runtime permissions are durable once granted (they survive app
        // and device restarts), so the normal, expected one-time setup
        // step is simply opening KtopPreviewActivity once after install -
        // that's the only Activity in this app and the only place that
        // can show the permission prompt. No need to chase this from the
        // service on every connect.
        // Reconnect loop: Karoo calls connectDevice() once and keeps the
        // emitter open. If the first attempt fails (RD asleep, Bluetooth
        // permission not granted yet) or the link drops during the ride,
        // nothing else would bring it back (seen 2026-10-17/18: a whole ride
        // without connection after a failed first attempt). Retry with
        // backoff until Karoo cancels the emitter.
        var cancelled = false
        var retryDelayMs = RECONNECT_MIN_MS
        lateinit var doConnect: () -> Unit
        val retry = Runnable { if (!cancelled) doConnect() }

        fun scheduleReconnect(reason: String) {
            if (cancelled) return
            mainHandler.removeCallbacks(retry)
            Log.i("ktop-ble", "connectDevice: $reason - reconnect in ${retryDelayMs / 1000}s")
            mainHandler.postDelayed(retry, retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        }

        doConnect = {
            try {
                Log.i("ktop-ble", "connectDevice: calling ble.connect($uid)")
                karooSystem.dispatch(RequestBluetooth(resourceId = btResourceId))

                ble.connect(
                    uid,
                    token = emitter,
                    onConnected = {
                        retryDelayMs = RECONNECT_MIN_MS
                        emitter.onNext(OnConnectionStatus(statusConnected()))
                        nativeShifting.start()
                    },
                    onDisconnected = {
                        nativeShifting.stop()
                        emitter.onNext(OnConnectionStatus(statusNotConnected()))
                        scheduleReconnect("disconnected")
                    },
                    onFailed = { reason ->
                        Log.w("ktop-ble", "connectDevice: ble.connect failed: $reason")
                        nativeShifting.stop()
                        emitter.onNext(OnConnectionStatus(statusNotConnected()))
                        scheduleReconnect("failed ($reason)")
                    }
                )
            } catch (t: Throwable) {
                Log.e("ktop-ble", "connectDevice: ERROR in main post", t)
                emitter.onNext(OnConnectionStatus(statusNotConnected()))
                scheduleReconnect("error")
            }
        }

        mainHandler.post { doConnect() }

        emitter.setCancellable {
            Log.i("ktop-ble", "connectDevice cancelled (uid=$uid) -> ble.disconnect(this caller)")
            cancelled = true
            mainHandler.removeCallbacks(retry)
            nativeShifting.stop()
            ble.disconnect(emitter)
            emitter.onNext(OnConnectionStatus(statusNotConnected()))
        }
    }
    private fun pickStatus(predicate: (ConnectionStatus) -> Boolean): ConnectionStatus =
        ConnectionStatus.values().firstOrNull(predicate) ?: ConnectionStatus.values().first()

    private fun statusConnected(): ConnectionStatus =
        pickStatus { it.name.contains("CONNECT", true) && it.name.contains("ED", true) }

    private fun statusConnecting(): ConnectionStatus = pickStatus {
        (it.name.contains("CONNECT", true) && !it.name.contains(
            "ED", true
        )) || it.name.contains("NOT", true)
    }

    private fun statusNotConnected(): ConnectionStatus = statusConnecting()
}
