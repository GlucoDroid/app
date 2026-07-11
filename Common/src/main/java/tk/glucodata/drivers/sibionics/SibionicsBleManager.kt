package tk.glucodata.drivers.sibionics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.UUID
import kotlin.math.abs
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot
import tk.glucodata.drivers.ManagedSensorViewModeStore

@SuppressLint("MissingPermission")
class SibionicsBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SibionicsConstants.SENSOR_GEN),
    ManagedBluetoothSensorDriver,
    ManagedSensorMaintenanceDriver {

    private enum class Phase {
        IDLE,
        CONNECTING,
        DISCOVERING,
        ENABLING_NOTIFY,
        PROBING_CHINESE,
        AUTHENTICATING,
        ACTIVATING,
        SYNCING_TIME,
        REQUESTING_DATA,
        STREAMING,
        ERROR,
    }

    private data class EmittedReading(
        val sampleMs: Long,
        val glucoseMgdl: Float,
        val rawMgdl: Float,
        val temperatureC: Float,
        val impedance: Float,
        val index: Int,
        val live: Boolean,
    )

    companion object {
        private const val RECONNECT_DELAY_MS = 8_000L
        private const val CHINESE_PROBE_TIMEOUT_MS = 5_000L
        private const val CHINESE_DATA_TIMEOUT_MS = 30_000L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val STREAMING_TIMEOUT_MS = 180_000L
        private const val CHINESE_POLL_INTERVAL_MS = 60_000L
        private const val CURRENT_FRESH_MS = 180_000L
        private const val MAX_FUTURE_DRIFT_MS = 10L * 60L * 1000L
        private const val MIN_REASONABLE_TIME_MS = 946_684_800_000L
    }

    @Volatile private var phase = Phase.IDLE
    private val handlerThread = HandlerThread("Sibionics-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    private var service: BluetoothGattService? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    @Volatile private var record: SibionicsRegistry.SensorRecord? = null
    @Volatile private var variant: SibionicsConstants.Variant = SibionicsConstants.Variant.EU
    @Volatile private var protocolMode: SibionicsConstants.ProtocolMode = SibionicsConstants.ProtocolMode.UNKNOWN
    @Volatile private var shortCode: String = variant.fallbackShortCode
    @Volatile private var sensitivity: Float = 1.27f
    @Volatile private var sessionKey: ByteArray? = null
    @Volatile private var keyGroupIndex: Int = 0
    @Volatile private var pendingResetCommand: Boolean = false
    @Volatile private var uiPaused: Boolean = false

    @Volatile private var lastIndex: Int = 0
    @Volatile private var lastIndexDirty: Boolean = false
    @Volatile private var algorithmStateDirty: Boolean = false
    @Volatile private var algorithmRehydrating: Boolean = false
    @Volatile private var rehydrationExpectedIndex: Int = -1
    @Volatile private var rehydrationTargetIndex: Int = 0
    @Volatile private var lastLiveIndexSeen: Int = -1
    @Volatile private var lastLiveAlgorithmIndexSeen: Int = -1
    @Volatile private var startTimeMs: Long = 0L
    @Volatile private var latestReadingTimeMs: Long = 0L
    @Volatile private var latestGlucoseMgdl: Float = Float.NaN
    @Volatile private var latestRawMgdl: Float = Float.NaN
    @Volatile private var latestRateMgdlPerMin: Float = Float.NaN
    @Volatile private var latestTemperatureC: Float = Float.NaN
    @Volatile private var latestImpedance: Float = Float.NaN

    private val algorithm = SibionicsAlgorithmContext(serial)
    private val authTimeoutRunnable = Runnable { tryNextKeyGroup("auth timeout") }

    @Volatile private var viewModeValue: Int = ManagedSensorViewModeStore.read(Applic.app, serial, 0)

    override var viewMode: Int
        get() = viewModeValue
        set(value) {
            val normalized = ManagedSensorViewModeStore.sanitize(value)
            viewModeValue = normalized
            ManagedSensorViewModeStore.write(Applic.app, SerialNumber, normalized)
        }

    fun restoreFromPersistence(context: Context) {
        val restored = SibionicsRegistry.findRecord(context, SerialNumber)
        record = restored
        restored?.let {
            variant = it.variant
            mActiveDeviceAddress = it.address.ifBlank { null }
        }
        variant = SibionicsRegistry.loadVariant(context, SerialNumber)
        protocolMode = SibionicsRegistry.loadProtocolMode(context, SerialNumber)
        if (protocolMode == SibionicsConstants.ProtocolMode.UNKNOWN && variant.prefersChineseProbe) {
            protocolMode = SibionicsConstants.ProtocolMode.CHINESE
        }
        shortCode = SibionicsRegistry.loadShortCode(context, SerialNumber)
        sensitivity = SibionicsSensitivity.sensitivityFor(shortCode)
        algorithm.configure(shortCode, sensitivity, variant)
        lastIndex = SibionicsRegistry.loadLastIndex(context, SerialNumber)
        val algorithmState = SibionicsRegistry.loadAlgorithmState(context, SerialNumber)
        if (algorithmState != null && algorithm.restore(algorithmState)) {
            lastLiveAlgorithmIndexSeen = lastIndex - 1
            Log.i(SibionicsConstants.TAG, "restored exact algorithm state idx=$lastIndex")
        } else if (lastIndex > 1) {
            beginAlgorithmRehydration(lastIndex, "no usable saved state")
        }
        startTimeMs = SibionicsRegistry.loadStartTimeMs(context, SerialNumber)
        val (time, glucose, raw) = SibionicsRegistry.loadLastReading(context, SerialNumber)
        latestReadingTimeMs = time
        latestGlucoseMgdl = glucose
        latestRawMgdl = raw
        sessionKey = SibionicsProtocol.deriveSessionKey(variant)
        constatstatusstr = disconnectedStatus()
    }

    override fun getService(): UUID = SibionicsConstants.SERVICE_FF30

    override fun canConnectWithoutDataptr(): Boolean = true

    override fun hasNativeSensorBacking(): Boolean = false

    override fun shouldUseNativeHistorySync(): Boolean = false

    override fun managesLiveRoomStorage(): Boolean = true

    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    override fun isManagedOutsideNativeActiveSet(): Boolean = true

    override fun supportsDisplayModes(): Boolean = true

    override fun supportsManualCalibration(): Boolean = false

    override fun shouldShowSearchingStatusWhenIdle(): Boolean = true

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        SibionicsConstants.matchesId(SerialNumber, sensorId) ||
            record?.matchesId(sensorId) == true ||
            SibionicsRegistry.findRecord(Applic.app, sensorId)?.matchesId(SerialNumber) == true

    override fun mygetDeviceName(): String =
        record?.displayName?.takeIf { it.isNotBlank() }
            ?: SibionicsRegistry.findRecord(Applic.app, SerialNumber)?.displayName?.takeIf { it.isNotBlank() }
            ?: SibionicsConstants.stripManagedPrefix(SerialNumber)
            ?: SerialNumber

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        uiPaused = false
        if (mActiveBluetoothDevice == null) {
            hydrateBluetoothDeviceFromAddress()
        }
        phase = Phase.CONNECTING
        setStatus(connectingStatus())
        return super.connectDevice(delayMillis)
    }

    @Synchronized
    override fun softDisconnect() {
        uiPaused = true
        stop = true
        handler.removeCallbacksAndMessages(null)
        runCatching { mBluetoothGatt?.disconnect() }
        runCatching { mBluetoothGatt?.close() }
        mBluetoothGatt = null
        mActiveBluetoothDevice = null
        phase = Phase.IDLE
        setStatus(disconnectedStatus())
    }

    @Synchronized
    override fun softReconnect() {
        uiPaused = false
        stop = false
        phase = Phase.IDLE
        connectDevice(0)
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        softDisconnect()
        Applic.app?.let { SibionicsRegistry.removeSensor(it, SerialNumber) }
        SensorBluetooth.updateDevices()
    }

    override fun removeManagedPersistence(context: Context) {
        SibionicsRegistry.removeSensor(context, SerialNumber)
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        runCatching { handlerThread.quitSafely() }
        super.close()
    }

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val scanned = SibionicsConstants.normalizeBleAddress(address)
        val known = knownBleAddress()
        if (known != null && scanned != null && known.equals(scanned, ignoreCase = true)) return true

        val name = SibionicsConstants.normalizeBleName(deviceName)
        if (name.isBlank()) return false
        val rec = record ?: SibionicsRegistry.findRecord(Applic.app, SerialNumber)
        val bleAlias = SibionicsConstants.normalizeBleName(rec?.bleName)
        if (matchesBleNameAlias(name, bleAlias)) return true
        val embeddedBle = SibionicsRegistry.deriveBleName(rec?.displayName)
            ?: SibionicsRegistry.deriveBleName(SibionicsConstants.stripManagedPrefix(SerialNumber))
        if (matchesBleNameAlias(name, embeddedBle)) return true
        val display = SibionicsConstants.normalizeBleName(rec?.displayName)
        val stripped = SibionicsConstants.normalizeBleName(SibionicsConstants.stripManagedPrefix(SerialNumber))
        if (matchesQrShortName(name, display) || matchesQrShortName(name, stripped)) return true
        return display.isNotBlank() && (
            name.equals(display, ignoreCase = true) ||
                display.contains(name, ignoreCase = true) ||
                name.endsWith(display.takeLast(6), ignoreCase = true) ||
                display.endsWith(name.takeLast(6), ignoreCase = true)
            ) ||
            stripped.isNotBlank() && (
                name.equals(stripped, ignoreCase = true) ||
                    stripped.contains(name, ignoreCase = true) ||
                    name.endsWith(stripped.takeLast(6), ignoreCase = true) ||
                    stripped.endsWith(name.takeLast(6), ignoreCase = true)
                )
    }

    private fun matchesBleNameAlias(advertisedName: String, alias: String?): Boolean {
        val normalizedAlias = SibionicsConstants.normalizeBleName(alias)
        if (advertisedName.length < 6 || normalizedAlias.length < 6) return false
        return advertisedName.equals(normalizedAlias, ignoreCase = true) ||
            advertisedName.endsWith(normalizedAlias.takeLast(6), ignoreCase = true) ||
            normalizedAlias.endsWith(advertisedName.takeLast(6), ignoreCase = true)
    }

    private fun matchesQrShortName(advertisedName: String, qrShortName: String?): Boolean {
        val normalizedQr = SibionicsConstants.normalizeBleName(qrShortName)
        if (advertisedName.length < 8 || normalizedQr.length < 8) return false
        // Native QR identity uses the trailing 11-char SI name; some BLE local names embed
        // that SI name's first four chars as their suffix (e.g. QR 46HU804EBJ4 ↔ ...46HU).
        return advertisedName.endsWith(normalizedQr.take(4), ignoreCase = true)
    }

    override fun setDeviceAddress(address: String?) {
        super.setDeviceAddress(address)
        val normalized = SibionicsConstants.normalizeBleAddress(address) ?: return
        val ctx = Applic.app ?: return
        val rec = record ?: SibionicsRegistry.findRecord(ctx, SerialNumber)
        if (rec != null) {
            record = SibionicsRegistry.ensureSensorRecord(
                context = ctx,
                rawInput = rec.displayName,
                address = normalized,
                displayName = rec.displayName,
                variant = variant,
                shortCodeOverride = shortCode,
            )
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (stop) return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                gatt.device?.address?.let { setDeviceAddress(it) }
                connectTime = System.currentTimeMillis()
                phase = Phase.DISCOVERING
                setStatus(connectingStatus())
                runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                handler.postDelayed({ runCatching { gatt.discoverServices() } }, 250L)
                UiRefreshBus.requestStatusRefresh()
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(SibionicsConstants.TAG, "disconnected status=$status serial=$SerialNumber")
                service = null
                notifyChar = null
                writeChar = null
                phase = Phase.IDLE
                runCatching { gatt.close() }
                mBluetoothGatt = null
                mActiveBluetoothDevice = null
                setStatus(disconnectedStatus())
                handler.removeCallbacks(chinesePollRunnable)
                handler.removeCallbacks(streamingTimeoutRunnable)
                handler.removeCallbacks(handshakeTimeoutRunnable)
                handler.removeCallbacks(authTimeoutRunnable)
                handler.removeCallbacks(chineseProbeTimeoutRunnable)
                handler.removeCallbacks(chineseDataTimeoutRunnable)
                if (!stop && !uiPaused) scheduleReconnect("disconnect")
                UiRefreshBus.requestStatusRefresh()
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            setStatus("Discovery failed: $status")
            scheduleReconnect("discover failed")
            return
        }
        service = gatt.getService(SibionicsConstants.SERVICE_FF30)
        val svc = service
        if (svc == null) {
            setStatus("Sibionics FF30 service missing")
            scheduleReconnect("missing service")
            return
        }
        notifyChar = svc.getCharacteristic(SibionicsConstants.CHAR_NOTIFY_FF31)
        writeChar = svc.getCharacteristic(SibionicsConstants.CHAR_WRITE_FF32)
        if (notifyChar == null || writeChar == null) {
            setStatus("Sibionics FF31/FF32 missing")
            scheduleReconnect("missing chars")
            return
        }
        phase = Phase.ENABLING_NOTIFY
        enableNotify(gatt, notifyChar!!)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (descriptor.characteristic?.uuid == SibionicsConstants.CHAR_NOTIFY_FF31) {
            if (pendingResetCommand) {
                pendingResetCommand = false
                writeResetCommand("pending")
                return
            }
            startProtocolProbe()
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(SibionicsConstants.TAG, "write ${characteristic.uuid} failed status=$status")
        }
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (characteristic.uuid == SibionicsConstants.CHAR_NOTIFY_FF31) {
            handleIncoming(characteristic.value ?: ByteArray(0))
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (characteristic.uuid == SibionicsConstants.CHAR_NOTIFY_FF31) {
            handleIncoming(value)
        }
    }

    private fun startProtocolProbe() {
        when (protocolMode) {
            SibionicsConstants.ProtocolMode.CHINESE -> {
                sendChineseDataRequest(probing = false)
                scheduleChineseDataTimeout()
            }
            SibionicsConstants.ProtocolMode.V120 -> sendAuthPacket()
            SibionicsConstants.ProtocolMode.UNKNOWN -> {
                sendChineseDataRequest(probing = true)
                scheduleChineseProbeTimeout()
            }
        }
    }

    private fun handleIncoming(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (bytes.size >= 2 && bytes[0] == 0xAA.toByte() && bytes[1] == 0x55.toByte()) {
            handleChinese(SibionicsProtocol.parseChinese(bytes))
            return
        }
        if (protocolMode == SibionicsConstants.ProtocolMode.UNKNOWN || phase == Phase.PROBING_CHINESE) {
            val chinese = SibionicsProtocol.parseChinese(bytes)
            if (chinese is SibionicsProtocol.ParseResult.V120AuthRequired) {
                switchToV120("auth marker")
                return
            }
        }
        handleV120(SibionicsProtocol.parseV120(bytes))
    }

    private fun handleChinese(result: SibionicsProtocol.ParseResult) {
        when (result) {
            is SibionicsProtocol.ParseResult.ChineseEcho -> {
                if (protocolMode == SibionicsConstants.ProtocolMode.UNKNOWN) {
                    protocolMode = SibionicsConstants.ProtocolMode.CHINESE
                    Applic.app?.let { SibionicsRegistry.saveProtocolMode(it, SerialNumber, protocolMode) }
                }
                handler.removeCallbacks(chineseProbeTimeoutRunnable)
                handler.removeCallbacks(chineseDataTimeoutRunnable)
                if (phase != Phase.STREAMING) {
                    phase = Phase.REQUESTING_DATA
                    setStatus(waitingForDataStatus())
                    scheduleChineseDataTimeout()
                }
            }

            is SibionicsProtocol.ParseResult.ChineseData -> {
                if (protocolMode != SibionicsConstants.ProtocolMode.CHINESE) {
                    protocolMode = SibionicsConstants.ProtocolMode.CHINESE
                    Applic.app?.let { SibionicsRegistry.saveProtocolMode(it, SerialNumber, protocolMode) }
                }
                handler.removeCallbacks(chineseProbeTimeoutRunnable)
                handler.removeCallbacks(chineseDataTimeoutRunnable)
                phase = Phase.STREAMING
                processChineseEntries(result.entries)
                scheduleChinesePoll()
                scheduleStreamingTimeout()
            }

            is SibionicsProtocol.ParseResult.V120AuthRequired -> switchToV120("chinese parser requested V120")
            is SibionicsProtocol.ParseResult.ChecksumError -> {
                Log.w(SibionicsConstants.TAG, "Chinese checksum error ${SibionicsProtocol.toHex(result.plaintext)}")
            }
            is SibionicsProtocol.ParseResult.Unknown -> {
                Log.w(SibionicsConstants.TAG, "Chinese unknown ${SibionicsProtocol.toHex(result.bytes)}")
            }
            else -> Unit
        }
    }

    private fun handleV120(result: SibionicsProtocol.ParseResult) {
        when (result) {
            is SibionicsProtocol.ParseResult.Handshake -> handleV120Handshake(result.response)
            is SibionicsProtocol.ParseResult.V120Data -> {
                handler.removeCallbacks(handshakeTimeoutRunnable)
                phase = Phase.STREAMING
                processV120Entries(result.entries)
                scheduleStreamingTimeout()
            }
            is SibionicsProtocol.ParseResult.V120AuthRequired -> switchToV120("auth marker")
            is SibionicsProtocol.ParseResult.ChecksumError -> {
                Log.w(SibionicsConstants.TAG, "V120 checksum error ${SibionicsProtocol.toHex(result.plaintext)}")
            }
            is SibionicsProtocol.ParseResult.Unknown -> {
                Log.w(SibionicsConstants.TAG, "V120 unknown ${SibionicsProtocol.toHex(result.bytes)}")
            }
            else -> Unit
        }
    }

    private fun handleV120Handshake(response: SibionicsProtocol.ResponseType) {
        handler.removeCallbacks(handshakeTimeoutRunnable)
        handler.removeCallbacks(authTimeoutRunnable)
        protocolMode = SibionicsConstants.ProtocolMode.V120
        Applic.app?.let {
            SibionicsRegistry.saveProtocolMode(it, SerialNumber, protocolMode)
            SibionicsRegistry.saveVariant(it, SerialNumber, variant)
        }
        when (response) {
            SibionicsProtocol.ResponseType.AUTH_ACCEPTED -> {
                algorithm.configure(shortCode, sensitivity, variant)
                setStatus("Authenticated")
                phase = Phase.ACTIVATING
                writeCommand(SibionicsProtocol.buildActivationPacket(), "activation")
                scheduleHandshakeTimeout()
            }
            SibionicsProtocol.ResponseType.TIME_SYNC_NEEDED -> {
                phase = Phase.SYNCING_TIME
                writeCommand(SibionicsProtocol.buildTimeSyncPacket(), "time-sync")
                scheduleHandshakeTimeout()
            }
            SibionicsProtocol.ResponseType.DATA_REQUESTED -> {
                phase = Phase.REQUESTING_DATA
                writeCommand(SibionicsProtocol.buildDataRequestPacket(lastIndex), "data-request")
                scheduleHandshakeTimeout()
            }
            SibionicsProtocol.ResponseType.STREAMING_READY -> {
                phase = Phase.STREAMING
                setStatus(waitingForDataStatus())
                scheduleStreamingTimeout()
            }
        }
    }

    private fun switchToV120(reason: String) {
        handler.removeCallbacks(chineseProbeTimeoutRunnable)
        handler.removeCallbacks(chineseDataTimeoutRunnable)
        handler.removeCallbacks(authTimeoutRunnable)
        protocolMode = SibionicsConstants.ProtocolMode.V120
        Applic.app?.let { SibionicsRegistry.saveProtocolMode(it, SerialNumber, protocolMode) }
        Log.i(SibionicsConstants.TAG, "switching to V120: $reason")
        sendAuthPacket()
    }

    private fun sendAuthPacket() {
        val groups = keyGroups()
        if (groups.isEmpty()) {
            setStatus("No V120 key")
            return
        }
        val selected = groups[keyGroupIndex.coerceIn(0, groups.lastIndex)]
        variant = selected
        sessionKey = SibionicsProtocol.deriveSessionKey(selected)
        val key = sessionKey
        if (key == null) {
            tryNextKeyGroup("key derivation failed")
            return
        }
        phase = Phase.AUTHENTICATING
        setStatus("Authenticating")
        writeCommand(SibionicsProtocol.buildAuthPacket(SibionicsConstants.macBytes(mActiveDeviceAddress), key), "auth")
        scheduleAuthTimeout()
    }

    private fun tryNextKeyGroup(reason: String) {
        val groups = keyGroups()
        keyGroupIndex++
        if (keyGroupIndex >= groups.size) {
            keyGroupIndex = 0
            phase = Phase.ERROR
            setStatus("Auth failed")
            Log.w(SibionicsConstants.TAG, "V120 auth failed: $reason")
            scheduleReconnect("auth failed")
            return
        }
        Log.i(SibionicsConstants.TAG, "V120 auth retry: $reason -> ${groups[keyGroupIndex].id}")
        sendAuthPacket()
    }

    private fun keyGroups(): List<SibionicsConstants.Variant> {
        val preferred = variant
        return listOf(
            preferred,
            SibionicsConstants.Variant.EU,
            SibionicsConstants.Variant.HEMATONIX,
            SibionicsConstants.Variant.SIBIONICS2,
            SibionicsConstants.Variant.GS3,
        ).distinctBy { it.appId + it.registrationKeyHex }
    }

    private fun sendChineseDataRequest(probing: Boolean) {
        protocolMode = if (protocolMode == SibionicsConstants.ProtocolMode.UNKNOWN) {
            SibionicsConstants.ProtocolMode.UNKNOWN
        } else {
            SibionicsConstants.ProtocolMode.CHINESE
        }
        if (probing) {
            phase = Phase.PROBING_CHINESE
            setStatus("Probing")
        } else if (phase != Phase.STREAMING) {
            phase = Phase.REQUESTING_DATA
            setStatus(waitingForDataStatus())
        }
        val packet = SibionicsProtocol.buildChineseDataRequest(lastIndex.coerceAtLeast(1), SibionicsConstants.macBytes(mActiveDeviceAddress))
        writeCommand(packet, "chinese-data-request")
    }

    private fun processChineseEntries(entries: List<SibionicsProtocol.ChineseEntry>) {
        if (entries.isEmpty()) {
            setStatus(waitingForDataStatus())
            return
        }
        val now = System.currentTimeMillis()
        val emitted = entries
            .sortedBy { it.index }
            .mapNotNull { entry ->
                val eventMs = sanitizeSampleTime(entry.eventTimeMs(now))
                processEntry(
                    index = entry.index,
                    rawMmol = entry.rawMmol,
                    rawMgdl = entry.rawMgdl,
                    temperatureC = entry.temperatureC,
                    impedance = entry.rawImpedance.toFloat(),
                    eventMs = eventMs,
                    live = entry.isLive,
                )
            }
        flushAlgorithmCheckpointIfDirty()
        storeAndPublish(emitted)
    }

    private fun processV120Entries(entries: List<SibionicsProtocol.V120Entry>) {
        if (entries.isEmpty()) {
            setStatus(waitingForDataStatus())
            return
        }
        val emitted = entries
            .sortedBy { it.index }
            .mapNotNull { entry ->
                processEntry(
                    index = entry.index,
                    rawMmol = entry.rawMmol,
                    rawMgdl = entry.rawMgdl,
                    temperatureC = entry.temperatureC,
                    impedance = entry.rawImpedance.toFloat(),
                    eventMs = sanitizeSampleTime(entry.eventTimeMs),
                    live = entry.isLive,
                )
            }
        flushAlgorithmCheckpointIfDirty()
        storeAndPublish(emitted)
    }

    private fun processEntry(
        index: Int,
        rawMmol: Float,
        rawMgdl: Float,
        temperatureC: Float,
        impedance: Float,
        eventMs: Long,
        live: Boolean,
    ): EmittedReading? {
        if (!rawMmol.isFinite() || rawMmol <= 0f || eventMs <= 0L) return null
        if (!algorithmRehydrating && index <= 1 && lastIndex > 1) {
            resetForSensorRestart()
        }
        if (!live && lastIndex > 0 && index < lastIndex) return null
        if (live && lastLiveAlgorithmIndexSeen >= 0 && index <= lastLiveAlgorithmIndexSeen) return null
        if (!algorithmRehydrating && lastIndex > 0 && index > lastIndex) {
            beginAlgorithmRehydration(lastIndex, "algorithm index gap: expected $lastIndex, received $index")
            scheduleReconnect("exact algorithm index gap")
            return null
        }
        val wasRehydrating = algorithmRehydrating
        if (wasRehydrating && !acceptRehydrationIndex(index)) return null
        if (startTimeMs <= 0L && index >= 0) {
            startTimeMs = eventMs - index * SibionicsConstants.READING_INTERVAL_MS
            Applic.app?.let { SibionicsRegistry.saveStartTimeMs(it, SerialNumber, startTimeMs) }
        }
        if (index <= 1) algorithm.reset()
        val displayMmol = algorithm.process(
            rawMmol = rawMmol,
            temperatureC = temperatureC,
            index = index,
            mode = if (live) SibionicsAlgorithmMode.LIVE else SibionicsAlgorithmMode.REPLAY,
        )
        if (live) lastLiveAlgorithmIndexSeen = index
        algorithmStateDirty = true
        advanceLastIndex(index)
        if (wasRehydrating && index >= rehydrationTargetIndex - 1) {
            algorithmRehydrating = false
            rehydrationExpectedIndex = -1
            rehydrationTargetIndex = 0
            Log.i(SibionicsConstants.TAG, "exact algorithm rehydrated through idx=$index")
        }
        if (wasRehydrating && !live) return null
        val glucoseMgdl = displayMmol * SibionicsConstants.MGDL_PER_MMOLL
        if (!SibionicsConstants.isValidAlgorithmGlucoseMgdl(glucoseMgdl)) {
            Log.w(SibionicsConstants.TAG, "skip invalid glucose idx=$index display=$glucoseMgdl raw=$rawMgdl")
            return null
        }

        return EmittedReading(eventMs, glucoseMgdl, rawMgdl, temperatureC, impedance, index, live)
    }

    private fun advanceLastIndex(index: Int) {
        if (index >= lastIndex) {
            lastIndex = index + 1
            lastIndexDirty = true
        }
    }

    private fun flushAlgorithmCheckpointIfDirty() {
        if (!lastIndexDirty && !algorithmStateDirty) return
        val indexDirty = lastIndexDirty
        val stateDirty = algorithmStateDirty
        lastIndexDirty = false
        algorithmStateDirty = false
        Applic.app?.let { context ->
            if (stateDirty) {
                SibionicsRegistry.saveAlgorithmCheckpoint(context, SerialNumber, lastIndex, algorithm.snapshot())
            } else if (indexDirty) {
                SibionicsRegistry.saveLastIndex(context, SerialNumber, lastIndex)
            }
        }
    }

    private fun beginAlgorithmRehydration(previousIndex: Int, reason: String) {
        algorithm.reset()
        algorithmStateDirty = false
        algorithmRehydrating = true
        rehydrationExpectedIndex = -1
        rehydrationTargetIndex = previousIndex.coerceAtLeast(1)
        lastLiveAlgorithmIndexSeen = -1
        lastIndex = 1
        lastIndexDirty = true
        Log.w(
            SibionicsConstants.TAG,
            "exact algorithm state unavailable ($reason); requesting contiguous replay from idx=1",
        )
    }

    private fun resetForSensorRestart() {
        Log.i(SibionicsConstants.TAG, "sensor index restarted; clearing exact algorithm state")
        algorithm.reset()
        lastIndex = 0
        lastIndexDirty = true
        algorithmStateDirty = true
        lastLiveAlgorithmIndexSeen = -1
        lastLiveIndexSeen = -1
        startTimeMs = 0L
        latestReadingTimeMs = 0L
        latestGlucoseMgdl = Float.NaN
        latestRawMgdl = Float.NaN
        Applic.app?.let { context ->
            SibionicsRegistry.clearStartTimeMs(context, SerialNumber)
            HistorySyncAccess.markSensorReset(SerialNumber)
        }
    }

    private fun acceptRehydrationIndex(index: Int): Boolean {
        if (rehydrationExpectedIndex < 0) {
            if (index > 1) {
                Log.w(
                    SibionicsConstants.TAG,
                    "cannot rehydrate exact algorithm: expected idx 0/1, received idx=$index",
                )
                return false
            }
            rehydrationExpectedIndex = index + 1
            return true
        }
        if (index < rehydrationExpectedIndex) return false
        if (index != rehydrationExpectedIndex) {
            Log.w(
                SibionicsConstants.TAG,
                "cannot rehydrate exact algorithm: expected idx=$rehydrationExpectedIndex, received idx=$index",
            )
            return false
        }
        rehydrationExpectedIndex = index + 1
        return true
    }

    private fun storeAndPublish(readings: List<EmittedReading>) {
        if (readings.isEmpty()) return
        val history = ArrayList<EmittedReading>()
        var live: EmittedReading? = null
        for (reading in readings.sortedBy { it.sampleMs }) {
            if (reading.live) {
                live = reading
            } else if (reading.index != lastLiveIndexSeen) {
                history += reading
            }
        }

        if (history.isNotEmpty()) {
            val timestamps = LongArray(history.size) { history[it].sampleMs }
            val values = FloatArray(history.size) { history[it].glucoseMgdl }
            val raws = FloatArray(history.size) { history[it].rawMgdl }
            HistorySyncAccess.storeSensorHistoryBatchAsync(SerialNumber, timestamps, values, raws)
        }

        live?.let { publishLiveReading(it) }
        UiRefreshBus.requestDataRefresh()
    }

    private fun publishLiveReading(reading: EmittedReading) {
        val previousTime = latestReadingTimeMs
        val previousValue = latestGlucoseMgdl
        latestReadingTimeMs = reading.sampleMs
        latestGlucoseMgdl = reading.glucoseMgdl
        latestRawMgdl = reading.rawMgdl
        latestTemperatureC = reading.temperatureC
        latestImpedance = reading.impedance
        lastLiveIndexSeen = reading.index
        latestRateMgdlPerMin = if (previousTime > 0L && previousValue.isFinite() && reading.sampleMs > previousTime) {
            (reading.glucoseMgdl - previousValue) / ((reading.sampleMs - previousTime) / 60_000f)
        } else {
            Float.NaN
        }
        Applic.app?.let {
            SibionicsRegistry.saveLastReading(it, SerialNumber, reading.sampleMs, reading.glucoseMgdl, reading.rawMgdl)
        }
        HistorySyncAccess.storeCurrentReadingAsync(
            reading.sampleMs,
            reading.glucoseMgdl,
            reading.rawMgdl,
            latestRateMgdlPerMin.takeIf { it.isFinite() } ?: 0f,
            SerialNumber,
        )
        val displayValue = toDisplay(reading.glucoseMgdl)
        val displayRate = if (Applic.unit == 1) latestRateMgdlPerMin / SibionicsConstants.MGDL_PER_MMOLL else latestRateMgdlPerMin
        SuperGattCallback.processExternalCurrentReading(
            SerialNumber,
            displayValue,
            displayRate.takeIf { it.isFinite() } ?: 0f,
            reading.sampleMs,
            SibionicsConstants.SENSOR_GEN,
        )
        setStatus(connectedStatus())
        Log.i(
            SibionicsConstants.TAG,
            "live idx=${reading.index} mgdl=%.1f raw=%.1f temp=%.1f".format(
                reading.glucoseMgdl,
                reading.rawMgdl,
                reading.temperatureC,
            ),
        )
    }

    private fun sanitizeSampleTime(timeMs: Long): Long {
        val now = System.currentTimeMillis()
        if (timeMs < MIN_REASONABLE_TIME_MS) return now
        if (timeMs > now + MAX_FUTURE_DRIFT_MS) return now
        return timeMs
    }

    private fun enableNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(ch, true)
        val descriptor = ch.getDescriptor(SibionicsConstants.CCCD)
        if (descriptor == null) {
            startProtocolProbe()
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val wrote = runCatching { gatt.writeDescriptor(descriptor) }.getOrDefault(false)
        if (!wrote) {
            Log.w(SibionicsConstants.TAG, "write CCCD failed; starting probe anyway")
            startProtocolProbe()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCommand(bytes: ByteArray, label: String): Boolean {
        val gatt = mBluetoothGatt ?: return false
        val ch = writeChar ?: return false
        val writeType = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        ch.writeType = writeType
        ch.value = bytes
        val ok = runCatching { gatt.writeCharacteristic(ch) }.getOrDefault(false)
        Log.i(SibionicsConstants.TAG, "write $label ok=$ok bytes=${SibionicsProtocol.toHex(bytes)}")
        return ok
    }

    override fun supportsResetAction(): Boolean = true

    override fun sendMaintenanceCommand(opCode: Int): Boolean =
        if (opCode == 0x10 || opCode == 0) resetSensor() else false

    override fun resetSensor(): Boolean {
        pendingResetCommand = false
        val ok = writeResetCommand("user")
        if (ok) {
            return true
        }
        pendingResetCommand = true
        connectDevice(0)
        setStatus("Reset queued")
        return true
    }

    private fun writeResetCommand(reason: String): Boolean {
        val ok = writeCommand(SibionicsProtocol.buildResetPacket(0), "reset-$reason")
        if (ok) markResetSent()
        return ok
    }

    private fun markResetSent() {
        algorithm.reset()
        lastIndex = 1
        lastIndexDirty = true
        algorithmStateDirty = true
        algorithmRehydrating = false
        rehydrationExpectedIndex = -1
        rehydrationTargetIndex = 0
        lastLiveAlgorithmIndexSeen = -1
        lastLiveIndexSeen = -1
        startTimeMs = 0L
        latestReadingTimeMs = 0L
        latestGlucoseMgdl = Float.NaN
        latestRawMgdl = Float.NaN
        Applic.app?.let {
            SibionicsRegistry.saveAlgorithmCheckpoint(it, SerialNumber, lastIndex, algorithm.snapshot())
            SibionicsRegistry.clearStartTimeMs(it, SerialNumber)
            HistorySyncAccess.markSensorReset(SerialNumber)
        }
        lastIndexDirty = false
        algorithmStateDirty = false
        setStatus("Reset sent")
        UiRefreshBus.requestStatusRefresh()
    }

    override fun supportsClearCalibrationAction(): Boolean = true

    override fun clearSensorCalibration(): Boolean {
        val previousIndex = lastIndex
        beginAlgorithmRehydration(previousIndex, "manual algorithm reset")
        Applic.app?.let {
            SibionicsRegistry.clearAlgorithmState(it, SerialNumber)
            SibionicsRegistry.saveLastIndex(it, SerialNumber, lastIndex)
        }
        lastIndexDirty = false
        scheduleReconnect("manual algorithm reset")
        setStatus("Replaying algorithm")
        UiRefreshBus.requestStatusRefresh()
        return true
    }

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val time = latestReadingTimeMs
        val glucose = latestGlucoseMgdl
        if (time <= 0L || !glucose.isFinite() || glucose <= 0f) return null
        if (abs(System.currentTimeMillis() - time) > maxAgeMillis) return null
        val raw = latestRawMgdl
        val displayGlucose = toDisplay(glucose)
        val displayRaw = if (raw.isFinite() && raw > 0f) toDisplay(raw) else Float.NaN
        val displayRate = if (latestRateMgdlPerMin.isFinite()) {
            if (Applic.unit == 1) latestRateMgdlPerMin / SibionicsConstants.MGDL_PER_MMOLL else latestRateMgdlPerMin
        } else {
            Float.NaN
        }
        return ManagedSensorCurrentSnapshot(
            timeMillis = time,
            glucoseValue = displayGlucose,
            rawGlucoseValue = displayRaw,
            calibratedGlucoseValue = Float.NaN,
            rate = displayRate,
            sensorGen = SibionicsConstants.SENSOR_GEN,
        )
    }

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot =
        ManagedSensorUiSnapshot(
            serial = SerialNumber,
            displayName = mygetDeviceName(),
            deviceAddress = mActiveDeviceAddress ?: record?.address ?: "Unknown",
            uiFamily = ManagedSensorUiFamily.SIBIONICS,
            connectionStatus = if (phase == Phase.STREAMING) connectedStatus() else constatstatusstr.orEmpty(),
            detailedStatus = getDetailedBleStatus(),
            subtitleStatus = getDetailedBleStatus().ifBlank { constatstatusstr.orEmpty() },
            showConnectionStatusInDetails = true,
            startTimeMs = startTimeMs,
            officialEndMs = officialEndMs(),
            expectedEndMs = expectedEndMs(),
            isUiEnabled = !stop,
            isActive = SensorIdentity.matches(activeSensorId, SerialNumber),
            rssi = readrssi,
            dataptr = 0L,
            viewMode = viewMode,
            supportsDisplayModes = supportsDisplayModes(),
            supportsManualCalibration = supportsManualCalibration(),
            supportsHardwareReset = supportsResetAction(),
            supportsClearCalibration = supportsClearCalibrationAction(),
            sensorDetailTelemetry = detailTelemetry(),
            isVendorPaired = true,
            isVendorConnected = mBluetoothGatt != null && phase == Phase.STREAMING,
            sensorRemainingHours = remainingHours(),
            sensorAgeHours = ageHours(),
            vendorModel = variant.displayLabel,
            vendorFirmware = protocolMode.name,
        )

    override fun getDetailedBleStatus(): String {
        if (uiPaused || stop) return disconnectedStatus()
        return when (phase) {
            Phase.IDLE -> disconnectedStatus()
            Phase.CONNECTING -> connectingStatus()
            Phase.DISCOVERING -> "Discovering"
            Phase.ENABLING_NOTIFY -> "Subscribing"
            Phase.PROBING_CHINESE -> "Probing"
            Phase.AUTHENTICATING -> "Authenticating"
            Phase.ACTIVATING -> "Activating"
            Phase.SYNCING_TIME -> "Syncing time"
            Phase.REQUESTING_DATA -> "Requesting data"
            Phase.STREAMING -> if (latestReadingTimeMs > 0L) connectedStatus() else waitingForDataStatus()
            Phase.ERROR -> constatstatusstr.orEmpty().ifBlank { "Error" }
        }
    }

    private fun detailTelemetry(): String =
        buildString {
            append(protocolMode.name)
            append(" • ")
            append(variant.id)
            append(" • idx=")
            append(lastIndex)
            if (shortCode.isNotBlank()) {
                append(" • ")
                append(shortCode)
                append(if (SibionicsSensitivity.tryDecode(shortCode) != null) " qrSens=" else " fallbackSens=")
                append("%.2f".format(sensitivity))
            }
            if (latestTemperatureC.isFinite()) {
                append(" • temp=")
                append("%.1f".format(latestTemperatureC))
            }
        }

    private fun officialEndMs(): Long =
        if (startTimeMs > 0L) startTimeMs + SibionicsConstants.DEFAULT_EXPECTED_LIFETIME_MS else 0L

    private fun expectedEndMs(): Long {
        if (startTimeMs <= 0L) return 0L
        val duration = if (protocolMode == SibionicsConstants.ProtocolMode.CHINESE) {
            SibionicsConstants.EXTENDED_CHINESE_LIFETIME_MS
        } else {
            SibionicsConstants.DEFAULT_EXPECTED_LIFETIME_MS
        }
        return startTimeMs + duration
    }

    private fun remainingHours(): Int {
        val end = expectedEndMs()
        if (end <= 0L) return -1
        val remaining = end - System.currentTimeMillis()
        return if (remaining <= 0L) 0 else (remaining / 3_600_000L).toInt()
    }

    private fun ageHours(): Int =
        if (startTimeMs <= 0L) -1 else ((System.currentTimeMillis() - startTimeMs) / 3_600_000L).toInt()

    private fun toDisplay(mgdl: Float): Float =
        if (Applic.unit == 1) mgdl / SibionicsConstants.MGDL_PER_MMOLL else mgdl

    private fun connectedStatus(): String =
        runCatching { Applic.app.getString(R.string.status_connected) }.getOrDefault("Connected")

    private fun disconnectedStatus(): String =
        runCatching { Applic.app.getString(R.string.status_disconnected) }.getOrDefault("Disconnected")

    private fun connectingStatus(): String =
        runCatching { Applic.app.getString(R.string.connecting) }.getOrDefault("Connecting...")

    private fun waitingForDataStatus(): String =
        runCatching { Applic.app.getString(R.string.status_waiting_for_data) }.getOrDefault("Connected, waiting for data...")

    private fun setStatus(status: String) {
        constatstatusstr = status
        UiRefreshBus.requestStatusRefresh()
    }

    private fun knownBleAddress(): String? =
        SibionicsConstants.normalizeBleAddress(mActiveDeviceAddress)
            ?: SibionicsConstants.normalizeBleAddress(record?.address)
            ?: SibionicsConstants.normalizeBleAddress(SibionicsRegistry.findRecord(Applic.app, SerialNumber)?.address)

    private fun hydrateBluetoothDeviceFromAddress(): Boolean {
        val address = knownBleAddress() ?: return false
        mActiveDeviceAddress = address
        if (mActiveBluetoothDevice?.address?.equals(address, ignoreCase = true) == true) return true
        val adapter = runCatching {
            (Applic.app?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
        }.getOrNull() ?: return false
        mActiveBluetoothDevice = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        return mActiveBluetoothDevice != null
    }

    private fun scheduleReconnect(reason: String) {
        if (stop || uiPaused) return
        Log.i(SibionicsConstants.TAG, "schedule reconnect: $reason")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private val reconnectRunnable = Runnable {
        if (!stop && !uiPaused) connectDevice(0)
    }

    private fun scheduleAuthTimeout() {
        handler.removeCallbacks(authTimeoutRunnable)
        handler.postDelayed(authTimeoutRunnable, CHINESE_PROBE_TIMEOUT_MS)
    }

    private fun scheduleHandshakeTimeout() {
        handler.removeCallbacks(handshakeTimeoutRunnable)
        handler.postDelayed(handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS)
    }

    private val handshakeTimeoutRunnable = Runnable {
        setStatus("Handshake timeout")
        sendAuthPacket()
    }

    private fun scheduleChineseProbeTimeout() {
        handler.removeCallbacks(chineseProbeTimeoutRunnable)
        handler.postDelayed(chineseProbeTimeoutRunnable, CHINESE_PROBE_TIMEOUT_MS)
    }

    private fun scheduleChineseDataTimeout() {
        handler.removeCallbacks(chineseDataTimeoutRunnable)
        handler.postDelayed(chineseDataTimeoutRunnable, CHINESE_DATA_TIMEOUT_MS)
    }

    private val chineseProbeTimeoutRunnable = Runnable {
        if (phase == Phase.PROBING_CHINESE) switchToV120("Chinese probe timeout")
    }

    private val chineseDataTimeoutRunnable = Runnable {
        if (protocolMode == SibionicsConstants.ProtocolMode.CHINESE) {
            sendChineseDataRequest(probing = false)
            scheduleChineseDataTimeout()
        }
    }

    private fun scheduleChinesePoll() {
        handler.removeCallbacks(chinesePollRunnable)
        handler.postDelayed(chinesePollRunnable, CHINESE_POLL_INTERVAL_MS)
    }

    private val chinesePollRunnable = Runnable {
        if (!stop && protocolMode == SibionicsConstants.ProtocolMode.CHINESE) {
            sendChineseDataRequest(probing = false)
            scheduleChinesePoll()
        }
    }

    private fun scheduleStreamingTimeout() {
        handler.removeCallbacks(streamingTimeoutRunnable)
        handler.postDelayed(streamingTimeoutRunnable, STREAMING_TIMEOUT_MS)
    }

    private val streamingTimeoutRunnable = Runnable {
        if (phase == Phase.STREAMING && System.currentTimeMillis() - latestReadingTimeMs > CURRENT_FRESH_MS) {
            setStatus(waitingForDataStatus())
            scheduleReconnect("streaming timeout")
        }
    }
}
