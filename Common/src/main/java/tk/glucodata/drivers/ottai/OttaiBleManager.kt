// OttaiBleManager.kt — BLE state machine for Ottai CGM.
//
// Flow after connect: discover -> requestMtu -> enable notifications (history,
// live, cgm-info) -> Auth V2 (read device time, read device param+sign, verify,
// write app param+sign, derive ECDH session key) -> STREAMING (decrypt+parse
// live/history). Activation is a separate, explicitly-gated action because it
// starts the sensor's irreversible lifetime (see OttaiDriver.requestActivation).
//
// The crypto/auth/parse are delegated to the unit-tested OttaiCrypto/OttaiBleAuth/
// OttaiParser. This manager only sequences GATT ops and wires results. GATT
// timing details will be refined during field testing; the structure + crypto
// calls are faithful to the decompile.

package tk.glucodata.drivers.ottai

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus

@SuppressLint("MissingPermission")
class OttaiBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), OttaiDriver {

    companion object {
        private const val TAG = OttaiConstants.TAG
        const val SENSOR_GEN = 0
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MTU = 247

        /**
         * Set (by the setup wizard) to a canonical sensorId to request a one-time
         * activation on that sensor's next successful auth. Cleared once fired.
         * Activation starts the sensor's irreversible lifetime, so it is only ever
         * armed by an explicit user action.
         */
        @Volatile @JvmStatic var activateRequestedFor: String? = null
    }

    enum class Phase { IDLE, CONNECTING, DISCOVERING, ENABLING_NOTIFY, AUTH, STREAMING }
    private enum class AuthStep { NONE, READ_DEVICE_TIME, READ_DEVICE_PARAM, READ_DEVICE_SIGN, WRITE_APP_PARAM, WRITE_APP_SIGN, DONE }
    private enum class ActStep { NONE, RTC, MAX_ACTIVE, DESTRUCTION, COMMAND, DONE }

    @Volatile var phase: Phase = Phase.IDLE
        private set

    private val handlerThread = HandlerThread("Ottai-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    private var svcDeviceInfo: BluetoothGattService? = null
    private var svcCgm: BluetoothGattService? = null
    private var svcAuth: BluetoothGattService? = null

    // ---- session state ----
    @Volatile private var materials: OttaiRegistry.DeviceMaterials = OttaiRegistry.DeviceMaterials("", "", "", 0L, "", 0)
    @Volatile private var authKeys: List<ByteArray>? = null
    @Volatile private var macBytes: ByteArray = ByteArray(0)

    @Volatile private var deviceTimeBytes: ByteArray = ByteArray(0)
    @Volatile private var deviceParamIndex: Int = 0
    @Volatile private var deviceParamTime: ByteArray = ByteArray(0)
    @Volatile private var devicePubX: ByteArray = ByteArray(0)
    @Volatile private var devicePubY: ByteArray = ByteArray(0)

    @Volatile private var appPrivate: ECPrivateKey? = null
    @Volatile private var appPubX: ByteArray = ByteArray(0)
    @Volatile private var appPubY: ByteArray = ByteArray(0)
    @Volatile private var appTime3: ByteArray = ByteArray(0)
    @Volatile private var appIndex: Int = 0
    @Volatile private var sessionKeyHex: String = ""

    @Volatile private var authStep: AuthStep = AuthStep.NONE
    @Volatile private var actStep: ActStep = ActStep.NONE
    @Volatile private var notifyEnableIndex = 0
    @Volatile private var discoveryStarted = false
    // Set while we re-run service discovery AFTER auth (the sensor exposes a Service
    // Changed characteristic and may restructure its GATT post-auth, leaving the
    // pre-auth handles for the activation chars stale). onServicesDiscovered consumes it.
    @Volatile private var pendingActivation = false

    @Volatile private var lastGlucoseAtMs = 0L
    @Volatile private var lastGlucoseMgdl = 0f
    @Volatile private var lastRawCurrent = Float.NaN
    @Volatile private var lastDataNo = -1

    override var viewMode: Int = 0

    private val notifyOrder = listOf(
        OttaiConstants.SERVICE_CGM to OttaiConstants.CHAR_GLUCOSE_HISTORY,
        OttaiConstants.SERVICE_CGM to OttaiConstants.CHAR_GLUCOSE_LIVE,
        OttaiConstants.SERVICE_DEVICE_INFO to OttaiConstants.CHAR_CGM_INFO_NOTIFY,
    )

    // ---- persistence ----

    fun restoreFromPersistence(context: Context) {
        val id = SerialNumber ?: return
        materials = OttaiRegistry.loadMaterials(context, id)
        authKeys = materials.authKeys
        lastDataNo = OttaiRegistry.loadLastDataNo(context, id)
        // Auth V2 signs the cloud id bytes, not necessarily the Android BLE address.
        macBytes = runCatching { OttaiCrypto.hexToBytes(OttaiConstants.canonicalSensorId(id)) }.getOrDefault(ByteArray(0))
    }

    private val reconnectRunnable = Runnable { if (!stop) connectDevice(0) }
    private fun scheduleReconnect(reason: String, delay: Long = RECONNECT_DELAY_MS) {
        if (stop) return
        Log.i(TAG, "reconnect: $reason")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    // ---- lifecycle ----

    override fun getService(): UUID = OttaiConstants.SERVICE_CGM

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        if (phase != Phase.IDLE && (mBluetoothGatt != null || mActiveBluetoothDevice != null)) return true
        phase = Phase.CONNECTING
        val scheduled = super.connectDevice(delayMillis)
        if (!scheduled && phase == Phase.CONNECTING) phase = Phase.IDLE
        return scheduled
    }

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val scanned = OttaiConstants.normalizeBleAddress(address, allowPlain = false) ?: return false
        val known = OttaiConstants.normalizeBleAddress(mActiveDeviceAddress, allowPlain = false)
            ?: OttaiConstants.normalizeBleAddress(
                OttaiRegistry.findRecord(Applic.app, SerialNumber)?.address,
                allowPlain = false,
            )
        return known != null && scanned.equals(known, ignoreCase = true)
    }

