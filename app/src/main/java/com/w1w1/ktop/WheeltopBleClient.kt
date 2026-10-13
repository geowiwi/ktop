package com.w1w1.ktop

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WheeltopBleClient(private val appCtx: Context) {

    companion object {
        private const val TAG = "ktop-ble"
        private const val RAW_TAG = "ktop-raw"

        private val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_CHAR_TX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_CHAR_RX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val PREFIX = 0xFE
        private const val XOR_BASE = 0x32

        private const val CMD_GET_KEY = 0x11
        private const val CMD_GET_TRANSMISSION_VERSION_INFO = 0x60
        private const val CMD_START_READ = 0xFA
        private const val CMD_READ = 0xFB
        private const val CMD_SERVER_REPORT_GEAR = 0x98
        // From the reference app (eds-ox js/functions.js):
        private const val CMD_BACK_DIAL_SLEEP = 0x63          // payload [0] = sleep on, [1] = sleep off
        private const val CMD_FRONT_STATUS_REPORT = 0x88      // FD push, payload[4] = Q_NUM 1..6
        private const val CMD_SET_PROTECTION_THRESHOLD = 0x93 // payload [1] = race mode, [2] = normal

        private val GETKEY_PW = "yOTmK50z".toByteArray(Charsets.US_ASCII)

        private const val BATT_POLL_MS = 30_000L

        // Timeout for the startRead/read block-dump exchange that follows
        // the handshake (used to learn TOTAL_CNT and the initial gear).
        // If a block notification never arrives, abandon the sequence
        // instead of leaving it stuck forever - a stuck sequence would
        // otherwise misinterpret every later notification (battery
        // readings, gear pushes) as a leftover ASCII block.
        private const val READ_BLOCK_TIMEOUT_MS = 4_000L

        // A connectGatt() right after a scan/bond change frequently fails
        // transiently with GATT_ERROR (status 133) on many Android BLE
        // stacks. Retrying a couple of times with a short delay clears
        // this up almost every time; only give up after that.
        private const val MAX_CONNECT_ATTEMPTS = 3
        private const val CONNECT_RETRY_DELAY_MS = 350L

        // If a device never answers (out of range, already connected to
        // another host, etc.) we'd otherwise sit on "Connecting…" forever.
        private const val CONNECT_TIMEOUT_MS = 15_000L

        // Delay between gatt.disconnect() and gatt.close(). Closing the
        // client immediately after requesting a disconnect is a known
        // cause of the underlying GATT client slot not being freed in
        // time, which makes later connectGatt() calls silently fail
        // after a few reconnect cycles.
        private const val GATT_CLOSE_DELAY_MS = 200L

        // Calling connectGatt(autoConnect=false) directly against a MAC
        // the Android BLE stack hasn't "seen" yet in this boot cycle -
        // i.e. no scan has matched it since the adapter last came up -
        // frequently just never calls back at all (no error, it simply
        // never reaches onConnectionStateChange) until the 15s connect
        // timeout below gives up. A direct connect right after a device
        // reboot is exactly that case: nothing has scanned yet. A brief
        // targeted scan for this one address first reliably "warms" the
        // stack's knowledge of the device before the real connect - this
        // is exactly why connecting always worked when it went through
        // scanForGeX() first (the preview screen's flow) but not when
        // WheeltopExtension.connectDevice() connected directly by UID.
        private const val PRESCAN_TIMEOUT_MS = 5_000L

        // WheeltopExtension (the Karoo data-field service) and
        // KtopPreviewActivity (the standalone launcher preview screen) used
        // to each create their own WheeltopBleClient and independently
        // connectGatt() to the same physical EDS MAC address. The EDS
        // derailleur, like most small BLE peripherals, only accepts one
        // central connection at a time, so whichever of the two connected
        // second would fail - and since both run in the same app process
        // (see AndroidManifest.xml: no android:process split), the only
        // way to clear a stuck first connection was to force-stop the
        // whole process. Routing both callers through one singleton
        // instance means they now share a single real BLE connection via
        // the connect()/disconnect(token) reference counting above,
        // instead of fighting each other for the device's one connection
        // slot.
        @Volatile private var instance: WheeltopBleClient? = null

        fun getInstance(context: Context): WheeltopBleClient =
            instance ?: synchronized(this) {
                instance ?: WheeltopBleClient(context.applicationContext).also { instance = it }
            }
    }

    class Subscription(private val onCancel: () -> Unit) {
        fun cancel() = onCancel()
    }

    class SimpleBus<T> {
        private val handlers = ConcurrentHashMap<Int, (T) -> Unit>()
        private var nextId = 0
        fun subscribe(h: (T) -> Unit): Subscription {
            val id = synchronized(this) { ++nextId }
            handlers[id] = h
            return Subscription { handlers.remove(id) }
        }

        fun emit(v: T) {
            handlers.values.forEach { it(v) }
        }
    }

    val gearFlow = SimpleBus<Int>()

    // Front derailleur (2x systems, e.g. EDS TX). The FD position comes as
    // an unsolicited frontStatusReport (0x88) push, payload[4] = Q_NUM:
    // 1..3 = big chainring, 4..6 = small chainring (eds-ox PROTOCOL-APP.md
    // + js/functions.js). Also present as Q_NUM / Q_TOTAL in the
    // device-info dump. Exposed Ki2-style: front gear 1 = small, 2 = big.
    val frontGearFlow = SimpleBus<Int>()
    @Volatile private var lastFrontGear: Int? = null
    fun getLastFrontGear(): Int? = lastFrontGear

    /** True if this groupset has a front derailleur (Q_TOTAL > 0 or FD battery reported). */
    fun hasFrontDerailleur(): Boolean =
        (frontTotal ?: 0) > 0 || (lastFrontBatteryV ?: 0.0) > 0.0 || lastFrontGear != null
    @Volatile private var frontTotal: Int? = null

    private fun frontGearFromQNum(q: Int): Int? = when (q) {
        in 1..3 -> 2
        in 4..6 -> 1
        else -> null
    }

    // ===== Device info / modes (eds-ox reference app) =====
    /** Everything from the startRead/read ASCII dump we understand, for the device details screen. */
    data class DeviceInfo(
        val versions: Map<String, String> = emptyMap(),
        val keySwitch: String? = null,
        val runCount: Int? = null,
        val runTime: Int? = null,
        val errorCode: String? = null,
        val raw: String = "",
    )
    @Volatile var deviceInfo: DeviceInfo = DeviceInfo()
        private set

    /** Race mode (PTOTECT 1 = race, 2 = normal). null = unknown yet. */
    @Volatile var raceMode: Boolean? = null
        private set

    /** RD sleep mode (cmd60 payload[12] == 0 -> sleep enabled). null = unknown yet. */
    @Volatile var sleepEnabled: Boolean? = null
        private set

    @Volatile var connectedName: String? = null
        private set

    /** Fires whenever deviceInfo / raceMode / sleepEnabled / front gear changes (for the UI). */
    val infoFlow = SimpleBus<Unit>()

    private var pendingRaceResult: ((Boolean) -> Unit)? = null
    private var pendingRaceValue: Boolean? = null
    private var pendingSleepResult: ((Boolean) -> Unit)? = null
    private var pendingSleepValue: Boolean? = null

    /** Race mode on/off (setProtectionThreshold 0x93). Note: drains the RD battery faster. */
    fun setRaceMode(on: Boolean, onResult: (Boolean) -> Unit) {
        if (!isReady()) { onResult(false); return }
        pendingRaceValue = on
        pendingRaceResult = onResult
        val f = if (on) 1 else 2
        Log.i(TAG, "-> setProtectionThreshold $f (race=${on})")
        enqueueWrite(buildFrame(xorByte = 0x32, key = sessionKey, cmd = CMD_SET_PROTECTION_THRESHOLD, payload = byteArrayOf(f.toByte())))
        connectHandler.postDelayed({
            pendingRaceResult?.let { it(false) }
            pendingRaceResult = null
        }, 2000)
    }

    /** RD sleep mode on/off (backDialSleep 0x63). Disabling sleep drains the RD battery faster. */
    fun setSleepEnabled(enabled: Boolean, onResult: (Boolean) -> Unit) {
        if (!isReady()) { onResult(false); return }
        pendingSleepValue = enabled
        pendingSleepResult = onResult
        val f = if (enabled) 0 else 1
        Log.i(TAG, "-> backDialSleep $f (sleep=${enabled})")
        enqueueWrite(buildFrame(xorByte = 0x32, key = sessionKey, cmd = CMD_BACK_DIAL_SLEEP, payload = byteArrayOf(f.toByte())))
        connectHandler.postDelayed({
            pendingSleepResult?.let { it(false) }
            pendingSleepResult = null
        }, 2000)
    }

    // Shift counters (since connect)
    val shiftCountFlow = SimpleBus<Int>()
    val shiftUpCountFlow = SimpleBus<Int>()
    val shiftDownCountFlow = SimpleBus<Int>()

    @Volatile private var shiftCount: Int = 0
    @Volatile private var shiftUpCount: Int = 0
    @Volatile private var shiftDownCount: Int = 0

    fun getShiftCount(): Int = shiftCount
    @Volatile private var frontShiftCount: Int = 0
    /** Front (FD) shifts since connect, counted from frontStatusReport changes. */
    fun getFrontShiftCount(): Int = frontShiftCount
    fun getShiftUpCount(): Int = shiftUpCount
    fun getShiftDownCount(): Int = shiftDownCount

    val rearBatteryVFlow = SimpleBus<Double>()
    val leftBatteryVFlow = SimpleBus<Double>()
    val rightBatteryVFlow = SimpleBus<Double>()
    val frontBatteryVFlow = SimpleBus<Double>()

    @Volatile private var lastGear: Int? = null
    fun getLastGear(): Int? = lastGear

    // Total number of gears (TOTAL_CNT), learned once per connection from
    // the startRead/read ASCII device-info dump. Needed to turn the raw
    // position the RD reports into the human gear number - see
    // handleReadBlockChunk()/finishReadBlocks() and the serverReportGear
    // handling in handleNotify().
    @Volatile private var totalGears: Int? = null
    fun getTotalGears(): Int? = totalGears

    @Volatile private var lastRearBatteryV: Double? = null
    @Volatile private var lastLeftBatteryV: Double? = null
    @Volatile private var lastRightBatteryV: Double? = null
    @Volatile private var lastFrontBatteryV: Double? = null

    fun getLastRearBatteryV(): Double? = lastRearBatteryV
    fun getLastLeftBatteryV(): Double? = lastLeftBatteryV
    fun getLastRightBatteryV(): Double? = lastRightBatteryV
    fun getLastFrontBatteryV(): Double? = lastFrontBatteryV

    private val adapter: BluetoothAdapter by lazy {
        val mgr = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mgr.adapter
    }

    private val scanner: BluetoothLeScanner?
        get() = adapter.bluetoothLeScanner

    private var scanCallback: ScanCallback? = null

    // Pre-connect "wake the stack" scan state - deliberately separate
    // from scanCallback above (scanForGeX's own scan) so the two can
    // never interfere with each other.
    private var prescanCallback: ScanCallback? = null
    private var prescanTimeoutRunnable: Runnable? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    @Volatile private var sessionKey: Int = 0

    // ===== MINIMAL ADDITIONS FOR "READY" (data flow active) =====
    @Volatile private var ready: Boolean = false
    @Volatile private var connectedMac: String? = null
    @Volatile private var readyCallbackFired: Boolean = false

    // Multiple Karoo contexts can call connect() for the SAME physical
    // device concurrently - the Sensors/pairing screen and each active
    // activity ("profile") that uses this extension's data types each
    // get their own Emitter and their own connectDevice()/cancel calls.
    // Previously this client only remembered a single set of callbacks,
    // so whichever caller cancelled first (e.g. leaving the Sensors
    // screen) tore the BLE connection down via close() for every other
    // caller too - including an activity that had just started using
    // it. That is what made the extension "not work" after a Karoo
    // restart until it was force-stopped: a stale cancel from an
    // earlier context kept killing the activity's fresh connection out
    // from under it. Listeners are now reference-counted per caller
    // (keyed by the Emitter each caller passes as `token`); the BLE
    // connection is only actually torn down once every caller has
    // disconnected.
    private data class Listener(
        val onConnected: () -> Unit,
        val onDisconnected: () -> Unit,
        val onFailed: (String) -> Unit
    )
    private val listeners = ConcurrentHashMap<Any, Listener>()

    private fun notifyAllConnected() {
        listeners.values.forEach { runCatching { it.onConnected() } }
    }
    private fun notifyAllDisconnected() {
        listeners.values.forEach { runCatching { it.onDisconnected() } }
    }
    private fun notifyAllFailed(reason: String) {
        listeners.values.forEach { runCatching { it.onFailed(reason) } }
    }

    /** TRUE = protocol handshake finished (sessionKey acquired) */
    fun isReady(): Boolean = ready && sessionKey != 0 && gatt != null
    fun getConnectedUid(): String? = connectedMac
    // ===========================================================

    // Connection retry/timeout bookkeeping. All accessed on the connect
    // handler's thread (main looper), so no extra locking needed.
    private var pendingDevice: BluetoothDevice? = null
    private var connectAttempt = 0
    private val connectHandler = Handler(Looper.getMainLooper())
    private var connectTimeoutRunnable: Runnable? = null

    private data class WriteReq(val bytes: ByteArray)

    private val writeQueue: ArrayDeque<WriteReq> = ArrayDeque()
    @Volatile private var writeInFlight = false
    private val writeHandler = Handler(Looper.getMainLooper())

    @Volatile private var battPolling = false
    private val battHandler = Handler(Looper.getMainLooper())

    // startRead/read block-dump state. Only touched on the main looper
    // (BLE callbacks arrive there via connectHandler, and these are only
    // driven from those callbacks), so no extra locking needed.
    private var readBlocksTotal = 0
    private var readCurrentBlock = 0
    private val readBuffer = StringBuilder()
    private var readTimeoutRunnable: Runnable? = null

    private val totalCntRegex = Regex("""TOTAL_CNT:(\d+)""")
    private val numRegex = Regex("""(?<![A-Za-z_])NUM:(\d+)""")
    private val versionRegex = Regex("""(REMOTE_V|GEARS_V|L_Ver|R_Ver|Q_Ver|H_Ver|ANT_Ver):([0-9]+(?:\.[0-9]+)?)""")
    private val protectRegex = Regex("""(?<![A-Za-z_])PTOTECT:(\d+)""")
    private val keySwitchRegex = Regex("""KeySwitch:(\d+)""")
    private val qTotalRegex = Regex("""Q_TOTAL:(\d+)""")
    private val qNumRegex = Regex("""Q_NUM:(\d+)""")
    private val runCntRegex = Regex("""HRunCnt:(\d+)""")
    private val runTimeRegex = Regex("""RunTime:(\d+)""")
    private val errCodeRegex = Regex("""(?<![A-Za-z_])ERRCODE:(0x[0-9a-fA-F]+)""")

    private fun parseDeviceInfo(text: String) {
        val versions = linkedMapOf<String, String>()
        versionRegex.findAll(text).forEach { m -> versions[m.groupValues[1]] = m.groupValues[2] }
        deviceInfo = DeviceInfo(
            versions = versions,
            keySwitch = keySwitchRegex.find(text)?.groupValues?.get(1),
            runCount = runCntRegex.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            runTime = runTimeRegex.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            errorCode = errCodeRegex.find(text)?.groupValues?.get(1),
            raw = text,
        )
        protectRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { raceMode = (it == 1) }
        qTotalRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { frontTotal = it }
        val qNum = qNumRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (qNum != null && (frontTotal ?: 0) > 0) {
            frontGearFromQNum(qNum)?.let { lastFrontGear = it; frontGearFlow.emit(it) }
        }
        Log.i(TAG, "Device info: versions=$versions race=$raceMode qTotal=$frontTotal qNum=$qNum " +
            "keySwitch=${deviceInfo.keySwitch} runCnt=${deviceInfo.runCount} runTime=${deviceInfo.runTime}")
        infoFlow.emit(Unit)
    }

    private val battPollRunnable = object : Runnable {
        override fun run() {
            if (!battPolling) return
            if (sessionKey != 0) sendGetTransmissionVersionInfo()
            battHandler.postDelayed(this, BATT_POLL_MS)
        }
    }

    // ===== Runtime permission helpers =====
    // Android 12+ (API 31) requires BLUETOOTH_SCAN / BLUETOOTH_CONNECT
    // at runtime even though they're declared in the manifest. Calling
    // the BLE APIs without them throws SecurityException and crashes
    // the extension; we check first and fail cleanly instead.
    private fun hasPermission(perm: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appCtx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean = hasPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun hasConnectPermission(): Boolean = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    fun scanForGeX(onFound: (BluetoothDevice, String) -> Unit) {
        stopScan()

        if (!hasScanPermission()) {
            Log.w(TAG, "scanForGeX: missing BLUETOOTH_SCAN permission")
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "scanForGeX: Bluetooth is disabled")
            return
        }

        val s = scanner ?: return
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val name = result.scanRecord?.deviceName
                    ?: runCatching { dev.name }.getOrNull()
                    ?: ""
                if (name.contains("EDS", ignoreCase = true)) {
                    onFound(dev, name.ifBlank { "EDS" })
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed, errorCode=$errorCode")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            s.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "scanForGeX: SecurityException starting scan: ${e.message}")
            scanCallback = null
        }
    }

    fun stopScan() {
        val s = scanner ?: return
        scanCallback?.let { runCatching { s.stopScan(it) } }
        scanCallback = null
    }

    fun connect(
        mac: String,
        token: Any,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        Log.i(TAG, "connect() called with $mac token=$token")

        if (!hasConnectPermission()) {
            onFailed("missing BLUETOOTH_CONNECT permission")
            return
        }
        if (!adapter.isEnabled) {
            onFailed("bluetooth disabled")
            return
        }

        // Already connected (or in the middle of connecting) to this same
        // device for another caller - attach as an additional listener
        // instead of tearing the connection down and reconnecting from
        // scratch out from under whoever is already using it.
        if (connectedMac?.equals(mac, ignoreCase = true) == true &&
            (pendingDevice != null || gatt != null)
        ) {
            listeners[token] = Listener(onConnected, onDisconnected, onFailed)
            if (isReady()) onConnected()
            return
        }

        val dev = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: run {
            onFailed("invalid mac")
            return
        }

        close()

        // ===== MINIMAL: store identity + callbacks, reset READY =====
        connectedMac = mac
        connectedName = runCatching { dev.name }.getOrNull()
        ready = false
        readyCallbackFired = false
        listeners[token] = Listener(onConnected, onDisconnected, onFailed)
        // ==========================================================

        pendingDevice = dev
        connectAttempt = 0
        prescanThenConnect(dev)
    }

    /**
     * Detaches one caller from the shared connection. The underlying BLE
     * link is only actually closed once every caller has disconnected, so
     * one context ending (e.g. the Sensors screen closing) no longer kills
     * the connection for another context still using it (e.g. an active
     * profile/activity).
     */
    fun disconnect(token: Any) {
        listeners.remove(token)
        if (listeners.isEmpty()) {
            close()
        }
    }

    /**
     * Briefly scans for this specific device address before the real
     * connect, so the BLE stack has fresh knowledge of it - see
     * PRESCAN_TIMEOUT_MS above for why. Falls back to connecting directly
     * (unchanged old behavior) if scanning isn't possible right now, the
     * scan API itself fails, or nothing matches within the timeout - this
     * is a reliability improvement, never a stricter gate.
     */
    private fun prescanThenConnect(dev: BluetoothDevice) {
        if (!hasScanPermission() || !adapter.isEnabled) {
            startConnectAttempt(dev)
            return
        }
        val s = scanner
        if (s == null) {
            startConnectAttempt(dev)
            return
        }

        var resolved = false
        fun proceed() {
            if (resolved) return
            resolved = true
            stopPrescan()
            startConnectAttempt(dev)
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device?.address?.equals(dev.address, ignoreCase = true) == true) {
                    proceed()
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "pre-connect scan failed ($errorCode) - connecting directly anyway")
                proceed()
            }
        }
        prescanCallback = cb

        val timeout = Runnable {
            Log.w(TAG, "pre-connect scan didn't see ${dev.address} within ${PRESCAN_TIMEOUT_MS}ms - connecting directly anyway")
            proceed()
        }
        prescanTimeoutRunnable = timeout
        connectHandler.postDelayed(timeout, PRESCAN_TIMEOUT_MS)

        val filter = ScanFilter.Builder().setDeviceAddress(dev.address).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            s.startScan(listOf(filter), settings, cb)
        } catch (e: SecurityException) {
            Log.w(TAG, "prescan: SecurityException starting scan: ${e.message}")
            proceed()
        } catch (e: IllegalArgumentException) {
            // Some stacks reject setDeviceAddress() filters in odd states;
            // don't let a scan-filter quirk block connecting outright.
            Log.w(TAG, "prescan: could not start filtered scan: ${e.message}")
            proceed()
        }
    }

    private fun stopPrescan() {
        prescanTimeoutRunnable?.let { connectHandler.removeCallbacks(it) }
        prescanTimeoutRunnable = null
        prescanCallback?.let { cb -> runCatching { scanner?.stopScan(cb) } }
        prescanCallback = null
    }

    private fun startConnectAttempt(dev: BluetoothDevice) {
        connectAttempt += 1
        Log.i(TAG, "Connecting to ${dev.address} (attempt $connectAttempt/$MAX_CONNECT_ATTEMPTS)")

        armConnectTimeout()

        gatt = try {
            // Passing the main-thread Handler keeps every GATT callback on
            // the same thread as the rest of this client, avoiding races
            // between BLE callback threads and the write/poll handlers.
            // The (transport, phy, handler) overload only exists from API
            // 26 - below that there's no Handler-taking overload at all,
            // so we fall back to letting the system pick the callback
            // thread (the original behavior) on very old devices.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dev.connectGatt(
                    appCtx,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    connectHandler
                )
            } else {
                dev.connectGatt(
                    appCtx,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "connectGatt: SecurityException: ${e.message}")
            null
        }

        if (gatt == null) {
            clearConnectTimeout()
            notifyAllFailed("connectGatt failed to start")
            close()
        }
    }

    private fun retryOrFail(failedGatt: BluetoothGatt, reason: String) {
        runCatching { failedGatt.close() }
        if (gatt === failedGatt) gatt = null

        val dev = pendingDevice
        if (dev != null && connectAttempt < MAX_CONNECT_ATTEMPTS) {
            connectHandler.postDelayed({ startConnectAttempt(dev) }, CONNECT_RETRY_DELAY_MS)
        } else {
            clearConnectTimeout()
            notifyAllFailed(reason)
            close()
        }
    }

    private fun armConnectTimeout() {
        clearConnectTimeout()
        val r = Runnable {
            if (!ready) {
                Log.w(TAG, "Connect attempt timed out after ${CONNECT_TIMEOUT_MS}ms")
                notifyAllFailed("connect timeout")
                close()
            }
        }
        connectTimeoutRunnable = r
        connectHandler.postDelayed(r, CONNECT_TIMEOUT_MS)
    }

    private fun clearConnectTimeout() {
        connectTimeoutRunnable?.let { connectHandler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // e.g. GATT_ERROR (133) right after scanning - retry a
                // few times before surfacing a failure.
                retryOrFail(g, "GATT status=$status")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // IMPORTANT: do NOT call onConnected() here (not READY yet)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clearConnectTimeout()
                ready = false
                runCatching { g.close() }
                if (gatt === g) gatt = null
                notifyAllDisconnected()
                close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(NUS_SERVICE) ?: run {
                notifyAllFailed("NUS service not found")
                close()
                return
            }

            val cTx = svc.getCharacteristic(NUS_CHAR_TX)
            val cRx = svc.getCharacteristic(NUS_CHAR_RX)
            val candidates = listOfNotNull(cTx, cRx)

            writeChar = candidates.firstOrNull { it.isWritable() }
            notifyChar = candidates.firstOrNull { it.isNotifiable() }

            if (writeChar == null || notifyChar == null) {
                notifyAllFailed("write/notify chars not found")
                close()
                return
            }

            val n = notifyChar!!
            g.setCharacteristicNotification(n, true)
            val cccd = n.getDescriptor(CCCD) ?: run {
                notifyAllFailed("CCCD missing")
                close()
                return
            }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyAllFailed("CCCD write failed status=$status")
                close()
                return
            }
            sendGetKey()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { onNotifyRaw(it) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onNotifyRaw(value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            onWriteComplete()
        }
    }

    fun close() {
        stopBatteryPolling()
        stopScan()
        stopPrescan()
        clearConnectTimeout()
        abandonReadBlocks()

        val g = gatt
        gatt = null
        writeChar = null
        notifyChar = null
        sessionKey = 0

        // ===== MINIMAL: reset READY identity =====
        ready = false
        connectedMac = null
        readyCallbackFired = false
        // Drop every caller's listener too - once the BLE link itself is
        // torn down there is nothing left for them to be notified about,
        // and the next connect() call starts every caller fresh anyway.
        listeners.clear()
        // ===========================================================

        pendingDevice = null
        connectAttempt = 0
        lastGear = null
        totalGears = null
        lastFrontGear = null
        frontTotal = null
        frontShiftCount = 0
        deviceInfo = DeviceInfo()
        raceMode = null
        sleepEnabled = null
        connectedName = null
        pendingRaceResult = null
        pendingSleepResult = null

        writeInFlight = false
        synchronized(writeQueue) { writeQueue.clear() }

        shiftCount = 0
        shiftUpCount = 0
        shiftDownCount = 0
        shiftCountFlow.emit(0)
        shiftUpCountFlow.emit(0)
        shiftDownCountFlow.emit(0)

        if (g != null) {
            runCatching { g.disconnect() }
            // Don't release the GATT client synchronously - give the
            // Android BT stack a moment to process the disconnect first.
            // Closing immediately is a known cause of subsequent
            // connectGatt() calls silently failing after a few
            // reconnect cycles (the client "slot" isn't freed in time).
            connectHandler.postDelayed({ runCatching { g.close() } }, GATT_CLOSE_DELAY_MS)
        }
    }

    private fun armReadTimeout() {
        clearReadTimeout()
        val r = Runnable {
            Log.w(TAG, "Timed out waiting for read block $readCurrentBlock/$readBlocksTotal - abandoning device-info read")
            abandonReadBlocks()
        }
        readTimeoutRunnable = r
        connectHandler.postDelayed(r, READ_BLOCK_TIMEOUT_MS)
    }

    private fun clearReadTimeout() {
        readTimeoutRunnable?.let { connectHandler.removeCallbacks(it) }
        readTimeoutRunnable = null
    }

    private fun abandonReadBlocks() {
        clearReadTimeout()
        readBlocksTotal = 0
        readCurrentBlock = 0
        readBuffer.setLength(0)
    }

    private fun startBatteryPolling() {
        battPolling = true
        battHandler.removeCallbacks(battPollRunnable)
        // NOT an immediate post(): handleNotify()'s CMD_GET_KEY branch
        // already sends one explicit getTransmissionVersionInfo right
        // after the handshake, immediately followed by sendStartRead().
        // An immediate first poll tick here used to fire a SECOND
        // getTransmissionVersionInfo within milliseconds of that pair,
        // sandwiching startRead between two duplicate requests. The RD's
        // firmware doesn't handle that overlap cleanly - observed on a
        // real device as startRead's response simply never arriving
        // ("Timed out waiting for read block 0/0"), which means
        // TOTAL_CNT never gets learned and the rear gear field can never
        // populate for that connection. The first explicit call already
        // covers the "show a battery reading right away" case, so the
        // polling cycle itself only needs to start after one full
        // interval.
        battHandler.postDelayed(battPollRunnable, BATT_POLL_MS)
    }

    private fun stopBatteryPolling() {
        battPolling = false
        battHandler.removeCallbacks(battPollRunnable)
    }

    private fun enqueueWrite(bytes: ByteArray) {
        synchronized(writeQueue) { writeQueue.addLast(WriteReq(bytes)) }
        pumpWrites()
    }

    private fun pumpWrites() {
        if (writeInFlight) return
        val req =
            synchronized(writeQueue) { if (writeQueue.isEmpty()) null else writeQueue.removeFirst() }
                ?: return

        val g = gatt
        val w = writeChar
        if (g == null || w == null) return

        w.writeType =
            if ((w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        w.value = req.bytes
        val ok = runCatching { g.writeCharacteristic(w) }.getOrDefault(false)
        if (!ok) {
            synchronized(writeQueue) { writeQueue.addFirst(req) }
            writeHandler.postDelayed({ pumpWrites() }, 80)
            return
        }

        writeInFlight = true
        writeHandler.postDelayed({
            if (writeInFlight) {
                writeInFlight = false
                pumpWrites()
            }
        }, 800)
    }

    private fun onWriteComplete() {
        writeInFlight = false
        pumpWrites()
    }

    private fun sendGetKey() {
        val frame = buildFrame(xorByte = 0x32, key = 0x00, cmd = CMD_GET_KEY, payload = GETKEY_PW)
        Log.i(TAG, "-> getKey")
        enqueueWrite(frame)
    }

    // Kicks off the startRead(0xfa)/read(0xfb) exchange that dumps the RD's
    // ASCII device-info block (firmware versions, TOTAL_CNT, current NUM,
    // ...). This is how the official app learns TOTAL_CNT - there's no
    // binary command that returns it directly - so we need it too in
    // order to turn the raw position serverReportGear(0x98) reports into
    // the actual 1..TOTAL_CNT gear number.
    private fun sendStartRead() {
        if (sessionKey == 0) return
        readBlocksTotal = 0
        readCurrentBlock = 0
        readBuffer.setLength(0)
        val frame = buildFrame(
            xorByte = 0x32,
            key = sessionKey,
            cmd = CMD_START_READ,
            payload = byteArrayOf(0x80.toByte())
        )
        Log.i(TAG, "-> startRead")
        armReadTimeout()
        enqueueWrite(frame)
    }

    private fun sendReadBlock(block: Int) {
        if (sessionKey == 0) return
        val frame = buildFrame(
            xorByte = 0x32,
            key = sessionKey,
            cmd = CMD_READ,
            payload = byteArrayOf(0x00.toByte(), block.toByte(), 0x51.toByte())
        )
        armReadTimeout()
        enqueueWrite(frame)
    }

    // Read-block responses are plain ASCII with no XOR/CRC framing at all
    // (see PROTOCOL-APP.md), so they're routed here directly rather than
    // through decodeFrame()/handleNotify(). The first 4 bytes of each
    // chunk are an unidentified prefix (block number, per the docs) and
    // are skipped.
    private fun handleReadBlockChunk(value: ByteArray) {
        clearReadTimeout()

        val start = if (value.size > 4) 4 else 0
        for (i in start until value.size) {
            readBuffer.append((value[i].toInt() and 0xFF).toChar())
        }

        readCurrentBlock++
        if (readCurrentBlock < readBlocksTotal) {
            sendReadBlock(readCurrentBlock)
        } else {
            finishReadBlocks()
        }
    }

    private fun finishReadBlocks() {
        val text = readBuffer.toString()
        readBlocksTotal = 0
        readCurrentBlock = 0
        readBuffer.setLength(0)
        clearReadTimeout()

        parseDeviceInfo(text)

        val total = totalCntRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (total == null || total <= 0) {
            Log.w(TAG, "Could not parse TOTAL_CNT from device-info dump")
            return
        }
        totalGears = total

        val num = numRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (num != null) {
            val g = total - num + 1
            if (g in 1..total) {
                lastGear = g
                Log.i(TAG, "Initial gear from device-info dump: $g/$total")
                gearFlow.emit(g)
            }
        }
    }

    private fun sendGetTransmissionVersionInfo() {
        if (sessionKey == 0) return
        val frame = buildFrame(
            xorByte = 0x32,
            key = sessionKey,
            cmd = CMD_GET_TRANSMISSION_VERSION_INFO,
            payload = byteArrayOf()
        )
        Log.i(TAG, "-> getTransmissionVersionInfo")
        enqueueWrite(frame)
    }

    // Dispatches a raw BLE notification. While we're in the middle of the
    // startRead/read block-dump exchange, the RD sends plain ASCII with no
    // framing at all, so those bytes must bypass decodeFrame() entirely -
    // trying to CRC/XOR-decode them would just fail silently. Everything
    // else is a normal framed packet.
    private fun onNotifyRaw(value: ByteArray) {
        if (readBlocksTotal > 0 && readCurrentBlock < readBlocksTotal) {
            handleReadBlockChunk(value)
        } else {
            handleNotify(value)
        }
    }

    private fun handleNotify(value: ByteArray) {
        val f = decodeFrame(value)
        if (f == null) {
            Log.d(RAW_TAG, "undecoded notify ${value.size}B: ${value.toHex()}")
            return
        }
        // Shifter-button test (2026-10-12): the shifters talk only to the
        // RD, and no button event is documented for apps. Log every frame
        // except the 30 s battery poll so a test session shows whether the
        // RD reports anything usable (e.g. a shift past the last cog or the
        // fine-tune "T" slider). Filter logcat with "ktop-raw".
        if (f.cmd != CMD_GET_TRANSMISSION_VERSION_INFO) {
            Log.d(RAW_TAG, "frame cmd=0x${f.cmd.toString(16)} payload=${f.payload.toHex()}")
        }

        when (f.cmd) {
            CMD_GET_KEY -> {
                if (f.payload.isNotEmpty()) {
                    sessionKey = f.payload[0].toInt() and 0xFF
                    Log.i(TAG, "Session key=0x${sessionKey.toString(16)}")

                    // ===== MINIMAL: READY now, fire onConnected() once =====
                    ready = true
                    clearConnectTimeout()
                    if (!readyCallbackFired) {
                        readyCallbackFired = true
                        notifyAllConnected()
                    }
                    // =====================================================

                    startBatteryPolling()
                    sendGetTransmissionVersionInfo()
                    // Learn TOTAL_CNT (and the initial gear) - needed to
                    // interpret serverReportGear(0x98) pushes below.
                    sendStartRead()
                }
            }

            CMD_START_READ -> {
                clearReadTimeout()
                if (f.payload.size >= 2) {
                    val blocks = f.payload[1].toInt() and 0xFF
                    if (blocks > 0) {
                        readBlocksTotal = blocks
                        readCurrentBlock = 0
                        readBuffer.setLength(0)
                        sendReadBlock(0)
                    } else {
                        Log.w(TAG, "startRead reported 0 blocks")
                    }
                }
            }

            // Unsolicited push from the RD whenever the gear changes -
            // this is how the real app tracks gear, rather than polling.
            // payload[4] = raw position, payload[5] = 1 while the shift is
            // starting, something else once it's finished. We update the
            // live display on both (matches the official app's UI), but
            // only count a completed shift once, on the finish event.
            CMD_SERVER_REPORT_GEAR -> {
                val total = totalGears
                if (total != null && total > 0 && f.payload.size >= 6) {
                    val raw = f.payload[4].toInt() and 0xFF
                    val isStart = (f.payload[5].toInt() and 0xFF) == 1
                    val g = total - raw + 1
                    if (g in 1..total) {
                        Log.i(TAG, "serverReportGear raw=$raw -> gear=$g (${if (isStart) "start" else "finish"})")
                        gearFlow.emit(g)

                        if (!isStart && lastGear != null && lastGear == g) {
                            // Finish report without a gear change: not a
                            // shift (e.g. pressing past the last cog, if the
                            // RD reports that at all). Logged for the
                            // shifter-button test, not counted.
                            Log.i(TAG, "serverReportGear finish without gear change (gear=$g)")
                        } else if (!isStart) {
                            val prev = lastGear
                            lastGear = g

                            shiftCount += 1
                            shiftCountFlow.emit(shiftCount)

                            if (prev != null) {
                                if (g > prev) {
                                    shiftUpCount += 1
                                    shiftUpCountFlow.emit(shiftUpCount)
                                } else if (g < prev) {
                                    shiftDownCount += 1
                                    shiftDownCountFlow.emit(shiftDownCount)
                                }
                            }
                        }
                    }
                }
            }

            CMD_GET_TRANSMISSION_VERSION_INFO -> {
                if (f.payload.size >= 10) {
                    val hPower = u16(f.payload[0], f.payload[1])
                    val rPower = u16(f.payload[2], f.payload[3])
                    val qPower = if (f.payload.size >= 8) u16(f.payload[6], f.payload[7]) else 0
                    val lPower = u16(f.payload[8], f.payload[9])

                    val rdV = hPower / 100.0
                    val lV = lPower / 100.0
                    val rV = rPower / 100.0
                    val fdV = qPower / 100.0

                    Log.i(
                        TAG,
                        "BATT cmd60 RD=${String.format(Locale.US, "%.2f", rdV)}V L=${
                            String.format(Locale.US, "%.2f", lV)
                        }V R=${String.format(Locale.US, "%.2f", rV)}V FD=${
                            String.format(Locale.US, "%.2f", fdV)
                        }V"
                    )

                    if (f.payload.size > 12) {
                        val sleep = (f.payload[12].toInt() and 0xFF) == 0
                        if (sleepEnabled != sleep) { sleepEnabled = sleep; infoFlow.emit(Unit) }
                    }

                    emitVoltage(rdV, ::lastRearBatteryV, rearBatteryVFlow)
                    emitVoltage(lV, ::lastLeftBatteryV, leftBatteryVFlow)
                    emitVoltage(rV, ::lastRightBatteryV, rightBatteryVFlow)
                    if (fdV > 0.0) {
                        emitVoltage(fdV, ::lastFrontBatteryV, frontBatteryVFlow)
                    }
                }
            }

            CMD_FRONT_STATUS_REPORT -> {
                if (f.payload.size >= 5) {
                    val q = f.payload[4].toInt() and 0xFF
                    val fg = frontGearFromQNum(q)
                    Log.i(TAG, "frontStatusReport Q_NUM=$q -> front gear=$fg")
                    if (fg != null) {
                        if (lastFrontGear != fg) {
                            if (lastFrontGear != null) {
                                // a real front shift (not the first report after connect)
                                frontShiftCount += 1
                                shiftCount += 1
                                shiftCountFlow.emit(shiftCount)
                            }
                            lastFrontGear = fg
                        }
                        frontGearFlow.emit(fg)
                        infoFlow.emit(Unit)
                    }
                }
            }

            CMD_SET_PROTECTION_THRESHOLD -> {
                val ok = f.payload.isNotEmpty() && (f.payload[0].toInt() and 0xFF) == 0
                Log.i(TAG, "setProtectionThreshold response ok=$ok")
                if (ok) pendingRaceValue?.let { raceMode = it }
                pendingRaceResult?.invoke(ok)
                pendingRaceResult = null
                infoFlow.emit(Unit)
            }

            CMD_BACK_DIAL_SLEEP -> {
                val ok = f.payload.isNotEmpty() && (f.payload[0].toInt() and 0xFF) == 0
                Log.i(TAG, "backDialSleep response ok=$ok")
                if (ok) pendingSleepValue?.let { sleepEnabled = it }
                pendingSleepResult?.invoke(ok)
                pendingSleepResult = null
                infoFlow.emit(Unit)
            }

            else -> {
                // Diagnostic only: makes any command this client doesn't
                // yet decode visible in logcat (e.g. a possible future
                // front-derailleur gear push) instead of being silently
                // dropped. See frontGearFlow's comment above.
                Log.d(TAG, "unhandled cmd=0x${f.cmd.toString(16)} payload=${f.payload.size}B")
            }
        }
    }

    private fun emitVoltage(
        v: Double,
        slot: kotlin.reflect.KMutableProperty0<Double?>,
        bus: SimpleBus<Double>
    ) {
        if (v <= 0.0 || v > 20.0) return
        val prev = slot.get()
        if (prev == null || kotlin.math.abs(prev - v) > 0.001) {
            slot.set(v)
            bus.emit(v)
        }
    }

    private fun u16(hi: Byte, lo: Byte): Int =
        ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

    private data class Frame(val key: Int, val cmd: Int, val payload: ByteArray)

    private fun xorVal(xorByte: Int): Int = (xorByte - XOR_BASE) and 0xFF

    private fun decodeFrame(raw: ByteArray): Frame? {
        if (raw.isEmpty() || (raw[0].toInt() and 0xFF) != PREFIX) return null
        if (raw.size < 7) return null

        val xb = raw[1].toInt() and 0xFF
        val xv = xorVal(xb)

        val len = (raw[4].toInt() and 0xFF) xor xv
        val need = 5 + len + 2
        if (raw.size < need) return null

        val got = ((raw[5 + len].toInt() and 0xFF) shl 8) or (raw[5 + len + 1].toInt() and 0xFF)
        val calc = crc16(raw, 0, 5 + len)
        if (calc != got) return null

        val key = (raw[2].toInt() and 0xFF) xor xv
        val cmd = (raw[3].toInt() and 0xFF) xor xv

        val payloadEnc = raw.copyOfRange(5, 5 + len)
        val payload = ByteArray(len) { i -> ((payloadEnc[i].toInt() and 0xFF) xor xv).toByte() }

        return Frame(key, cmd, payload)
    }

    private fun buildFrame(xorByte: Int, key: Int, cmd: Int, payload: ByteArray): ByteArray {
        val xb = xorByte and 0xFF
        val xv = xorVal(xb)

        val keyEnc = (key xor xv) and 0xFF
        val cmdEnc = (cmd xor xv) and 0xFF
        val lenEnc = (payload.size xor xv) and 0xFF

        val payloadEnc =
            ByteArray(payload.size) { i -> ((payload[i].toInt() and 0xFF) xor xv).toByte() }

        val header = byteArrayOf(
            PREFIX.toByte(),
            xb.toByte(),
            keyEnc.toByte(),
            cmdEnc.toByte(),
            lenEnc.toByte()
        )
        val noCrc = header + payloadEnc

        val crc = crc16(noCrc, 0, noCrc.size)
        val crcHi = ((crc shr 8) and 0xFF).toByte()
        val crcLo = (crc and 0xFF).toByte()

        return noCrc + byteArrayOf(crcHi, crcLo)
    }

    private fun BluetoothGattCharacteristic.isWritable(): Boolean {
        val p = properties
        return (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }

    private fun BluetoothGattCharacteristic.isNotifiable(): Boolean {
        val p = properties
        return (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
    }

    // CRC16-IBM (Modbus) bitwise implementation (no big tables)
    private fun crc16(buf: ByteArray, offset: Int, len: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (buf[offset + i].toInt() and 0xFF)
            for (b in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