    override fun setDeviceAddress(address: String?) {
        super.setDeviceAddress(address)
        val normalized = OttaiConstants.normalizeBleAddress(address, allowPlain = false) ?: return
        val ctx = Applic.app ?: return
        val id = SerialNumber ?: return
        OttaiRegistry.ensureSensorRecord(ctx, id, normalized, OttaiConstants.DEFAULT_DISPLAY_NAME)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (stop) return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "connected ${gatt.device?.address}")
                handler.removeCallbacks(reconnectRunnable)
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                connectTime = System.currentTimeMillis()
                phase = Phase.DISCOVERING
                authStep = AuthStep.NONE
                actStep = ActStep.NONE
                notifyEnableIndex = 0
                discoveryStarted = false
                // CGM links drop quickly at default (balanced) params; request a fast
                // interval so auth/activation completes before the sensor drops idle.
                runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                // Start discovery ONLY after MTU settles (in onMtuChanged). A fixed-delay
                // discoverServices() raced the MTU exchange on the Mi9T: an MTU change
                // landing mid-discovery dropped the discovery callback (onServicesDiscovered
                // never fired → no auth → sensor terminates after ~60s, status=19). Do NOT
                // call gatt.refresh() either — where it actually runs it wipes the cache
                // mid-setup and breaks discovery the same way.
                val mtuRequested = runCatching { gatt.requestMtu(MTU) }.getOrDefault(false)
                // Fallback: if onMtuChanged never fires, discover anyway.
                handler.postDelayed({ startServiceDiscovery(gatt) }, if (mtuRequested) 1500 else 300)
                UiRefreshBus.requestStatusRefresh()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "disconnected status=$status")
                phase = Phase.IDLE
                svcDeviceInfo = null; svcCgm = null; svcAuth = null
                sessionKeyHex = ""
                runCatching { gatt.close() }
                mBluetoothGatt = null
                mActiveBluetoothDevice = null
                if (!stop) scheduleReconnect("gatt disconnect")
                UiRefreshBus.requestStatusRefresh()
            }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Log.d(TAG, "mtu=$mtu status=$status")
        startServiceDiscovery(gatt)
    }

    /** Idempotent: kicks off service discovery exactly once per connection. */
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (discoveryStarted || phase != Phase.DISCOVERING) return
        discoveryStarted = true
        val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
        Log.i(TAG, "discoverServices() started=$started")
        if (!started) {
            discoveryStarted = false
            handler.postDelayed({ startServiceDiscovery(gatt) }, 800)
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            scheduleReconnect("discover failed $status"); return
        }
        // One-time GATT map dump: service UUID + instanceId (≈ATT handle), then each
        // characteristic's short UUID, handle and properties. Reveals duplicate-UUID
        // services and the real handle of b8fd9848/6aa799b6 (the activation writes that
        // fail) so we can tell a resolution bug from the sensor rejecting the write.
        runCatching {
            for (s in gatt.services) {
                val chars = s.characteristics.joinToString(" ") {
                    "${it.uuid.toString().take(8)}#${it.instanceId}/0x${it.properties.toString(16)}"
                }
                Log.i(TAG, "GATT svc ${s.uuid.toString().take(8)}#${s.instanceId} [$chars]")
            }
        }
        svcDeviceInfo = gatt.getService(OttaiConstants.SERVICE_DEVICE_INFO)
        svcCgm = gatt.getService(OttaiConstants.SERVICE_CGM)
        svcAuth = gatt.getService(OttaiConstants.SERVICE_AUTH)
        if (svcCgm == null || svcAuth == null || svcDeviceInfo == null) {
            Log.e(TAG, "missing Ottai services (cgm=$svcCgm auth=$svcAuth info=$svcDeviceInfo)")
            scheduleReconnect("missing services"); return
        }
        // Post-auth re-discovery (requested by requestActivation): handles above are now
        // fresh — start the activation writes. The GATT map dumped above lets us compare
        // b8fd9848's handle pre- vs post-auth.
        if (pendingActivation) {
            pendingActivation = false
            Log.i(TAG, "post-auth re-discovery complete — starting activation")
            startActivationWrites(gatt)
            return
        }
        authKeys = materials.authKeys
        if (authKeys == null) {
            Log.w(TAG, "no auth keys (cloud materials missing) — cannot authenticate")
            // Stay connected but idle; the driver needs cloud bind first.
            phase = Phase.IDLE
            return
        }
        phase = Phase.ENABLING_NOTIFY
        notifyEnableIndex = 0
        enableNextNotification(gatt)
    }

    private fun enableNextNotification(gatt: BluetoothGatt) {
        if (notifyEnableIndex >= notifyOrder.size) {
            startAuth(gatt)
            return
        }
        val (svcUuid, chUuid) = notifyOrder[notifyEnableIndex]
        val ch = gatt.getService(svcUuid)?.getCharacteristic(chUuid)
        if (ch == null) {
            notifyEnableIndex++
            enableNextNotification(gatt)
            return
        }
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(OttaiConstants.CCCD)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            runCatching { gatt.writeDescriptor(cccd) }
        } else {
            notifyEnableIndex++
            enableNextNotification(gatt)
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (phase == Phase.ENABLING_NOTIFY) {
            notifyEnableIndex++
            enableNextNotification(gatt)
        }
    }

    // ---- Auth V2 ----

    private fun startAuth(gatt: BluetoothGatt) {
        phase = Phase.AUTH
        authStep = AuthStep.READ_DEVICE_TIME
        readChar(gatt, OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_CURRENT_TIME)
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
        val value = ch.value ?: ByteArray(0)
        when (ch.uuid) {
            OttaiConstants.CHAR_CURRENT_TIME -> {
                deviceTimeBytes = value.copyOf()
                authStep = AuthStep.READ_DEVICE_PARAM
                readChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_DEVICE_PARAM)
            }
            OttaiConstants.CHAR_AUTH_DEVICE_PARAM -> {
                parseDeviceAuthParam(value)
                authStep = AuthStep.READ_DEVICE_SIGN
                readChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_SIGN)
            }
            OttaiConstants.CHAR_AUTH_SIGN -> {
                verifyDeviceSign(value)
                writeAppParam(gatt)
            }
            OttaiConstants.CHAR_GLUCOSE_LIVE -> handleGlucosePayload(value, live = true)
        }
    }

    private fun parseDeviceAuthParam(v: ByteArray) {
        // [0]=index, [1..3]=time(3), [4..35]=pubX(32), [36..67]=pubY(32)
        if (v.size < 68) { Log.w(TAG, "device param short ${v.size}"); return }
        deviceParamIndex = v[0].toInt() and 0xFF
        deviceParamTime = v.copyOfRange(1, 4)
        devicePubX = v.copyOfRange(4, 36)
        devicePubY = v.copyOfRange(36, 68)
    }

    private fun verifyDeviceSign(deviceSign: ByteArray) {
        val keys = authKeys ?: return
        if (deviceParamIndex !in keys.indices) { Log.w(TAG, "device index oob"); return }
        val authKeyHex = OttaiCrypto.bytesToHex(keys[deviceParamIndex])
        val ok = OttaiBleAuth.verifyDeviceSign(
            deviceSign, authKeyHex, OttaiCrypto.bytesToHex(macBytes),
            devicePubX, devicePubY, deviceParamTime,
        )
        // Per decompile notes the boolean polarity is noisy; log, do not hard-fail.
        Log.i(TAG, "device sign verify=$ok")
    }

    private fun writeAppParam(gatt: BluetoothGatt) {
        val keys = authKeys ?: return
        val kp = OttaiBleAuth.generateKeyPair()
        appPrivate = kp.private as ECPrivateKey
        val (x, y) = OttaiBleAuth.publicCoords(kp.public as ECPublicKey)
        appPubX = x; appPubY = y
        appTime3 = OttaiBleAuth.appTime3(deviceTimeBytes)
        appIndex = (0 until keys.size).random()
        val param = OttaiBleAuth.appAuthParameter(appIndex, appTime3, appPubX, appPubY)
        authStep = AuthStep.WRITE_APP_PARAM
        writeChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_APP_PARAM, param)
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "write err ${ch.uuid.toString().take(8)} status=$status actStep=$actStep")
            if (actStep != ActStep.NONE) {
                // Match the official app: ANY activation-write failure aborts (it returns
                // FALSE and never proceeds). On a healthy/virgin sensor every step succeeds.
                Log.e(TAG, "activation aborted after $actStep write error (status=$status) — " +
                    "sensor rejected the write (advertised handle, refused at write time)")
                actStep = ActStep.NONE
            }
            return
        }
        when {
            authStep == AuthStep.WRITE_APP_PARAM && ch.uuid == OttaiConstants.CHAR_AUTH_APP_PARAM -> {
                val keys = authKeys ?: return
                val authKeyHex = OttaiCrypto.bytesToHex(keys[appIndex])
                val sign = OttaiBleAuth.authSignHex(authKeyHex, OttaiCrypto.bytesToHex(macBytes), appPubX, appPubY, appTime3)
                authStep = AuthStep.WRITE_APP_SIGN
                writeChar(gatt, OttaiConstants.SERVICE_AUTH, OttaiConstants.CHAR_AUTH_SIGN, sign)
            }
            authStep == AuthStep.WRITE_APP_SIGN && ch.uuid == OttaiConstants.CHAR_AUTH_SIGN -> {
                deriveSession()
                authStep = AuthStep.DONE
                phase = Phase.STREAMING
                val sessionOk = sessionKeyHex.isNotBlank()
                Log.i(TAG, "auth complete; session=${if (sessionOk) "ok" else "FAILED"}")
                UiRefreshBus.requestStatusRefresh()
                // Activation fires once, promptly (within the sensor's idle window):
                //  - AUTO: a virgin sensor (no cloud activeTime) self-activates on its
                //    first authenticated connect, guarded one-shot so reconnects don't
                //    re-fire the irreversible write.
                //  - EXPLICIT: the Advanced "Activate" action arms activateRequestedFor.
                val ctx = Applic.app
                val id = SerialNumber
                if (sessionOk && id != null) {
                    val explicit = OttaiConstants.matchesCanonicalOrKnownNativeAlias(id, activateRequestedFor)
                    val auto = !explicit && ctx != null && materials.activeTimeMs <= 0L &&
                        !OttaiRegistry.loadActivationAttempted(ctx, id)
                    if (explicit || auto) {
                        activateRequestedFor = null
                        if (ctx != null) OttaiRegistry.setActivationAttempted(ctx, id, true)
                        Log.i(TAG, "auto-activating on first connect (explicit=$explicit auto=$auto)")
                        handler.postDelayed({ runCatching { requestActivation() } }, 400)
                    }
                }
            }
            // Any successful activation write advances the sequence (RTC/maxActive on
            // 0000180a, destruction on 84c5b711, cmd on 0000181f).
            actStep != ActStep.NONE -> advanceActivation(gatt)
        }
    }

    private fun deriveSession() {
        val priv = appPrivate ?: return
        sessionKeyHex = OttaiBleAuth.deriveSessionKey(
            OttaiCrypto.bytesToHex(devicePubX),
            OttaiCrypto.bytesToHex(devicePubY),
            priv,
        ).orEmpty()
    }

    // ---- streaming ----

    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        val value = ch.value ?: return
        when (ch.uuid) {
            OttaiConstants.CHAR_GLUCOSE_LIVE -> handleGlucosePayload(value, live = true)
            OttaiConstants.CHAR_GLUCOSE_HISTORY -> handleGlucosePayload(value, live = false)
        }
    }

    private fun handleGlucosePayload(cipher: ByteArray, live: Boolean) {
        if (sessionKeyHex.isBlank()) { Log.w(TAG, "payload before session key"); return }
        val activeMs = materials.activeTimeMs
        val readings = if (live) {
            OttaiParser.parseLive(cipher, sessionKeyHex, materials.method, materials.coefficients, activeMs)
                ?.let { listOf(it) } ?: emptyList()
        } else {
            OttaiParser.parseHistory(cipher, sessionKeyHex, materials.method, materials.coefficients, activeMs)
        }
        for (r in readings) {
            if (!r.valid) continue
            if (materials.method.isBlank()) {
                Log.w(TAG, "no method — skipping emit (raw only) dataNo=${r.record.dataNo}")
                continue
            }
            emitReading(r)
        }
        if (readings.isNotEmpty()) UiRefreshBus.requestStatusRefresh()
    }

    private fun emitReading(r: OttaiReading) {
        // adjustGlucose is mmol/L; convert to mg/dL for native stream.
        val mgdl = (r.adjustGlucose * 18.0).toFloat()
        if (mgdl <= 1f) return
        val sampleMs = if (r.monitorTimeMs > 0L) r.monitorTimeMs else System.currentTimeMillis()
        if (sampleMs >= lastGlucoseAtMs) {
            lastGlucoseAtMs = sampleMs
            lastGlucoseMgdl = mgdl
            lastRawCurrent = r.record.rawCurrent.toFloat()
            lastDataNo = r.record.dataNo
        }
        Log.i(TAG, "BG dataNo=${r.record.dataNo} mmol=%.2f mgdl=%.0f raw=%d T=%.1f".format(
            r.adjustGlucose, mgdl, r.record.rawCurrent, r.record.temperatureC))
        mirrorReadingIntoNative(sampleMs, mgdl)
        Applic.app?.let { OttaiRegistry.saveLastDataNo(it, SerialNumber.orEmpty(), r.record.dataNo) }
    }

    private fun ensureNativeShell() {
        val id = SerialNumber ?: return
        runCatching {
            val startSec = (materials.activeTimeMs / 1000L).coerceAtLeast(1L)
            Natives.ensureSensorShell(id, startSec)
            if (dataptr == 0L) dataptr = runCatching { Natives.getdataptr(id) }.getOrDefault(0L)
        }.onFailure { Log.stack(TAG, "ensureNativeShell", it) }
    }

    private fun mirrorReadingIntoNative(sampleMs: Long, mgdl: Float) {
        val id = SerialNumber ?: return
        ensureNativeShell()
        runCatching {
            Natives.addGlucoseStream(sampleMs / 1000L, mgdl, id)
            Natives.wakebackup()
        }.onFailure { Log.stack(TAG, "mirrorReadingIntoNative", it) }
    }

    // ---- activation (gated) ----

    override fun requestActivation(): Boolean {
        val gatt = mBluetoothGatt ?: return false
        if (phase != Phase.STREAMING || sessionKeyHex.isBlank()) {
            Log.w(TAG, "activation refused — not authenticated (phase=$phase)")
            return false
        }
        // Re-discover first. The pre-auth GATT can be stale post-auth (b8fd9848/6aa799b6
        // returned Invalid Handle on the handles discovered before auth, while the sensor
        // advertises Service Changed). A fresh discovery picks up the post-auth handles;
        // if discovery can't start, fall back to the current handles.
        pendingActivation = true
        discoveryStarted = true // suppress the connect-time discovery guard
        Log.i(TAG, "re-discovering services before activation")
        if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
            pendingActivation = false
            startActivationWrites(gatt)
        }
        return true
    }

    private fun startActivationWrites(gatt: BluetoothGatt) {
        Log.i(TAG, "starting activation sequence")
        actStep = ActStep.RTC
        writeRtc(gatt)
    }

    private fun advanceActivation(gatt: BluetoothGatt) {
        when (actStep) {
            ActStep.RTC -> { actStep = ActStep.MAX_ACTIVE; writeMaxActiveTime(gatt) }
            ActStep.MAX_ACTIVE -> { actStep = ActStep.DESTRUCTION; writeDestructionTime(gatt) }
            ActStep.DESTRUCTION -> { actStep = ActStep.COMMAND; writeActivateCmd(gatt) }
            ActStep.COMMAND -> { actStep = ActStep.DONE; Log.i(TAG, "activation command sent") }
            else -> {}
        }
    }

    private fun writeRtc(gatt: BluetoothGatt) {
        val secs = (System.currentTimeMillis() / 1000L).toInt()
        writeChar(gatt, OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_CURRENT_TIME, OttaiBleAuth.intToBytesLE(secs))
    }

    private fun writeMaxActiveTime(gatt: BluetoothGatt) {
        // Official: p.U(p.w0(p.D / 1000), hex2bytes(sessionKey)), where p.D =
        // activeExpireTime (ms) from the cloud validate-by-mac response. p.U zero-pads
        // to one AES block and encrypts with AES/ECB/ZeroBytePadding; this is a 16-byte
        // write, not plaintext duration + session key.
        val expireMs = materials.activeExpireTimeMs.takeIf { it > 0L } ?: OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS
        val secs = expireMs / 1000L
        val payload = OttaiCrypto.encryptPayload(longToBytesLE(secs), sessionKeyHex)
            ?: run {
                Log.e(TAG, "maxActive encryption failed — aborting activation")
                actStep = ActStep.NONE
                return
            }
        val issued = writeChar(gatt, OttaiConstants.SERVICE_DEVICE_INFO, OttaiConstants.CHAR_MAX_ACTIVE_TIME, payload)
        if (!issued) {
            // Characteristic not present at all (some firmwares omit it) — skip to the
            // next step. A present-but-rejected write is handled in onCharacteristicWrite.
            Log.w(TAG, "maxActive char absent — skipping to destruction")
            advanceActivation(gatt)
        }
    }

    private fun writeDestructionTime(gatt: BluetoothGatt) {
        // Official: p.w0(p.E / 1000) || {0x04}, where p.E = retainTime (ms) from the cloud
        // response, defaulting to 172800000 (= 172800 s) when the server omits it. This is
        // a small DURATION, not an absolute epoch — writing now+lifetime here made the
        // sensor terminate the link (HCI reason 0x13).
        val retainMs = materials.retainTimeMs.takeIf { it > 0L } ?: OttaiConstants.DEFAULT_RETAIN_TIME_MS
        val secs = retainMs / 1000L
        val payload = longToBytesLE(secs) + byteArrayOf(0x04)
        if (!writeChar(gatt, OttaiConstants.SERVICE_DESTRUCTIVE, OttaiConstants.CHAR_DESTRUCTIVE, payload)) {
            Log.e(TAG, "destruction char absent — aborting activation")
            actStep = ActStep.NONE
        }
    }

    private fun writeActivateCmd(gatt: BluetoothGatt) {
        // Official: p.U({0x03}, hex2bytes(sessionKey)) then write to the CGM command
        // characteristic. This is encrypted and exactly one AES block.
        val cmd = OttaiCrypto.encryptActivateCmd(byteArrayOf(OttaiConstants.ACTIVATE_CMD), sessionKeyHex)
            ?: run {
                Log.e(TAG, "activation command encryption failed — aborting activation")
                actStep = ActStep.NONE
                return
            }
        if (!writeChar(gatt, OttaiConstants.SERVICE_CGM, OttaiConstants.CHAR_COMMAND, cmd)) {
            Log.e(TAG, "command char absent — activation not sent")
            actStep = ActStep.NONE
        }
    }

    private fun longToBytesLE(v: Long): ByteArray = ByteArray(8) { ((v ushr (it * 8)) and 0xFF).toByte() }

    override fun requestHistoryBackfill(): Boolean {
        // History arrives via notifications on CHAR_GLUCOSE_HISTORY once subscribed;
        // a dedicated pull command is not required for the watch protocol. The
        // device pushes buffered history after auth. No-op trigger for now.
        return phase == Phase.STREAMING
    }

    // ---- GATT helpers ----

    @Suppress("DEPRECATION")
    private fun readChar(gatt: BluetoothGatt, svc: UUID, ch: UUID) {
        val c = gatt.getService(svc)?.getCharacteristic(ch)
        if (c == null) { Log.w(TAG, "readChar missing $ch"); return }
        runCatching { gatt.readCharacteristic(c) }
    }

    /** Returns true if a write was issued (characteristic resolved), false if missing. */
    @Suppress("DEPRECATION")
    private fun writeChar(gatt: BluetoothGatt, svc: UUID, ch: UUID, value: ByteArray): Boolean {
        val c = gatt.getService(svc)?.getCharacteristic(ch)
        if (c == null) { Log.w(TAG, "writeChar missing $ch"); return false }
        c.value = value
        // Match the official app: use the characteristic's supported write type rather
        // than forcing write-with-response (forcing it on a no-response-only char gets
        // an ATT error and the sensor drops the link).
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        Log.i(TAG, "write ${ch.toString().take(8)}#${c.instanceId} props=0x${c.properties.toString(16)} wt=${c.writeType} len=${value.size}")
        runCatching { gatt.writeCharacteristic(c) }
        return true
    }

    // ---- OttaiDriver ----

    /** Apply freshly-fetched cloud materials (after bind/validate + decrypt). */
    fun applyMaterials(context: Context, m: OttaiRegistry.DeviceMaterials) {
        materials = m
        authKeys = m.authKeys
        OttaiRegistry.saveMaterials(context, SerialNumber.orEmpty(), m)
    }

    override fun getCurrentSnapshot(maxAgeMillis: Long): OttaiCurrentSnapshot? {
        if (lastGlucoseAtMs == 0L) return null
        if (System.currentTimeMillis() - lastGlucoseAtMs > maxAgeMillis) return null
        return OttaiCurrentSnapshot(lastGlucoseAtMs, lastGlucoseMgdl, lastRawCurrent, Float.NaN, SENSOR_GEN)
    }

    override fun getStartTimeMs(): Long = materials.activeTimeMs
    override fun getOfficialEndMs(): Long =
        if (materials.activeTimeMs <= 0L) 0L
        else materials.activeTimeMs + OttaiConstants.DEFAULT_RATED_LIFETIME_DAYS * 24L * 3600L * 1000L
    override fun getExpectedEndMs(): Long = getOfficialEndMs()
    override fun isSensorExpired(): Boolean {
        val end = getOfficialEndMs(); return end > 0L && System.currentTimeMillis() > end
    }
    override fun getSensorRemainingHours(): Int {
        val end = getOfficialEndMs(); if (end <= 0L) return -1
        val ms = end - System.currentTimeMillis(); return if (ms <= 0L) 0 else (ms / 3_600_000L).toInt()
    }
    override fun getSensorAgeHours(): Int {
        if (materials.activeTimeMs <= 0L) return -1
        return ((System.currentTimeMillis() - materials.activeTimeMs) / 3_600_000L).toInt()
    }
    override fun getReadingIntervalMinutes(): Int = OttaiConstants.DEFAULT_READING_INTERVAL_MINUTES

    override val vendorFirmwareVersion: String get() = materials.deviceVersion
    override val vendorModelName: String get() = OttaiConstants.DEFAULT_DISPLAY_NAME

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        OttaiConstants.matchesCanonicalOrKnownNativeAlias(sensorId, SerialNumber)

    override fun hasNativeSensorBacking(): Boolean = true

    override fun getDetailedBleStatus(): String = when (phase) {
        Phase.IDLE -> if (authKeys == null) "Needs cloud bind" else "Idle"
        Phase.CONNECTING -> "Connecting"
        Phase.DISCOVERING -> "Discovering"
        Phase.ENABLING_NOTIFY -> "Subscribing"
        Phase.AUTH -> "Authenticating"
        Phase.STREAMING -> if (lastGlucoseAtMs > 0L) {
            "Streaming • ${((System.currentTimeMillis() - lastGlucoseAtMs) / 60000L)}m ago"
        } else "Streaming (awaiting data)"
    }
}
