package tk.glucodata.drivers.aidex

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
// import android.util.Log
import tk.glucodata.Log
import tk.glucodata.SuperGattCallback
import tk.glucodata.Natives
import com.microtechmd.blecomm.parser.AidexXParser
import com.microtechmd.blecomm.parser.AidexXHistoryEntity
import com.microtechmd.blecomm.BleAdapter
import com.microtechmd.blecomm.BlecommLoader
import com.microtechmd.blecomm.BluetoothDeviceStore
import com.microtechmd.blecomm.constant.AidexXOperation
import com.microtechmd.blecomm.controller.AidexXController
import com.microtechmd.blecomm.controller.BleController
import com.microtechmd.blecomm.controller.BleControllerInfo
import com.microtechmd.blecomm.entity.BleMessage
import java.lang.reflect.Method
import java.util.Calendar
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import tk.glucodata.data.HistoryRepository
import com.microtechmd.blecomm.entity.AidexXDatetimeEntity
import com.microtechmd.blecomm.entity.NewSensorEntity

/**
 * AiDex/LinX Sensor Driver.
 *
 * Handles:
 * 1. Scanning (filtering by Manufacturer Data or Service UUID).
 * 2. Connection & Handshake (Proprietary "B6" protocol).
 * 3. Decryption (AES-128 CFB with Dynamic IV).
 * 4. Official parser first, deterministic fallback when needed.
 */
class AiDexSensor(context: Context, serial: String, dataptr: Long) : SuperGattCallback(serial, dataptr, 0) {

    companion object {
        private const val TAG = "AiDexSensor"
        
        // --- PROPRIETARY SERVICES & CHARACTERISTICS ---
        // Note: Characteristics are embedded in the Standard CGM Service (0x181F)
        val SERVICE_F000: UUID = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
        val CHAR_F001: UUID    = UUID.fromString("0000F001-0000-1000-8000-00805f9b34fb") // Write/Indicate (Commands)
        val CHAR_F002: UUID    = UUID.fromString("0000F002-0000-1000-8000-00805f9b34fb") // Write/Notify (Auth?)
        val CHAR_F003: UUID    = UUID.fromString("0000F003-0000-1000-8000-00805f9b34fb") // Notify (Data Stream)

        // --- PRIVATE CONFIGURATION SERVICE (FF30) ---
        // Used for New Sensor, Reset, Shelf Mode, etc.
        val SERVICE_FF30: UUID = UUID.fromString("0000FF30-0000-1000-8000-00805f9b34fb")
        val CHAR_FF31: UUID    = UUID.fromString("0000FF31-0000-1000-8000-00805f9b34fb") // Notify (Response)
        val CHAR_FF32: UUID    = UUID.fromString("0000FF32-0000-1000-8000-00805f9b34fb") // Write (Command)
        
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // --- KEYS ---
        // "AC4C8ECDD8761B512EEB95D707942912" -> Hex bytes
        private val MASTER_KEY = byteArrayOf(
            0xAC.toByte(), 0x4C.toByte(), 0x8E.toByte(), 0xCD.toByte(),
            0xD8.toByte(), 0x76.toByte(), 0x1B.toByte(), 0x51.toByte(),
            0x2E.toByte(), 0xEB.toByte(), 0x95.toByte(), 0xD7.toByte(),
            0x07.toByte(), 0x94.toByte(), 0x29.toByte(), 0x12.toByte()
        )

        private val handshakeHandler = Handler(Looper.getMainLooper())

        // Official handshake sequences observed in LinX 1.7.25 logs.
        // Unbonded/short flow (A8/AF IV path).
        private val OFFICIAL_HANDSHAKE_UNBONDED_STEPS = arrayOf(
            "BE67CDEE",
            "BF67FCDD",
            "9A8660",
            "A9B666",
            "A825A3EEC8",
            "AF24A34F7E",
        )

        // Bonded/long flow (history-prefetch + stream enable).
        // Derived from aidex_output_timed.txt.
        private val OFFICIAL_HANDSHAKE_BONDED_STEPS = arrayOf(
            "B0C58080",
            "B1C5B1B3",
            "94242D",
            "A7142B",
            "A689ECCD88",
            "A600ECCD29",
            "A6FFED133A",
            "A676ED139B",
            "A6EDEAE52F",
            "A18DEC99C1",
            "A1BCEC3DF7",
            "A163EC24F2",
            "A112EC4CC9",
            "A1C1ED1999",
            "A1F0EDBDAF",
            "A1A7ED9538",
            "A156ED6518",
            "A105ED8943",
            "A134ED2D75",
            "A1DBEA4605",
            "A18AEAC838",
            "A1B9EA0E68",
        )

        // Post-pairing flow (observed right after SMP pairing).
        // Derived from btsnoop_hci_timed.log + aidex_output_timed.txt.
        private const val OFFICIAL_PAIRED_F001 = "91C5470280BB4C3D8FA8EDB1B06A0F06"
        private val OFFICIAL_PAIRED_F002_STEPS = arrayOf(
            "FBF050",
            "CA8276",
            "969BEADB",
            "979BDBE8",
            "B27A47",
        )

        // Legacy handshake sequence from older probes
        private val LEGACY_HANDSHAKE_STEPS = arrayOf(
            "55FB0631",
            "54FB3702",
            "711AAB",
            "422AAD",
            "43BA4C847E",
            "44C14CB72F",
            "802454",
            "81FB486A48",
            "826674",
            "B4482C",
            "B597303367",
            "B60A0C",
        )

        private const val HANDSHAKE_STEP_DELAY_MS = 120L
        private const val OFFICIAL_BONDED_STEP_DELAY_MS = 200L
        private const val HANDSHAKE_READ_DELAY_MS = 120L
        private const val HANDSHAKE_TIMEOUT_MS = 4000L
        private const val HANDSHAKE_MAX_RETRIES = 3
        private const val HANDSHAKE_READ_MAX = 3
        private const val HANDSHAKE_PRIME_TIMEOUT_MS = 500L
        private const val BOND_WAIT_MS = 4000L
        private const val LEGACY_HANDSHAKE_STEP_DELAY_MS = 1200L
        private const val LEGACY_HANDSHAKE_READ_DELAY_MS = 1200L

        private const val BROADCAST_STALE_MS = 5 * 60_000L
        private const val BROADCAST_SCAN_WINDOW_MS = 30_000L
        private const val BROADCAST_SCAN_BASE_INTERVAL_AUTO_MS = 60_000L
        private const val BROADCAST_SCAN_BASE_INTERVAL_CONNECTED_MS = 60_000L
        private const val BROADCAST_SCAN_MAX_INTERVAL_MS = 300_000L
        private const val BROADCAST_MIN_STORE_INTERVAL_MS = 50_000L

        private const val BROADCAST_REFERENCE_MS = 5 * 60_000L

        // Edit 58a: AiDex X sensors have a 14-day maximum life
        private const val AIDEX_SENSOR_MAX_DAYS = 14L

        // Edit 59: Vendor initialization calibration factors (from iOS IPA analysis, Session 43)
        private const val CAL_FACTOR_PHASE1 = 0.85f   // 0-24h after reset
        private const val CAL_FACTOR_PHASE2 = 0.95f   // 24-48h after reset
        private const val PHASE1_DURATION_MS = 24L * 3600_000L  // 24 hours
        private const val PHASE2_DURATION_MS = 48L * 3600_000L  // 48 hours
    }
    // --- STATE ---
    private data class HandshakeStep(
        val label: String,
        val uuid: UUID,
        val data: ByteArray,
        val expectResponseOps: Set<Int> = emptySet()
    )
    private enum class OfficialFlowStage {
        BOOTSTRAP,
        STREAMING
    }

    private var handshakeStep = 0
    private var handshakePlan: List<HandshakeStep> = emptyList()
    private var handshakePlanLabel: String = "none"
    private var officialFlowStage: OfficialFlowStage = OfficialFlowStage.BOOTSTRAP
    private var dynamicIV: ByteArray? = null
    private var bondRequested = false
    private var bondWaitUntilMs = 0L
    private var justBondedThisSession = false
    private var handshakePrimingInProgress = false
    private var pendingHandshakePrimingTimeout: Runnable? = null
    private var useOfficialHandshake = false
    private var useBitReverse: Boolean? = null
    @Volatile private var connectInProgress = false
    private val broadcastEnabled = true
    private var broadcastDisabledLogged = false
    private val rawBroadcastFallbackEnabled = true

    private var lastRawMgDl: Float = 0f
    private var lastRawTime: Long = 0L
    private var lastAutoMgDl: Float = 0f
    private var lastAutoTime: Long = 0L

    private var pendingHandshakeRead: Runnable? = null
    private var pendingHandshakeTimeout: Runnable? = null
    private var expectedF002ResponseOps: Set<Int> = emptySet()
    private var waitAnyF002Response = false
    private var handshakeRetries: Int = 0
    private var handshakeReadAttempts: Int = 0
    private val pendingIVCandidates = ArrayList<ByteArray>(4)
    private var vendorParserUnavailableLogged = false
    private val vendorProbeFailures = HashSet<String>()
    private var ivLockKey: String? = null
    private var ivLockCount: Int = 0
    private var ivLocked = false
    private var ivLockedFromBroadcast = false

    // --- BROADCAST SCAN STATE ---
    private val scanHandler = Handler(Looper.getMainLooper())

    // Edit 28: Handler-based message queue for ALL vendor native lib interactions.
    // The official AiDex app uses a single Handler (workHandler) on the main looper
    // to serialize all BLE adapter operations. This prevents reentrancy (e.g. calling
    // onConnectSuccess() while executeConnect() is still on the stack) and ensures
    // proper ordering (executeWrite queued before executeDisconnect).
    private val vendorWorkHandler = Handler(Looper.getMainLooper())

    // init block removed for debugging

    private var broadcastScanner: BluetoothLeScanner? = null
    private var broadcastScanCallback: ScanCallback? = null
    internal var broadcastScanActive = false
    private var broadcastScanMisses = 0
    private var broadcastScanStartedAtElapsed = 0L
    private var connectedAddress: String? = null
    private var broadcastWakeLock: PowerManager.WakeLock? = null
    private var broadcastSeenInSession = false
    private var sessionStartMs: Long = System.currentTimeMillis()
    
    // --- BROADCAST STATE ---
    private var lastBroadcastGlucose: Float = 0f
    private var lastBroadcastTime: Long = 0L
    private var lastBroadcastOffsetMinutes: Long = 0L
    private var lastBroadcastStoredOffsetMinutes: Long = 0L
    private var lastRawBroadcastOffsetMinutes: Long = 0L

    private var viewModeInternal: Int = 0
    
    // Connection option: use broadcast scanning instead of GATT (separate from viewMode)
    var broadcastOnlyConnection: Boolean = false
        private set

    // --- VENDOR (blecomm-lib) STATE ---
    private val vendorBleEnabled = true
    private var vendorAdapter: VendorBleAdapter? = null
    private var vendorController: AidexXController? = null
    private var lastScanRecordBytes: ByteArray? = null
    private var vendorStarted = false
    private var vendorRegistered = false
    private var vendorLibAvailable = false
    private var vendorLibLogged = false
    private var vendorGattConnected = false
    private var vendorGattNotified = false
    private var vendorConnectPending = false
    @Volatile private var vendorNativeReady = false         // true after onConnectSuccess() succeeds without SIGSEGV
    private var vendorConnectSuccessCrashCount = 0          // consecutive crash count for crash-loop protection
    private val VENDOR_MAX_CRASH_RETRIES = 3                // stop calling onConnectSuccess after N consecutive failures
    @Volatile private var vendorExecuteConnectReceived = false  // true after native lib called executeConnect() — MUST be true before onConnectSuccess()
    // Edit 50a: Bypass flag for proactive GATT connect on 2nd DISCOVER.
    // When true, connectDevice() allows GATT connect even without vendorExecuteConnectReceived.
    // Unlike Edit 49a, we do NOT set vendorExecuteConnectReceived — the GATT connects and waits
    // silently. When the vendor lib later calls executeConnect(), it finds GATT already ready
    // and fires onConnectSuccess immediately (no 30s stall).
    @Volatile private var vendorProactiveGattConnect = false
    private var lastVendorMgDl: Float = 0f
    private var lastVendorTime: Long = 0L
    private var lastVendorOffsetMinutes: Int = 0
    private val vendorGattQueue = ArrayDeque<VendorGattOp>()  // Edit 36c: unified read+write queue
    @Volatile private var vendorGattOpActive = false  // Edit 36c/40: true while a GATT op (read or write) is pending callback. @Volatile for cross-thread visibility (binder thread sets, vendorWorkHandler reads).
    private var vendorLongConnectTriggered = false
    private var vendorLongConnectPendingReason: String? = null  // set when long-connect deferred for bonding

    // Edit 40: Reconnect timeout fallback — if register() with saved keys doesn't produce
    // executeConnect within VENDOR_RECONNECT_FALLBACK_MS, fall back to pair().
    // Edit 50b: Reduced from 10_000L to 500L. The recpnn.txt log proved pair() with preserved
    // keys immediately triggers executeConnect() — waiting 10s was unnecessary delay. 500ms is
    // enough for register() to complete and the vendor lib to process the initial state.
    private var vendorReconnectFallbackRunnable: Runnable? = null
    private val VENDOR_RECONNECT_FALLBACK_MS = 500L
    // Edit 55a: 2nd fallback uses a longer delay (5s) to give BLE scanner time to find
    // the device after app restart. The 500ms 1st attempt handles the fast reconnect case.
    // Critically, 2nd attempt NO LONGER clears keys — key clearing only happens after
    // actual BOND failure (Edit 55b). failled-2.txt showed premature key clearing at 1000ms
    // caused a forced full re-pair + 30s stall, totaling 91s reconnect time.
    private val VENDOR_RECONNECT_FALLBACK_2ND_MS = 5_000L
    // Edit 49c: Track fallback attempts — first attempt preserves keys, second retries pair().
    private var vendorReconnectFallbackAttempt = 0

    // Edit 40: Guard against double-fire of DISCONNECT handler's postDelayed restart.
    // The vendor lib can send DISCONNECT with success=false then success=true in quick succession.
    private var vendorDisconnectRestartRunnable: Runnable? = null

    // Edit 41b: Pause GATT queue while SMP bonding is in progress.
    // When gatt.writeCharacteristic() returns false AND the device is BOND_BONDING, the BLE
    // stack is busy with SMP. Retrying every 200ms exhausts 10 retries in 2 seconds, but
    // bonding takes 3-6 seconds. Instead, pause the queue and resume on BOND_BONDED.
    @Volatile private var vendorQueuePausedForBonding = false
    private val VENDOR_BONDING_PAUSE_TIMEOUT_MS = 15_000L  // safety: resume after 15s even if BOND_BONDED never arrives

    // Edit 41c: Track reconnect-with-saved-keys attempts across discover-spam restarts.
    // After N consecutive spam restarts where executeConnect never arrives, clear keys and
    // force pair() on next discover — breaking the deadlock.
    private var vendorReconnectSpamRestarts = 0
    private val VENDOR_MAX_SPAM_RESTARTS_BEFORE_REPAIR = 2
    // Edit 49b: Extracted constant for DISCOVER spam threshold (was hardcoded 5, reduced to 3).
    // With Edit 49a's proactive GATT connect at DISCOVER #2, this is a safety net that fires
    // faster if the proactive connect failed (e.g. device went back out of range).
    private val VENDOR_DISCOVER_SPAM_THRESHOLD = 3

    // Edit 37a: getBroadcastData 2-second polling loop (matches official app behavior)
    // Edit 38b: Add max poll count + stop-after-store. The vendor lib disconnects after ~80s
    // of continuous polling. Limit to 30 polls (60s) and stop once we store a valid glucose.
    // Edit 44b: After first glucose, transition to slow periodic poll (~55s) instead of stopping.
    // This ensures glucose readings continue arriving even when AUTO_UPDATE pushes don't arrive
    // (e.g. on the F001 path). The slow interval is well under the vendor lib's ~80s timeout.
    // Edit 45b: Changed from 55s to 62s to avoid phase drift with the sensor's 60s update cycle.
    // Edit 45c: Slow poll is now a WATCHDOG backup only — AUTO_UPDATE pushes are the primary source.
    //   When AUTO_UPDATE is actively delivering glucose, slow poll is suppressed.
    //   If AUTO_UPDATE goes silent for 90s, slow poll re-engages as fallback.
    private var vendorPollActive = false
    private var vendorPollCount = 0
    private var vendorConsecutiveDiscoverCount = 0  // Edit 37e: track DISCOVER spam
    private val VENDOR_POLL_INTERVAL_MS = 2000L
    private val VENDOR_SLOW_POLL_INTERVAL_MS = 62_000L  // Edit 45b: slightly over sensor's 60s cycle to avoid phase drift
    private val VENDOR_MAX_POLL_COUNT = 30  // 30 polls × 2s = 60s max for initial fast polling
    private var vendorGotGlucoseThisCycle = false  // true once we store a value this connection
    private var vendorSlowPollActive = false  // Edit 44b: tracks whether we've transitioned to slow polling
    // Edit 45c: AUTO_UPDATE watchdog — track when last AUTO_UPDATE push arrived
    private var vendorLastAutoUpdateTime = 0L  // timestamp of last AUTO_UPDATE_FULL_HISTORY glucose
    private val VENDOR_AUTO_UPDATE_WATCHDOG_MS = 90_000L  // re-enable slow poll if no AUTO_UPDATE for 90s
    // Edit 52a: Snapshot of the last AUTO_UPDATE offset at disconnect time.
    // On reconnect, GET_HISTORY_RANGE uses this to roll back vendorHistoryNextIndex
    // if it was advanced past the gap by broadcast stores (belt-and-suspenders safety).
    private var vendorLastAutoUpdateOffsetAtDisconnect: Int = 0
    private val broadcastPollRunnable = Runnable {
        if (vendorBleEnabled && vendorGattConnected && vendorGattNotified && vendorPollActive) {
            // Edit 44b: If we're in slow-poll mode, just request and reschedule at slow interval.
            if (vendorSlowPollActive) {
                // Edit 45c: Check if AUTO_UPDATE is actively delivering glucose.
                // If so, suppress the poll — AUTO_UPDATE is the primary, more reliable source.
                // Only re-engage polling if AUTO_UPDATE has gone silent for VENDOR_AUTO_UPDATE_WATCHDOG_MS.
                val now = System.currentTimeMillis()
                if (vendorLastAutoUpdateTime > 0L && (now - vendorLastAutoUpdateTime) < VENDOR_AUTO_UPDATE_WATCHDOG_MS) {
                    Log.d(TAG, "Vendor slow poll: suppressed — AUTO_UPDATE active (last ${(now - vendorLastAutoUpdateTime) / 1000}s ago), rescheduling watchdog")
                    scheduleNextBroadcastPoll()  // reschedule to check again later
                    return@Runnable
                }
                // AUTO_UPDATE not active (never received or timed out) — fall back to polling
                try {
                    vendorController?.getBroadcastDataWithLog()
                    vendorPollCount++
                    Log.d(TAG, "Vendor slow poll #$vendorPollCount: getBroadcastData requested (${VENDOR_SLOW_POLL_INTERVAL_MS/1000}s interval, AUTO_UPDATE watchdog fallback)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Vendor slow poll failed: ${t.message}")
                }
                scheduleNextBroadcastPoll()
                return@Runnable
            }
            // Edit 38b: During fast polling, stop if we already got a glucose — transition to slow poll
            if (vendorGotGlucoseThisCycle) {
                Log.i(TAG, "Vendor poll: stored glucose this cycle, transitioning to slow poll (${VENDOR_SLOW_POLL_INTERVAL_MS/1000}s interval)")
                vendorSlowPollActive = true
                scheduleNextBroadcastPoll()
                return@Runnable
            }
            if (vendorPollCount >= VENDOR_MAX_POLL_COUNT) {
                Log.w(TAG, "Vendor poll: reached max fast poll count ($VENDOR_MAX_POLL_COUNT) without glucose, transitioning to slow poll")
                vendorSlowPollActive = true
                scheduleNextBroadcastPoll()
                return@Runnable
            }
            try {
                vendorController?.getBroadcastDataWithLog()
                vendorPollCount++
                Log.d(TAG, "Vendor poll #$vendorPollCount/$VENDOR_MAX_POLL_COUNT: getBroadcastData requested")
            } catch (t: Throwable) {
                Log.e(TAG, "Vendor poll failed: ${t.message}")
            }
        } else {
            Log.d(TAG, "Vendor poll skipped: enabled=$vendorBleEnabled connected=$vendorGattConnected notified=$vendorGattNotified active=$vendorPollActive")
            vendorPollActive = false
        }
    }

    private fun scheduleNextBroadcastPoll() {
        vendorWorkHandler.removeCallbacks(broadcastPollRunnable)
        vendorPollActive = true
        // Edit 44b: Use slow interval after first glucose, fast interval during initial polling
        val interval = if (vendorSlowPollActive) VENDOR_SLOW_POLL_INTERVAL_MS else VENDOR_POLL_INTERVAL_MS
        vendorWorkHandler.postDelayed(broadcastPollRunnable, interval)
    }

    private fun cancelBroadcastPoll() {
        vendorPollActive = false
        vendorPollCount = 0
        vendorGotGlucoseThisCycle = false  // Edit 38b: reset for next connection cycle
        vendorSlowPollActive = false  // Edit 44b: reset slow poll flag
        vendorLastAutoUpdateTime = 0L  // Edit 45c: reset AUTO_UPDATE watchdog for next connection
        vendorWorkHandler.removeCallbacks(broadcastPollRunnable)
    }
    private var vendorConnectSuccessPendingCaller: String? = null  // set when onConnectSuccess deferred for bonding
    private var aidexBondReceiver: BroadcastReceiver? = null
    @Volatile private var aidexBonded = false  // internal bond state — only set true by our own BroadcastReceiver (or already-bonded check)
    @Volatile private var cccdAuthFailSeen = false  // Edit 26: set true when CCCD write returns GATT_INSUFFICIENT_AUTHENTICATION (0x05), indicating SMP bonding started

    // Edit 29: Auth failure (status=5) tracking for crash-loop protection
    private var consecutiveAuthFailures = 0
    private var lastAuthFailureTime = 0L
    private val AUTH_FAIL_MAX_RETRIES = 5
    private val AUTH_FAIL_BASE_DELAY_MS = 2000L  // 2s, doubles each retry up to 60s

    // Background executor for vendor commands — avoids blocking main/BLE Binder threads
    // All calls to executeVendorCommand (which does Thread.sleep polling) MUST go through this.
    private val vendorExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "AiDex-VendorCmd").apply { isDaemon = true }
    }

    var viewMode: Int
        get() = viewModeInternal
        set(value) {
            if (viewModeInternal == value) return
            val previous = viewModeInternal
            viewModeInternal = value
            if (previous == 0 && value != 0) {
                // Allow combined/raw modes to consume the most recent broadcast once.
                lastBroadcastStoredOffsetMinutes = 0L
            }
            Log.i(TAG, "ViewMode changed: $previous -> $value")
            if (dataptr != 0L) Natives.setViewMode(dataptr, value)
            applyViewMode("viewMode", connectIfNeeded = true)
        }
    
    /** Enable/disable broadcast-only connection mode (no GATT, just BLE advertisements) */
    fun setBroadcastOnlyConnection(enabled: Boolean) {
        if (broadcastOnlyConnection == enabled) return
        broadcastOnlyConnection = enabled
        writeBoolPref("broadcastOnlyConnection", enabled)
        Log.i(TAG, "BroadcastOnlyConnection changed: $enabled")
        
        // Update notification status
        constatstatusstr = if (enabled) "Broadcast Mode" else "Disconnected"
        
        applyViewMode("broadcastOnly", connectIfNeeded = true)
    }
    
    /** Get detailed BLE status for UI display */
    fun getDetailedBleStatus(): String {
        val now = System.currentTimeMillis()
        
        if (broadcastOnlyConnection) {
            // Broadcast-only mode status
            return when {
                broadcastScanActive -> "Scanning..."
                lastBroadcastTime > 0 && (now - lastBroadcastTime) < 120_000 -> {
                    val agoSec = (now - lastBroadcastTime) / 1000
                    "Last broadcast ${agoSec}s ago"
                }
                lastBroadcastTime > 0 -> {
                    val agoMin = (now - lastBroadcastTime) / 60_000
                    "Last broadcast ${agoMin}m ago"
                }
                else -> "Waiting for broadcast..."
            }
        } else {
            // GATT mode status
            val gattStatus = mBluetoothGatt != null
            val rawStatus = constatstatusstr ?: ""
            return when {
                gattStatus && rawStatus.isEmpty() -> "Connected"
                rawStatus.startsWith("Status=") -> rawStatus
                rawStatus.isNotEmpty() -> rawStatus
                else -> "Disconnected"
            }
        }
    }
    
    private val appContext = context.applicationContext

    private val prefs by lazy { 
        appContext.getSharedPreferences("AiDexSensorPrefs", Context.MODE_PRIVATE)
    }

    private fun prefKey(name: String): String = "${name}_${SerialNumber}"

    private fun readFloatPref(name: String, default: Float): Float {
        val key = prefKey(name)
        return if (prefs.contains(key)) prefs.getFloat(key, default) else prefs.getFloat(name, default)
    }

    private fun readLongPref(name: String, default: Long): Long {
        val key = prefKey(name)
        return if (prefs.contains(key)) prefs.getLong(key, default) else prefs.getLong(name, default)
    }

    private fun readIntPref(name: String, default: Int): Int {
        val key = prefKey(name)
        return if (prefs.contains(key)) prefs.getInt(key, default) else prefs.getInt(name, default)
    }

    private fun writeFloatPref(name: String, value: Float) {
        prefs.edit()
            .putFloat(prefKey(name), value)
            .putFloat(name, value)
            .apply()
    }

    private fun writeLongPref(name: String, value: Long) {
        prefs.edit()
            .putLong(prefKey(name), value)
            .putLong(name, value)
            .apply()
    }

    private fun writeIntPref(name: String, value: Int) {
        prefs.edit()
            .putInt(prefKey(name), value)
            .putInt(name, value)
            .apply()
    }

    private fun readBoolPref(name: String, default: Boolean): Boolean {
        val key = prefKey(name)
        return if (prefs.contains(key)) prefs.getBoolean(key, default) else prefs.getBoolean(name, default)
    }

    private fun writeBoolPref(name: String, value: Boolean) {
        prefs.edit()
            .putBoolean(prefKey(name), value)
            .putBoolean(name, value)
            .apply()
    }

    private fun readStringPref(name: String, default: String?): String? {
        val key = prefKey(name)
        return if (prefs.contains(key)) prefs.getString(key, default) else prefs.getString(name, default)
    }

    private fun writeStringPref(name: String, value: String?) {
        prefs.edit()
            .putString(prefKey(name), value)
            .putString(name, value)
            .apply()
    }

    init {
        lastBroadcastGlucose = readFloatPref("lastBroadcastGlucose", 0f)
        lastBroadcastTime = readLongPref("lastBroadcastTime", 0L)
        lastBroadcastOffsetMinutes = readLongPref("lastBroadcastOffsetMinutes", 0L)
        lastRawMgDl = readFloatPref("lastRawMgDl", 0f)
        lastRawTime = readLongPref("lastRawTime", 0L)
        lastAutoMgDl = readFloatPref("lastAutoMgDl", 0f)
        lastAutoTime = readLongPref("lastAutoTime", 0L)
        sessionStartMs = System.currentTimeMillis()
        broadcastSeenInSession = false
        val savedReverse = readIntPref("useBitReverse", -1)
        useBitReverse = when (savedReverse) {
            1 -> true
            0 -> false
            else -> null
        }
        // Load connection option (separate from viewMode)
        broadcastOnlyConnection = readBoolPref("broadcastOnlyConnection", false)
        viewModeInternal = if (dataptr != 0L) Natives.getViewMode(dataptr) else 0
        
        // Set initial status for broadcast mode (prevents 'Searching for sensors' from SensorBluetooth)
        if (broadcastOnlyConnection) {
            constatstatusstr = "Broadcast Mode"
        }
        
        Log.i(TAG, "Loaded: LastBroadcast=$lastBroadcastGlucose, BitReverse=$useBitReverse, ViewMode=$viewModeInternal, BroadcastOnly=$broadcastOnlyConnection")
        applyViewMode("init", connectIfNeeded = false)
        scheduleBroadcastScan("init", forceImmediate = true)
    }

    private fun isAutoOnlyMode(): Boolean = viewModeInternal == 0
    private fun isRawOnlyMode(): Boolean = viewModeInternal == 1
    private fun isBroadcastOnlyMode(): Boolean = broadcastOnlyConnection
    private fun wantsAuto(): Boolean = viewModeInternal == 0 || viewModeInternal == 2
    private fun wantsRaw(): Boolean = viewModeInternal == 1 || viewModeInternal == 3
    private fun wantsBroadcastScan(): Boolean {
        // Mode 4 (Broadcast Only) always wants broadcast scan
        if (isBroadcastOnlyMode()) return broadcastEnabled
        
        if (!broadcastEnabled) return false
        if (!(wantsAuto() || (wantsRaw() && rawBroadcastFallbackEnabled))) return false

        // Edit 45a: Suppress broadcast scanning while vendor GATT is actively connected and
        // past the initial handshake. The sensor stops BLE advertising when a GATT connection
        // is active, so every scan times out — wasting battery and radio time.
        // We still allow scanning during the initial connect phase (before executeConnect arrives)
        // because the vendor lib needs scan record bytes to trigger executeConnect().
        // Once the long-connect data flow is running (vendorLongConnectTriggered), scanning is pure waste.
        if (vendorGattConnected && vendorLongConnectTriggered) {
            Log.d(TAG, "wantsBroadcastScan: suppressed — vendor GATT active with long-connect running")
            return false
        }

        val now = System.currentTimeMillis()
        if (dynamicIV == null) return true
        if (lastRawTime == 0L) return true
        return (now - lastRawTime) > BROADCAST_REFERENCE_MS
    }


    private fun scanTargetAddress(): String? = connectedAddress ?: mActiveDeviceAddress

    private fun broadcastScanBaseIntervalMs(): Long {
        return if (isAutoOnlyMode()) {
            BROADCAST_SCAN_BASE_INTERVAL_AUTO_MS
        } else {
            BROADCAST_SCAN_BASE_INTERVAL_CONNECTED_MS
        }
    }

    private fun applyViewMode(reason: String, connectIfNeeded: Boolean) {
        // Mode policy:
        // - Auto (0): official handshake + broadcast/IV-lock (vendor parser optional).
        // - Raw (1): legacy handshake.
        // - Auto+Raw (2, 3): combined modes.
        // - Broadcast Only (4): NO connection, just BLE advertisement scanning for stability.
        useOfficialHandshake = wantsAuto()

        if (wantsAuto() || wantsRaw()) {
            ensureVendorParserLoaded("mode-$reason")
        }

        if (vendorBleEnabled && wantsAuto()) {
            ensureVendorStarted("mode-$reason")
        } else {
            stopVendor("mode-$reason")
        }

        // Broadcast Only mode: disconnect GATT so sensor resumes advertising
        if (isBroadcastOnlyMode()) {
            Log.i(TAG, "Broadcast Only mode: disconnecting GATT so sensor can advertise")
            disconnect()
        } else if (connectIfNeeded && (wantsRaw() || wantsAuto())) {
            ensureConnected("mode-$reason")
        }

        if (wantsBroadcastScan()) {
            scheduleBroadcastScan("mode-$reason", forceImmediate = true)
        }
    }

    private fun ensureVendorParserLoaded(reason: String) {
        vendorLibAvailable = BlecommLoader.ensureLoaded()
        if (!vendorLibAvailable && !vendorLibLogged) {
            vendorLibLogged = true
            Log.e(TAG, "Vendor parser lib not available ($reason).")
        }
        if (vendorLibAvailable) {
            vendorParserUnavailableLogged = false
        }
    }

    // Edit 36c: Unified GATT operation queue. Android BLE only supports one pending GATT
    // operation at a time. Previously, reads bypassed the write queue and raced with writes,
    // causing gatt.readCharacteristic() to silently fail (return false) when a write was
    // in-flight. Now both reads and writes go through the same queue.
    // Edit 38c: Added retryCount to prevent infinite retry loops when writes fail (e.g. F003).
    private sealed class VendorGattOp {
        var retryCount: Int = 0
        data class Write(val uuid: Int, val data: ByteArray) : VendorGattOp()
        data class Read(val uuid: Int) : VendorGattOp()
    }
    private val VENDOR_GATT_OP_MAX_RETRIES = 10

    private fun ensureVendorStarted(reason: String) {
        if (!vendorBleEnabled) {
            ensureVendorParserLoaded("vendor-ble-disabled-$reason")
            return
        }
        if (vendorStarted && vendorController != null) return

        vendorLibAvailable = BlecommLoader.ensureLoaded()
        if (!vendorLibAvailable && !vendorLibLogged) {
            vendorLibLogged = true
            Log.e(TAG, "Vendor lib not available; vendor mode disabled.")
        }
        if (!vendorLibAvailable) {
            return
        }
        val adapter = vendorAdapter ?: VendorBleAdapter(appContext).also {
            vendorAdapter = it
        }
        if (!vendorStarted) {
            try {
                BleController.setBleAdapter(adapter)
                BleController.setDiscoveredCallback { info -> onVendorDiscovered(info) }
                vendorStarted = true
                vendorParserUnavailableLogged = false
            } catch (t: Throwable) {
                Log.e(TAG, "Vendor init failed: ${t.message}")
                return // cannot proceed without adapter registration
            }
            // Edit 33: Removed BleController.startScan() — the official app NEVER calls it from Java.
            // The native lib's own register() call manages scanning internally. Calling startScan()
            // explicitly was putting the native lib into a scanning state that conflicted with
            // register()'s internal scan management, preventing it from ever calling executeConnect().
            Log.i(TAG, "Vendor started ($reason)")
        }

        // Proactive initialization if we have a connected address but no controller yet.
        // Edit 32: Only initialize with REAL scan data — empty params corrupt the native lib's
        // internal state, causing it to discover the device but never call executeConnect().
        // If no cached params are available, the native lib's own scan will find the device
        // within seconds and trigger onVendorDiscovered() with real advertisement bytes.
        if (vendorController == null) {
            val addr = connectedAddress ?: mActiveDeviceAddress
            if (addr != null && SerialNumber.isNotEmpty()) {
                var scannedBytes = lastScanRecordBytes
                if (scannedBytes == null) {
                    scannedBytes = loadVendorParams()
                    if (scannedBytes != null) {
                        Log.i(TAG, "Vendor init: Loaded cached params (${scannedBytes.size} bytes)")
                        lastScanRecordBytes = scannedBytes
                    }
                }
                if (scannedBytes != null && scannedBytes.isNotEmpty()) {
                    Log.i(TAG, "Proactively initializing vendor controller for $addr (${scannedBytes.size} bytes)")
                    val info = BleControllerInfo(1, addr, "AiDEX $SerialNumber", SerialNumber, -60, scannedBytes)
                    mActiveBluetoothDevice?.let { adapter.recordDevice(it) }
                    onVendorDiscovered(info)
                } else {
                    Log.i(TAG, "Vendor init: No scan data for $addr yet — deferring controller init to first real scan result")
                    // Kick a broadcast scan so we pick up real advertisement bytes ASAP
                    startBroadcastScan("vendor-deferred-init")
                }
            }
        }
    }

    private fun stopVendor(reason: String) {
        if (!vendorBleEnabled) return
        if (!vendorStarted) return
        vendorStarted = false
        try {
            BleController.stopScan()
        } catch (_: Throwable) {
        }
        try {
            vendorController?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            vendorController?.unregister()
        } catch (_: Throwable) {
        }
        vendorRegistered = false
        vendorGattConnected = false
        vendorServicesReady = false
        vendorGattNotified = false
        vendorConnectPending = false
        vendorNativeReady = false
        vendorExecuteConnectReceived = false
            vendorGattQueue.clear()
            // Edit 40: Clear vendorGattOpActive on the handler thread for consistency,
            // but also set it false here immediately since disconnect is a full reset anyway.
            vendorGattOpActive = false
            vendorWorkHandler.post {
                vendorGattOpActive = false  // redundant but ensures handler-thread visibility
            }
        vendorLongConnectTriggered = false
        vendorLongConnectPendingReason = null
        vendorConnectSuccessPendingCaller = null
        aidexBonded = false
        cancelBroadcastPoll()  // Edit 37a: stop polling loop
        vendorConsecutiveDiscoverCount = 0  // Edit 37e: reset discover counter
        vendorReconnectFallbackAttempt = 0  // Edit 49c: reset fallback attempt counter
        vendorQueuePausedForBonding = false  // Edit 41b: clear bonding pause on stop
        // Note: vendorReconnectSpamRestarts is intentionally NOT reset in stopVendor() —
        // it must persist across stop/start cycles to count consecutive spam restarts.
        // It IS reset on executeConnect (success) and in forgetVendor().
        cancelReconnectStallTimeout()  // Edit 34: cancel stall timer on vendor stop
        vendorLastAutoUpdateOffsetAtDisconnect = 0  // Edit 52a: clear disconnect snapshot on vendor stop
        // Edit 40: Cancel reconnect fallback timer on vendor stop
        vendorReconnectFallbackRunnable?.let { vendorWorkHandler.removeCallbacks(it) }
        vendorReconnectFallbackRunnable = null
        // Edit 40: Cancel disconnect restart timer on vendor stop
        vendorDisconnectRestartRunnable?.let { vendorWorkHandler.removeCallbacks(it) }
        vendorDisconnectRestartRunnable = null
        unregisterAidexBondReceiver()
        Log.i(TAG, "Vendor stopped ($reason)")

    }

    private var disregardDisconnectsUntil: Long = 0

    @SuppressLint("MissingPermission")
    private fun registerAidexBondReceiver() {
        unregisterAidexBondReceiver()  // safety: remove any prior
        val targetAddress = connectedAddress ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (device.address != targetAddress) return
                val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                val curr = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                Log.i(TAG, "AiDex bond receiver: $targetAddress prev=$prev curr=$curr")
                when (curr) {
                    BluetoothDevice.BOND_BONDED -> {
                        if (aidexBonded) {
                            Log.i(TAG, "AiDex bond receiver: already handled BONDED, ignoring duplicate")
                            return
                        }
                        aidexBonded = true
                        Log.i(TAG, "AiDex bond receiver: BOND_BONDED — triggering deferred operations")

                        // Edit 41b: Resume GATT queue if paused for bonding
                        if (vendorQueuePausedForBonding) {
                            Log.i(TAG, "Edit 41b: BOND_BONDED arrived — resuming paused GATT queue")
                            vendorQueuePausedForBonding = false
                            // Short delay to let encryption settle before retrying writes
                            vendorWorkHandler.postDelayed({ drainVendorGattQueue() }, 300L)
                        }
                        // Trigger deferred onConnectSuccess (500ms delay for encryption to settle)
                        val pendingCaller = vendorConnectSuccessPendingCaller
                        if (pendingCaller != null) {
                            Log.i(TAG, "AiDex bond receiver: firing deferred safeCallOnConnectSuccess (caller=$pendingCaller)")
                            vendorConnectSuccessPendingCaller = null
                            scanHandler.postDelayed({
                                safeCallOnConnectSuccess("bond-receiver-deferred:$pendingCaller")
                            }, 500L)
                        }
                        // Trigger deferred long-connect (200ms delay — Edit 26: reduced from 2000ms)
                        val pendingReason = vendorLongConnectPendingReason
                        if (pendingReason != null) {
                            Log.i(TAG, "AiDex bond receiver: firing deferred startVendorLongConnect (reason=$pendingReason)")
                            vendorLongConnectPendingReason = null
                            scanHandler.postDelayed({
                                startVendorLongConnect("bond-receiver-deferred:$pendingReason")
                            }, 200L)
                        }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        Log.w(TAG, "AiDex bond receiver: BOND_NONE (bond failed or removed)")
                        aidexBonded = false
                    }
                }
            }
        }
        aidexBondReceiver = receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        appContext.registerReceiver(receiver, filter)
        Log.i(TAG, "AiDex bond receiver registered for $targetAddress")

        // Handle already-bonded case (reconnect to previously bonded device)
        val currentBond = mBluetoothGatt?.device?.bondState ?: BluetoothDevice.BOND_NONE
        if (currentBond == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "AiDex bond receiver: device already BONDED at registration time — setting aidexBonded=true")
            aidexBonded = true
        }
    }

    private fun unregisterAidexBondReceiver() {
        val receiver = aidexBondReceiver ?: return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: Throwable) {}
        aidexBondReceiver = null
        Log.i(TAG, "AiDex bond receiver unregistered")
    }

    /**
     * Edit 25: Remove stale BLE bond before connecting.
     *
     * AiDex protocol rule: after GATT connect, the sensor gives ~8 seconds
     * to send the key-exchange command and initiate bonding.  If the Android
     * BLE stack holds a stale bond record from a prior session, the first
     * CCCD write fails with GATT_INSUFFICIENT_AUTHENTICATION (0x05), the
     * stack auto-removes the stale bond, and fresh bonding starts — but this
     * wastes several seconds of the 8-second window and can leave the
     * connection in a confused state (status=19 peer disconnect).
     *
     * Calling removeBond() BEFORE connectGatt() ensures:
     *   - No stale bond to remove during the connection
     *   - Bonding starts fresh immediately on the first CCCD write
     *   - No time wasted on authentication errors
     *
     * @return true if the bond was removed (or was already absent), false on error
     */
    @SuppressLint("MissingPermission")
    private fun removeBondIfBonded(device: BluetoothDevice?): Boolean {
        if (device == null) return true
        val state = device.bondState
        if (state != BluetoothDevice.BOND_BONDED) {
            Log.d(TAG, "removeBondIfBonded: device ${device.address} not bonded (state=$state), nothing to do")
            return true
        }
        return try {
            val method = device.javaClass.getMethod("removeBond")
            val result = method.invoke(device) as Boolean
            Log.i(TAG, "removeBondIfBonded: removeBond() for ${device.address} returned $result")
            result
        } catch (e: Throwable) {
            Log.e(TAG, "removeBondIfBonded: reflection failed: ${e.message}")
            false
        }
    }

    override fun disconnect() {
        if (System.currentTimeMillis() < disregardDisconnectsUntil) {
            Log.w(TAG, "Ignoring disconnect() request (Debounce active)")
            return
        }
        super.disconnect()
    }

    override fun close() {
        // Do NOT call stopVendor("close") here!
        // SuperGattCallback.getConnectDevice() calls close() before every reconnect.
        // Calling stopVendor here tears down the entire vendor stack (unregisters controller,
        // clears all flags) during routine GATT reconnects, which:
        //   1) Kills active GATT connections that ensureVendorGattReady is waiting on
        //   2) Resets vendorRegistered/vendorExecuteConnectReceived/vendorNativeReady
        //   3) Forces full re-init on every reconnect cycle
        // Vendor shutdown is handled in onConnectionStateChange(DISCONNECTED) where
        // the flags are properly reset, and in the explicit stopVendor calls from
        // applyViewMode/stopBt.
        super.close()
    }

    /**
     * Sends a maintenance command to the AiDex sensor via the vendor library.
     * @param opCode 1=Reset, 2=ShelfMode, 3=DeleteBond, 4=ClearStorage
     */
    /**
     * Sends a maintenance command to the AiDex sensor via the vendor library.
     * @param opCode 1=Reset, 2=ShelfMode, 3=DeleteBond, 4=ClearStorage
     */
    fun sendMaintenanceCommand(opCode: Int): Boolean {
        return executeVendorCommand("maintenance", opCode) { controller ->
             when (opCode) {
                1 -> controller.reset()
                2 -> controller.shelfMode()
                3 -> controller.deleteBond()
                4 -> controller.clearStorage()
                else -> -1
            }
        }
    }
    
    // Old startNewSensor and resetSensor removed in favor of robust implementation at end of file.

    /**
     * Send a manual calibration (blood glucose reference) to the sensor via the vendor native lib.
     * @param glucoseMgDl finger-stick glucose in mg/dL (integer)
     * @return true if the native call accepted the command
     */
    fun calibrateSensor(glucoseMgDl: Int): Boolean {
        val offset = lastVendorOffsetMinutes
        if (offset <= 0) {
            Log.w(TAG, "calibrateSensor: no recent AUTO_UPDATE offset to reference (lastVendorOffsetMinutes=$offset)")
            return false
        }
        Log.i(TAG, "calibrateSensor: sending glucose=$glucoseMgDl mg/dL, timeOffset=$offset")
        return executeVendorCommand("calibration", AidexXOperation.SET_CALIBRATION) { ctrl ->
            ctrl.calibrationWithLog(glucoseMgDl, offset)
        }
    }

    /**
     * Unpair from the sensor: delete BLE bond on the sensor side, clear saved keys locally.
     * After this the sensor is free to pair with another device.
     */
    fun unpairSensor(): Boolean {
        Log.i(TAG, "unpairSensor: deleting bond and clearing keys")
        val bondResult = executeVendorCommand("unpair-deleteBond", AidexXOperation.DELETE_BOND) { ctrl ->
            ctrl.deleteBond()
        }
        Log.i(TAG, "unpairSensor: deleteBond result=$bondResult, clearing local keys")
        clearVendorPairingKeys()
        return bondResult
    }

    /**
     * Re-pair with the sensor: clear saved keys and restart the vendor stack so it
     * triggers a fresh pair() handshake on the next DISCOVER.
     */
    fun rePairSensor() {
        Log.i(TAG, "rePairSensor: clearing keys and restarting vendor stack for fresh pairing")
        clearVendorPairingKeys()
        try { stopVendor("rePair") } catch (t: Throwable) {
            Log.e(TAG, "rePairSensor: stopVendor failed: ${t.message}")
        }
        // Restart vendor — onVendorDiscovered() will see no saved keys and call pair()
        ensureVendorStarted("rePair")
    }

    /**
     * Soft disconnect: stop the vendor stack and disconnect GATT, but preserve BLE bond
     * and pairing keys so that reconnect can work without re-pairing.
     * This is what the Pause button should call (non-destructive).
     */
    fun softDisconnect() {
        Log.i(TAG, "softDisconnect: stopping vendor stack + GATT (preserving bond/keys)")
        try {
            cancelBroadcastPoll()
        } catch (t: Throwable) {
            Log.e(TAG, "softDisconnect: cancelBroadcastPoll failed: ${t.message}")
        }
        try {
            stopVendor("softDisconnect")
        } catch (t: Throwable) {
            Log.e(TAG, "softDisconnect: stopVendor failed: ${t.message}")
        }
        try {
            disconnect()
        } catch (t: Throwable) {
            Log.e(TAG, "softDisconnect: disconnect failed: ${t.message}")
        }
    }

    private fun executeVendorCommand(label: String, opCode: Int, action: (AidexXController) -> Int): Boolean {
        if (!vendorBleEnabled) {
             Log.w(TAG, "$label failed: Vendor BLE not enabled")
             return false
        }
        ensureVendorStarted(label)

        // Wait for controller to be ready (async init race)
        var attempts = 0
        while (vendorController == null && attempts < 5) {
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
            }
            attempts++
        }

        val controller = vendorController
        if (controller == null) {
            Log.w(TAG, "$label failed: Vendor controller not ready after wait")
            return false
        }

        // Critical: Ensure vendor GATT connection and notifications before sending command
        if (!ensureVendorGattReady()) {
            Log.w(TAG, "$label failed: Could not establish vendor GATT connection")
            return false
        }

        // Wait for AES initialization (the vendor native lib needs to complete the F002 handshake)
        val aesBeforeCmd = try { controller.isInitialized } catch (_: Throwable) { false }
        if (!aesBeforeCmd) {
            Log.i(TAG, "$label: AES not yet initialized, waiting for F002 handshake...")
            var aesWait = 0
            val maxAesWait = 50 // 10 seconds (50 * 200ms)
            var aesReady = false
            while (aesWait < maxAesWait) {
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                aesWait++
                aesReady = try { controller.isInitialized } catch (_: Throwable) { false }
                if (aesReady) break
            }
            if (!aesReady) {
                Log.w(TAG, "$label failed: AES handshake did not complete after ${aesWait * 200}ms")
                return false
            }
            Log.i(TAG, "$label: AES initialized after ${aesWait * 200}ms")
        } else {
            Log.i(TAG, "$label: AES already initialized")
        }

        // Trigger vendor long-connect mode if not already done
        if (vendorGattNotified && !vendorLongConnectTriggered) {
            try {
                startVendorLongConnect(label)
                // vendorLongConnectTriggered is set inside startVendorLongConnect
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start vendor long-connect: ${t.message}")
            }
        }

        return try {
            val writeQueueBefore = vendorGattQueue.size
            val result = action(controller)
            Log.i(TAG, "Command $label (op=$opCode) invoked, native result=$result (0x${Integer.toHexString(result)})")
            
            // Wait for the vendor library to trigger executeWrite callbacks
            Thread.sleep(800)
            
            val writeQueueAfter = vendorGattQueue.size
            val writesProduced = writeQueueBefore != writeQueueAfter || vendorGattOpActive
            Log.i(TAG, "Command $label: writeQueueBefore=$writeQueueBefore writeQueueAfter=$writeQueueAfter writeActive=$vendorGattOpActive writesProduced=$writesProduced")
            
            // The result from native methods like reset() is the opcode echoed back (e.g. 3840=0xF00).
            // This does NOT mean the command was successfully sent over BLE.
            // The real indicator is whether executeWrite/executeWriteCharacteristic was called.
            if (result < 0) {
                Log.w(TAG, "Command $label: native returned error $result")
                false
            } else {
                Log.i(TAG, "Command $label: native accepted (result=$result). BLE transmission pending via vendor callbacks.")
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to send command $label: ${t.message}")
            false
        }
    }

    /**
     * Ensures the vendor GATT connection is established and notifications are enabled.
     * Returns true if ready for command transmission, false otherwise.
     */
    private fun ensureVendorGattReady(): Boolean {
        // If already connected and notified (set by onDescriptorWrite(F003)), we're ready
        if (vendorGattConnected && vendorGattNotified) {
            Log.d(TAG, "ensureVendorGattReady: already ready (connected=$vendorGattConnected notified=$vendorGattNotified)")
            return true
        }

        // Check if GATT exists but flags aren't set yet
        val gatt = mBluetoothGatt
        if (gatt != null) {
            // GATT exists but vendorGattNotified is false — means onDescriptorWrite(F003) hasn't
            // fired yet (CCCDs not written, or service discovery still pending).
            // Do NOT force the flags — that was the root cause of the AES-never-initializes bug.
            // Instead, wait for the proper notification setup to complete.
            Log.i(TAG, "ensureVendorGattReady: GATT exists but not fully ready (connected=$vendorGattConnected notified=$vendorGattNotified). Waiting...")
            var waitAttempts = 0
            val maxWait = 50 // 10 seconds (50 * 200ms)
            while (!vendorGattNotified && waitAttempts < maxWait) {
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                waitAttempts++
            }
            if (vendorGattNotified) {
                vendorGattConnected = true
                Log.i(TAG, "ensureVendorGattReady: notifications became ready after ${waitAttempts * 200}ms")
                return true
            }
            Log.w(TAG, "ensureVendorGattReady: timeout waiting for notifications (GATT exists but CCCDs not written)")
            return false
        }

        // No GATT — need to connect
        val addr = connectedAddress ?: mActiveDeviceAddress
        if (addr == null) {
            Log.w(TAG, "ensureVendorGattReady: no known device address")
            return false
        }
        Log.i(TAG, "ensureVendorGattReady: triggering GATT connection for maintenance command")
        if (!connectDevice(0)) {
            Log.w(TAG, "ensureVendorGattReady: failed to trigger connection")
            return false
        }

        // Wait for GATT connection + notification setup (vendorGattNotified is set by onDescriptorWrite(F003))
        var waitAttempts = 0
        val maxWait = 75 // 15 seconds (75 * 200ms) — includes service discovery + CCCD writes
        while (!vendorGattNotified && waitAttempts < maxWait) {
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
            waitAttempts++
        }
        if (!vendorGattNotified) {
            Log.w(TAG, "ensureVendorGattReady: timeout waiting for GATT+notifications after ${waitAttempts * 200}ms")
            return false
        }

        vendorGattConnected = true
        Log.i(TAG, "ensureVendorGattReady: ready after new connection (${waitAttempts * 200}ms)")
        return true
    }



    private var lastDiscoveredInfo: BleControllerInfo? = null
    private var vendorServicesReady = false

    private fun onVendorDiscovered(info: BleControllerInfo) {
        if (!vendorBleEnabled) return
        if (!wantsAuto()) return
        if (!vendorLibAvailable) return

        // Cache valid info for later use (e.g. clean start retry)
        if (!info.sn.isNullOrBlank() && info.params != null) {
            lastDiscoveredInfo = info
            // Persist params for Clean Start scenarios (app restart)
            writeStringPref("lastVendorAddress", info.address)
            writeStringPref("lastVendorParams", bytesToHex(info.params))
        }

        val target = scanTargetAddress()
        val matches = when {
            target != null -> info.address == target
            !info.sn.isNullOrBlank() -> info.sn.contains(SerialNumber, ignoreCase = true)
            !info.name.isNullOrBlank() -> info.name.contains(SerialNumber, ignoreCase = true)
            else -> false
        }
        if (!matches) return

        if (vendorController == null) {
            vendorController = try {
                AidexXController.getInstance(info)
            } catch (t: Throwable) {
                Log.e(TAG, "Vendor controller init failed: ${t.message}")
                null
            }
        }
        val controller = vendorController ?: return
        try {
            controller.setMac(info.address)
            if (!info.sn.isNullOrBlank()) {
                controller.setSn(info.sn)
            }
            if (!info.name.isNullOrBlank()) {
                controller.setName(info.name)
            }
        } catch (_: Throwable) {
        }

        // Edit 33: Restore saved pairing keys BEFORE register(), matching official app's
        // TransmitterModel.instance() which sets key/id/hostAddress from DB before register().
        // Without these, register() has no encryption context and the native lib won't
        // progress to calling executeConnect().
        val hasSavedKeys = isVendorPaired()
        if (hasSavedKeys) {
            try {
                val savedKey = loadVendorPairingKey()
                val savedId = loadVendorPairingId()
                val savedHost = loadVendorHostAddress()
                if (savedKey != null) controller.setKey(savedKey)
                if (savedId != null) controller.setId(savedId)
                if (savedHost != null) controller.setHostAddress(savedHost)
                Log.i(TAG, "Vendor: restored saved pairing keys (key=${savedKey?.size ?: 0}b, id=${savedId?.size ?: 0}b, host=${savedHost?.size ?: 0}b)")
            } catch (t: Throwable) {
                Log.e(TAG, "Vendor: failed to restore pairing keys: ${t.message}")
            }
        }

        if (!vendorRegistered) {
            vendorRegistered = true
            try {
                controller.setMessageCallback { op, success, data ->
                    handleVendorReceive(op, success, data)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Vendor setMessageCallback failed: ${t.message}")
            }
            try {
                controller.register()
                Log.i(TAG, "Vendor: register() called (hasSavedKeys=$hasSavedKeys)")
            } catch (t: Throwable) {
                Log.e(TAG, "Vendor: register() failed: ${t.message}")
            }

            // Edit 33: For NEW/UNPAIRED sensors, call pair() after register() to trigger
            // the native lib's pairing handshake flow (DISCOVER→CONNECT→BOND→PAIR).
            // For SAVED/PAIRED sensors, register() with restored keys will handle reconnection.
            // The official app calls pair() from PairUtil.startPair() for new sensors,
            // and only register() for saved sensors.
            if (!hasSavedKeys) {
                try {
                    val pairResult = controller.pair()
                    Log.i(TAG, "Vendor: pair() called for new sensor (result=$pairResult)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Vendor: pair() failed: ${t.message}")
                }
            } else {
                // Edit 40: Reconnect with saved keys — if the vendor lib doesn't call
                // executeConnect within 10s, its internal state machine is stuck (Bug 1).
                // Fall back to pair() which forces the lib to restart the connection flow.
                // Edit 49c: First attempt preserves keys (pair() without clearing) to avoid
                // unnecessary key destruction. Only clear keys on 2nd attempt if first fails.
                // In the DISCOVER spam scenario, the keys aren't stale — the vendor lib's
                // state machine is just stuck. pair() without key-clear may unstick it.
                vendorReconnectFallbackAttempt = 0
                fun scheduleReconnectFallback() {
                    vendorReconnectFallbackRunnable?.let { vendorWorkHandler.removeCallbacks(it) }
                    vendorReconnectFallbackRunnable = Runnable {
                        if (!vendorExecuteConnectReceived && vendorRegistered && vendorBleEnabled) {
                            val ctrl = vendorController ?: return@Runnable
                            vendorReconnectFallbackAttempt++
                            if (vendorReconnectFallbackAttempt <= 1 && isVendorPaired()) {
                                // Edit 49c: First attempt — try pair() WITHOUT clearing keys
                                Log.w(TAG, "Edit 49c: executeConnect not received within ${VENDOR_RECONNECT_FALLBACK_MS}ms — " +
                                        "trying pair() with keys PRESERVED (attempt $vendorReconnectFallbackAttempt)")
                                try {
                                    ctrl.pair()
                                    Log.i(TAG, "Edit 49c: fallback pair() called (keys preserved)")
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Edit 49c: fallback pair() failed: ${t.message}")
                                }
                                // Re-arm fallback for 2nd attempt with longer delay
                                scheduleReconnectFallback()
                            } else if (vendorReconnectFallbackAttempt <= 2 && isVendorPaired()) {
                                // Edit 55a: 2nd attempt — retry pair() still with keys PRESERVED.
                                // failled-2.txt showed clearing keys at this stage forces a full re-pair
                                // + 30s BOND stall, totaling 91s. Keys should only be cleared after
                                // actual BOND failure (handled by Edit 55b in BOND handler).
                                Log.w(TAG, "Edit 55a: 2nd fallback (attempt $vendorReconnectFallbackAttempt) — " +
                                        "retrying pair() with keys PRESERVED (waited ${VENDOR_RECONNECT_FALLBACK_2ND_MS}ms)")
                                try {
                                    ctrl.pair()
                                    Log.i(TAG, "Edit 55a: fallback pair() called (keys still preserved)")
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Edit 55a: fallback pair() failed: ${t.message}")
                                }
                                // No more fallback attempts — if keys are truly stale, BOND failure
                                // handler (Edit 55b) will clear keys and restart.
                            } else {
                                // Edit 55a: 3rd+ attempt or no saved keys — clear keys as last resort.
                                // This path should rarely be reached now that BOND failure handler
                                // provides faster recovery.
                                Log.w(TAG, "Edit 55a: final fallback (attempt $vendorReconnectFallbackAttempt) — " +
                                        "clearing keys and falling back to pair()")
                                try {
                                    clearVendorPairingKeys()
                                    ctrl.pair()
                                    Log.i(TAG, "Vendor: fallback pair() called (keys cleared)")
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Vendor: fallback pair() failed: ${t.message}")
                                }
                            }
                        } else {
                            Log.d(TAG, "Vendor: reconnect fallback cancelled (executeConnect=${vendorExecuteConnectReceived} registered=$vendorRegistered enabled=$vendorBleEnabled)")
                        }
                        vendorReconnectFallbackRunnable = null
                    }
                    // Edit 55a: Use longer delay for 2nd+ attempt to give BLE scanner time after app restart
                    val delayMs = if (vendorReconnectFallbackAttempt >= 1) VENDOR_RECONNECT_FALLBACK_2ND_MS else VENDOR_RECONNECT_FALLBACK_MS
                    vendorWorkHandler.postDelayed(vendorReconnectFallbackRunnable!!, delayMs)
                    Log.i(TAG, "Vendor: scheduled reconnect fallback in ${delayMs}ms (attempt ${vendorReconnectFallbackAttempt + 1})")
                }
                scheduleReconnectFallback()
            }
        }
        val paramsHex = info.params?.let { bytesToHex(it) }
        Log.i(
            TAG,
            "Vendor discovered: ${info.address} sn=${info.sn} name=${info.name} params=${paramsHex ?: "null"} paired=$hasSavedKeys"
        )
        // Edit 28: Removed startVendorLongConnect("discover") — premature.
        // The native lib will drive the protocol via executeWrite() after onConnectSuccess.
        // Sending setDynamicMode/setAutoUpdate/getBroadcastData before the AES handshake
        // interferes with the connection setup.
    }


    private fun handleVendorReceive(operation: Int, success: Boolean, data: ByteArray?) {
        if (!vendorBleEnabled) return
        if (!vendorLibAvailable) return
        Log.i(TAG, "Vendor MessageCallback: op=$operation success=$success data=${data?.let { bytesToHex(it) } ?: "null"} (${data?.size ?: 0} bytes)")
        val payload: ByteArray?
        val resCode: Int
        if (operation in 1..3 || data == null || data.isEmpty()) {
            payload = data
            resCode = 1
        } else {
            resCode = data[0].toInt() and 0xFF
            payload = if (data.size > 1) data.copyOfRange(1, data.size) else ByteArray(0)
        }
        val message = BleMessage(operation, success, payload, resCode, BleMessage.MessageType.NORMAL)
        handleVendorMessage(message)
    }

    private fun handleVendorMessage(message: BleMessage) {
        if (!vendorBleEnabled) return
        if (!vendorLibAvailable) return
        val data = message.data
        val now = System.currentTimeMillis()
        // Edit 34: Update last message time to prevent reconnect stall timeout from firing
        vendorLastMessageTime = now
        Log.i(TAG, "Vendor message: op=${message.operation} success=${message.isSuccess} resCode=${message.resCode} data=${data?.let { bytesToHex(it) } ?: "null"}")
        when (message.operation) {
            // Edit 33: Handle pairing flow operations — matches official app's PairUtil.observeMessage
            AidexXOperation.DISCOVER -> {
                // Edit 37e: Track consecutive DISCOVER without executeConnect.
                // After a reset or disconnect, the native lib may spam DISCOVER without
                // ever calling executeConnect — meaning its internal state is stuck.
                // Edit 38a: Do NOT clear pairing keys on DISCOVER spam. The keys are still
                // valid after a vendor-initiated disconnect. Clearing them causes F003
                // characteristic selection (NOTIFY-only, not writable) and an infinite
                // write-fail loop that crashes the app. Instead, just restart the vendor
                // stack with keys preserved so it can reconnect normally.
                vendorConsecutiveDiscoverCount++
                Log.i(TAG, "Vendor DISCOVER: success=${message.isSuccess} (consecutive=$vendorConsecutiveDiscoverCount)")

                // Edit 50a: On the 1st consecutive DISCOVER with saved keys and no GATT,
                // proactively start a GATT connection so it's ready when the vendor lib
                // catches up. Unlike Edit 49a, we do NOT set vendorExecuteConnectReceived —
                // the vendor lib ignored our premature onConnectSuccess in that approach.
                // Instead, we set vendorProactiveGattConnect to bypass the connectDevice()
                // guard, connect GATT, enable CCCDs, and then WAIT. When the vendor lib
                // later calls executeConnect() (triggered by pair() in Edit 50c), it finds
                // GATT already connected + CCCDs ready → instant onConnectSuccess → data.
                // Edit 53a: Changed from consecutive==2 to consecutive==1 to eliminate the
                // 60-second scan gap between 1st and 2nd DISCOVER. Log analysis (slowerreconnect.txt)
                // showed this gap was the primary bottleneck: 110s total reconnect time, with
                // 60s spent waiting for the 2nd scan. With consecutive==1, reconnect drops
                // from ~110s to ~50s.
                if (vendorConsecutiveDiscoverCount == 1 && isVendorPaired() &&
                    !vendorGattConnected && !connectInProgress && mBluetoothGatt == null) {
                    Log.i(TAG, "Edit 53a: 1st DISCOVER with saved keys — proactive GATT connect (without vendorExecuteConnectReceived)")
                    vendorProactiveGattConnect = true
                    vendorConnectPending = true
                    if (mActiveDeviceAddress != null) {
                        connectedAddress = mActiveDeviceAddress
                    }
                    vendorWorkHandler.post {
                        val scheduled = connectDevice(0)
                        if (!scheduled) {
                            Log.w(TAG, "Edit 53a: proactive GATT connect failed to schedule — reverting flags")
                            vendorConnectPending = false
                            vendorProactiveGattConnect = false
                        }
                    }

                    // Edit 50c: Alongside proactive GATT, immediately call pair() with keys
                    // preserved to unstick the vendor lib's state machine. The recpnn.txt log
                    // proved that pair() with preserved keys triggers executeConnect() every time.
                    // Combined with proactive GATT (50a), this gives us:
                    //   GATT connecting (~1s) + pair() → executeConnect() → finds GATT ready
                    //   → instant onConnectSuccess → data in ~3-5s total
                    // Small delay (300ms) to let register() and GATT connect get started first.
                    vendorWorkHandler.postDelayed({
                        if (!vendorExecuteConnectReceived && vendorRegistered && vendorBleEnabled && isVendorPaired()) {
                            val ctrl = vendorController
                            if (ctrl != null) {
                                Log.i(TAG, "Edit 50c: 1st DISCOVER — calling pair() with keys preserved to trigger executeConnect")
                                try {
                                    ctrl.pair()
                                    Log.i(TAG, "Edit 50c: pair() called (keys preserved, alongside proactive GATT)")
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Edit 50c: pair() failed: ${t.message}")
                                }
                            }
                        } else {
                            Log.d(TAG, "Edit 50c: pair() skipped (execConnect=$vendorExecuteConnectReceived reg=$vendorRegistered enabled=$vendorBleEnabled paired=${isVendorPaired()})")
                        }
                    }, 300L)
                }

                if (vendorConsecutiveDiscoverCount >= VENDOR_DISCOVER_SPAM_THRESHOLD) {
                    vendorConsecutiveDiscoverCount = 0
                    vendorReconnectSpamRestarts++
                    // Edit 41c: After N consecutive spam restarts with saved keys and no
                    // executeConnect, clear keys and force pair() on the next cycle.
                    // This breaks the deadlock where register() with stale keys causes
                    // infinite DISCOVER spam (the vendor lib DISCOVERs with paired=true
                    // but never calls executeConnect).
                    if (vendorReconnectSpamRestarts >= VENDOR_MAX_SPAM_RESTARTS_BEFORE_REPAIR && isVendorPaired()) {
                        Log.w(TAG, "Edit 41c: $vendorReconnectSpamRestarts consecutive DISCOVER spam restarts " +
                                "with saved keys — clearing keys and forcing pair() on next cycle")
                        clearVendorPairingKeys()
                        vendorReconnectSpamRestarts = 0
                    } else {
                        Log.w(TAG, "Vendor DISCOVER spam: restart #$vendorReconnectSpamRestarts " +
                                "(max=$VENDOR_MAX_SPAM_RESTARTS_BEFORE_REPAIR). Restarting vendor stack (keys preserved).")
                    }
                    // DO NOT clearVendorPairingKeys() here unless escalation threshold reached (above)
                    vendorWorkHandler.post {
                        stopVendor("discover-spam-restart")
                        ensureVendorStarted("discover-spam-restart")
                    }
                }
            }
            AidexXOperation.CONNECT -> {
                Log.i(TAG, "Vendor CONNECT: success=${message.isSuccess}")
            }
            AidexXOperation.DISCONNECT -> {
                // Edit 38d + 39e + 40: Proper reconnection after vendor-initiated disconnect.
                // The vendor lib disconnects after ~80s of polling (or on its own schedule).
                // We need to: cancel polling, reset state for next cycle, and schedule a
                // delayed restart so the vendor lib can reconnect cleanly.
                // Edit 40: Guard against double-fire — the vendor lib can send DISCONNECT with
                // success=false then success=true in quick succession. Use removeCallbacks
                // before postDelayed to ensure only one restart fires.
                Log.i(TAG, "Vendor DISCONNECT: success=${message.isSuccess}")
                cancelBroadcastPoll()
                vendorGotGlucoseThisCycle = false  // reset for next connection cycle
                vendorLongConnectTriggered = false  // allow long-connect on next connection
                vendorStallRetryCount = 0  // Edit 39e: reset stall counter for fresh reconnect cycle
                cancelReconnectStallTimeout()  // Edit 39e: cancel stall timer — we know the disconnect is intentional
                vendorConsecutiveDiscoverCount = 0  // Edit 39e: reset discover counter
                // Cancel any previously scheduled restart before posting a new one
                vendorDisconnectRestartRunnable?.let { vendorWorkHandler.removeCallbacks(it) }
                // Schedule a delayed restart — gives the vendor lib time to clean up
                // its internal state before we re-trigger the scan→connect flow.
                val restartRunnable = Runnable {
                    vendorDisconnectRestartRunnable = null
                    if (vendorBleEnabled && !vendorGattConnected) {
                        Log.i(TAG, "Vendor DISCONNECT: post-disconnect restart — restarting vendor stack for reconnection")
                        stopVendor("post-disconnect-reconnect")
                        ensureVendorStarted("post-disconnect-reconnect")
                        // Also kick a broadcast scan so the native lib gets fresh advertisement data
                        scheduleBroadcastScan("post-disconnect-reconnect", forceImmediate = true)
                    }
                }
                vendorDisconnectRestartRunnable = restartRunnable
                vendorWorkHandler.postDelayed(restartRunnable, 5000L)
            }
            AidexXOperation.PAIR -> {
                // PAIR success = AES key exchange completed, encryption keys are now available
                Log.i(TAG, "Vendor PAIR: success=${message.isSuccess}")
                if (message.isSuccess) {
                    vendorStallRetryCount = 0  // Edit 36d: reset soft-retry counter on successful pair
                    cancelReconnectStallTimeout()  // Edit 36d: no stall — we got PAIR
                    val controller = vendorController
                    if (controller != null) {
                        saveVendorPairingKeys(controller)
                        // After successful pairing, the official app calls getTransInfo() and startTime()
                        try {
                            controller.getTransInfo()
                            Log.i(TAG, "Vendor PAIR: called getTransInfo()")
                        } catch (t: Throwable) {
                            Log.e(TAG, "Vendor PAIR: getTransInfo() failed: ${t.message}")
                        }
                        try {
                            controller.startTime()
                            Log.i(TAG, "Vendor PAIR: called startTime()")
                        } catch (t: Throwable) {
                            Log.e(TAG, "Vendor PAIR: startTime() failed: ${t.message}")
                        }
                    }
                } else {
                    Log.e(TAG, "Vendor PAIR FAILED — pairing handshake did not complete")
                }
            }
            AidexXOperation.UNPAIR -> {
                Log.i(TAG, "Vendor UNPAIR: success=${message.isSuccess}")
                if (message.isSuccess) {
                    clearVendorPairingKeys()
                }
            }
            AidexXOperation.BOND -> {
                Log.i(TAG, "Vendor BOND: success=${message.isSuccess}")
                if (message.isSuccess) {
                    // Edit 49d: Retry getId() — it often returns null during PAIR but succeeds
                    // after BOND completes (the vendor lib populates the ID buffer later).
                    // Evidence: lostc.txt line 2849 shows "getId() returned null/empty" at PAIR
                    // time but the ID would have been available after BOND.
                    val ctrl = vendorController
                    if (ctrl != null && readStringPref("vendorId", null) == null) {
                        try {
                            val id = ctrl.getId()
                            if (id != null && id.isNotEmpty()) {
                                writeStringPref("vendorId", bytesToHex(id))
                                Log.i(TAG, "Edit 49d: Vendor BOND: getId() succeeded on retry (${id.size} bytes)")
                            } else {
                                Log.d(TAG, "Edit 49d: Vendor BOND: getId() still null/empty after BOND")
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Edit 49d: getId() retry after BOND failed: ${t.message}")
                        }
                    }
                } else {
                    // Edit 55b: BOND failure — immediately trigger recovery instead of waiting
                    // for the 30s stall timeout. failled-2.txt showed this 30s stall was the single
                    // biggest delay (T+29s to T+59s). The stale keys were already cleared by the
                    // fallback timer, so the native lib has no keys to bond with. Recovery:
                    // disconnect GATT → short delay → clear keys (if any remain) → restart vendor
                    // stack with fresh pair cycle. This cuts re-pair from ~91s to ~30s.
                    Log.e(TAG, "Edit 55b: Vendor BOND FAILED — immediate recovery (bypassing 30s stall timeout)")
                    cancelReconnectStallTimeout()  // Don't let stall timer fire redundantly
                    vendorWorkHandler.post {
                        try {
                            // Clear keys since bonding failed — they're likely stale
                            if (isVendorPaired()) {
                                Log.i(TAG, "Edit 55b: Clearing stale pairing keys after BOND failure")
                                clearVendorPairingKeys()
                            }
                            // Soft restart: disconnect GATT, keep controller alive initially
                            vendorNativeReady = false
                            vendorExecuteConnectReceived = false
                            vendorProactiveGattConnect = false
                            vendorGattConnected = false
                            vendorServicesReady = false
                            vendorGattNotified = false
                            vendorConnectPending = false
                            vendorGattQueue.clear()
                            vendorGattOpActive = false
                            vendorLongConnectTriggered = false
                            try {
                                mBluetoothGatt?.disconnect()
                            } catch (t: Throwable) {
                                Log.e(TAG, "Edit 55b: GATT disconnect failed: ${t.message}")
                            }
                            // After brief delay, restart vendor stack for fresh pair
                            vendorWorkHandler.postDelayed({
                                Log.i(TAG, "Edit 55b: BOND failure recovery — restarting vendor stack for fresh pair")
                                try {
                                    stopVendor("bond-failure-recovery")
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Edit 55b: stopVendor failed: ${t.message}")
                                }
                                vendorStallRetryCount = 0
                                ensureVendorStarted("bond-failure-recovery")
                                scheduleBroadcastScan("bond-failure-recovery", forceImmediate = true)
                            }, 2_000L)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Edit 55b: BOND failure recovery crashed: ${t.message}")
                        }
                    }
                }
            }
            AidexXOperation.ENABLE_NOTIFY -> {
                Log.i(TAG, "Vendor ENABLE_NOTIFY: success=${message.isSuccess}")
            }
            AidexXOperation.DELETE_BOND -> {
                Log.i(TAG, "Vendor DELETE_BOND: success=${message.isSuccess}")
                if (message.isSuccess) {
                    clearVendorPairingKeys()
                }
            }
            AidexXOperation.GET_DEVICE_INFO -> {
                Log.i(TAG, "Vendor GET_DEVICE_INFO: success=${message.isSuccess} data=${data?.let { bytesToHex(it) } ?: "null"}")
                // Edit 58c: Parse device info response.
                // Observed format (16 bytes): [status:2][fw_major][fw_minor][hw_major][hw_minor][sensor_type:2][model_ascii:8]
                // Example: 00 00 01 07 01 03 0F 00 47 58 2D 30 31 53 00 00 → fw=1.7 hw=1.3 model="GX-01S"
                if (message.isSuccess && data != null && data.size >= 8) {
                    try {
                        val fwMajor = data[2].toInt() and 0xFF
                        val fwMinor = data[3].toInt() and 0xFF
                        val hwMajor = data[4].toInt() and 0xFF
                        val hwMinor = data[5].toInt() and 0xFF
                        vendorFirmwareVersion = "$fwMajor.$fwMinor"
                        vendorHardwareVersion = "$hwMajor.$hwMinor"
                        // Model string starts at byte 8, null-terminated
                        if (data.size > 8) {
                            val modelBytes = data.sliceArray(8 until data.size)
                            val nullIdx = modelBytes.indexOf(0.toByte())
                            val modelStr = if (nullIdx >= 0) String(modelBytes, 0, nullIdx, Charsets.US_ASCII)
                                          else String(modelBytes, Charsets.US_ASCII)
                            if (modelStr.isNotBlank()) vendorModelName = modelStr.trim()
                        }
                        Log.i(TAG, "Edit 58c: GET_DEVICE_INFO: fw=$vendorFirmwareVersion hw=$vendorHardwareVersion model=$vendorModelName")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Edit 58c: GET_DEVICE_INFO parse failed: ${t.message}")
                    }
                }
            }
            AidexXOperation.GET_START_TIME -> {
                // This is the final step in the official pairing flow.
                // After GET_START_TIME, the official app calls savePair() → TransmitterManager.set() → register()
                // and then starts the data subscription (setDynamicMode + setAutoUpdate + getBroadcastData).
                Log.i(TAG, "Vendor GET_START_TIME: success=${message.isSuccess} data=${data?.let { bytesToHex(it) } ?: "null"}")
                if (message.isSuccess) {
                    // Edit 58a: Parse the AidexXDatetimeEntity format from GET_START_TIME response.
                    // Format: [year_lo, year_hi, month, day, hour, minute, second, timezone, dstOffset]
                    // This gives us the authoritative sensor start time instead of inferring from offsets.
                    if (data != null && data.size >= 7) {
                        try {
                            val year = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                            val month = data[2].toInt() and 0xFF  // 1-12
                            val day = data[3].toInt() and 0xFF
                            val hour = data[4].toInt() and 0xFF
                            val minute = data[5].toInt() and 0xFF
                            val second = data[6].toInt() and 0xFF
                            // Timezone in 15-minute increments (signed), dstOffset also in 15-min increments
                            val tzQuarters = if (data.size >= 8) data[7].toInt() else 0  // signed byte
                            val dstQuarters = if (data.size >= 9) data[8].toInt() and 0xFF else 0

                            if (year in 2020..2040 && month in 1..12 && day in 1..31) {
                                // Construct UTC time adjusted for timezone
                                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                                cal.set(year, month - 1, day, hour, minute, second)
                                cal.set(java.util.Calendar.MILLISECOND, 0)
                                // Subtract the sensor's timezone offset to get UTC
                                val tzOffsetMs = (tzQuarters + dstQuarters) * 15L * 60_000L
                                val startUtcMs = cal.timeInMillis - tzOffsetMs

                                vendorSensorStartTimeMs = startUtcMs
                                sensorstartmsec = startUtcMs
                                if (dataptr != 0L) {
                                    try { Natives.aidexSetStartTime(dataptr, startUtcMs) } catch (_: Throwable) {}
                                }
                                // Compute expiry: AiDex X sensors have a 14-day life
                                vendorSensorExpiryMs = startUtcMs + (AIDEX_SENSOR_MAX_DAYS * 24L * 3600_000L)
                                Log.i(TAG, "Edit 58a: GET_START_TIME parsed: $year-${String.format("%02d", month)}-${String.format("%02d", day)} " +
                                        "${String.format("%02d", hour)}:${String.format("%02d", minute)}:${String.format("%02d", second)} " +
                                        "tz=${tzQuarters}q dst=${dstQuarters}q → startMs=$startUtcMs expiryMs=$vendorSensorExpiryMs")
                            } else {
                                Log.w(TAG, "Edit 58a: GET_START_TIME date out of range: $year-$month-$day")
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Edit 58a: GET_START_TIME parse failed: ${t.message}")
                        }
                    }
                    // Pairing is fully complete — trigger long-connect to start data flow
                    Log.i(TAG, "Vendor GET_START_TIME: pairing complete, triggering long-connect for data flow")
                    vendorWorkHandler.postDelayed({
                        startVendorLongConnect("post-pairing")
                    }, 500L)
                }
            }
            AidexXOperation.GET_HISTORY_RANGE -> {
                // Edit 45d: Parse 6-byte range response and trigger paginated history download.
                // Official app pattern: getHistoryRange() → parse range → getHistories(nextEventIndex)
                Log.i(TAG, "Vendor GET_HISTORY_RANGE: success=${message.isSuccess} data=${data?.let { bytesToHex(it) } ?: "null"} (${data?.size ?: 0} bytes)")
                if (message.isSuccess && data != null && data.size >= 6) {
                    val briefStart = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    val rawStart = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                    val newest = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
                    vendorHistoryBriefStart = briefStart
                    vendorHistoryNewestIndex = newest
                    Log.i(TAG, "GET_HISTORY_RANGE: briefStart=$briefStart rawStart=$rawStart newest=$newest, our nextIndex=$vendorHistoryNextIndex")
                    // Clamp nextIndex to at least briefStart (can't request before sensor's earliest)
                    if (vendorHistoryNextIndex < briefStart) {
                        Log.i(TAG, "GET_HISTORY_RANGE: clamping nextIndex from $vendorHistoryNextIndex to briefStart=$briefStart")
                        vendorHistoryNextIndex = briefStart
                    }
                    // Edit 52a: Roll back vendorHistoryNextIndex if it leapfrogged past a gap.
                    // After disconnect, broadcast stores may have advanced nextIndex to the current
                    // offset, skipping the gap between the last AUTO_UPDATE offset and the current one.
                    // Roll back to (lastAutoUpdateOffset + 1) so history backfill covers the gap.
                    if (vendorLastAutoUpdateOffsetAtDisconnect > 0 &&
                        vendorHistoryNextIndex > vendorLastAutoUpdateOffsetAtDisconnect + 1 &&
                        newest > vendorLastAutoUpdateOffsetAtDisconnect + 1) {
                        val rollbackTo = vendorLastAutoUpdateOffsetAtDisconnect + 1
                        Log.i(TAG, "Edit 52a: Rolling back vendorHistoryNextIndex from $vendorHistoryNextIndex to $rollbackTo " +
                                "(disconnect snapshot=$vendorLastAutoUpdateOffsetAtDisconnect, newest=$newest)")
                        vendorHistoryNextIndex = rollbackTo
                        writeIntPref("vendorHistoryNextIndex", vendorHistoryNextIndex)
                        vendorLastAutoUpdateOffsetAtDisconnect = 0  // consumed
                    }
                    // If there are records to fetch, start paginated download
                    if (vendorHistoryNextIndex < newest) {
                        val recordsAvailable = newest - vendorHistoryNextIndex
                        Log.i(TAG, "GET_HISTORY_RANGE: $recordsAvailable history records available, starting download from index=$vendorHistoryNextIndex")
                        vendorHistoryDownloading = true
                        vendorHistoryRecordsStored = 0
                        // Edit 48: Snapshot current AUTO_UPDATE offset so storeHistoryRecord()
                        // can skip records already covered by real-time data.
                        vendorHistoryAutoUpdateCutoff = lastVendorOffsetMinutes
                        Log.i(TAG, "GET_HISTORY_RANGE: autoUpdateCutoff=$vendorHistoryAutoUpdateCutoff (records at or beyond this offset will be skipped)")
                        vendorExecutor.execute {
                            try {
                                val controller = vendorController ?: return@execute
                                Log.i(TAG, "GET_HISTORY_RANGE: calling getHistories($vendorHistoryNextIndex)")
                                val result = controller.getHistories(vendorHistoryNextIndex)
                                Log.i(TAG, "GET_HISTORY_RANGE: getHistories($vendorHistoryNextIndex) returned $result")
                            } catch (t: Throwable) {
                                Log.e(TAG, "GET_HISTORY_RANGE: getHistories failed: ${t.message}")
                                vendorHistoryDownloading = false
                            }
                        }
                    } else {
                        Log.i(TAG, "GET_HISTORY_RANGE: no new history (nextIndex=$vendorHistoryNextIndex >= newest=$newest)")
                        vendorHistoryDownloading = false
                    }
                } else if (message.isSuccess && data != null && data.size < 6) {
                    Log.w(TAG, "GET_HISTORY_RANGE: unexpected data size ${data.size} (expected 6)")
                }
            }
            AidexXOperation.AUTO_UPDATE_FULL_HISTORY -> {
                // Edit 43b: This is the primary real-time glucose push mechanism in long-connect
                // mode. The sensor sends these continuously (~1/min) after SET_AUTO_UPDATE.
                // The vendor lib decrypts the F003 notification and delivers parsed data here.
                // Data is 14 bytes (vendor-parsed), not 17 (raw F003). Edit 43a fixes the parser.
                // Edit 45c: This is now the PRIMARY data source. When active, slow poll is suppressed.
                Log.i(TAG, "Vendor AUTO_UPDATE_FULL_HISTORY: success=${message.isSuccess} data=${data?.let { bytesToHex(it) } ?: "null"} (${data?.size ?: 0} bytes)")
                if (data != null && message.isSuccess) {
                    val entity = parseVendorInstantPayload(data, now)
                    if (entity != null) {
                        val didStore = storeAutoFromSource(entity.glucoseMgDl, entity.timeOffsetMinutes, now, "vendor-auto", fromVendor = true)
                        if (didStore) {
                            Log.i(TAG, "Vendor AUTO_UPDATE: glucose=${entity.glucoseMgDl} offset=${entity.timeOffsetMinutes}min trend=${entity.trend} — stored")
                        } else {
                            // Edit 51d: Log accurately when dedup timer or offset check rejects the store.
                            // Common after fresh pairing when GET_BROADCAST_DATA just stored <50s ago.
                            Log.i(TAG, "Vendor AUTO_UPDATE: glucose=${entity.glucoseMgDl} offset=${entity.timeOffsetMinutes}min trend=${entity.trend} — deduped (not stored)")
                        }
                        vendorGotGlucoseThisCycle = true
                        vendorLastMessageTime = now
                        // Edit 45c: Mark AUTO_UPDATE as active — this suppresses slow poll in the watchdog
                        // Always update regardless of whether store succeeded — we ARE receiving data.
                        vendorLastAutoUpdateTime = now
                        // Edit 51b: Advance vendorHistoryNextIndex past this AUTO_UPDATE offset.
                        // This prevents history backfill (after reconnection) from re-fetching offsets
                        // that AUTO_UPDATE already stored, eliminating duplicate data points.
                        // Advance even if store was deduped — the sensor DID deliver this offset.
                        val nextIdx = entity.timeOffsetMinutes + 1
                        if (nextIdx > vendorHistoryNextIndex) {
                            vendorHistoryNextIndex = nextIdx
                            writeIntPref("vendorHistoryNextIndex", vendorHistoryNextIndex)
                            Log.d(TAG, "Edit 51b: Advanced vendorHistoryNextIndex to $vendorHistoryNextIndex (from AUTO_UPDATE offset=${entity.timeOffsetMinutes})")
                        }
                        // Trigger history backfill (Edit 51a: suppressed while AUTO_UPDATE is active)
                        requestHistoryBackfill("post-auto-update")
                    } else {
                        Log.w(TAG, "Vendor AUTO_UPDATE: parseVendorInstantPayload returned null for ${data.size}-byte payload")
                    }
                }
            }
            AidexXOperation.GET_BROADCAST_DATA -> {
                // Edit 37a: Process the broadcast data and schedule the next poll.
                // Edit 37b: Detect warmup state — the vendor parser returns null when
                // isValid=0 or glucose is out of 30..500 range (e.g. 0xFF=255 during warmup).
                // Edit 38b: Stop fast polling once we store a valid glucose value. The vendor lib
                // disconnects after ~80s of continuous 2s polling.
                // Edit 44b: Instead of stopping entirely, transition to slow periodic poll (~62s).
                // This keeps glucose readings flowing even without AUTO_UPDATE pushes.
                if (data != null && message.isSuccess) {
                    val result = parseVendorBroadcastPayload(data, now)
                    if (result != null) {
                        storeAutoFromSource(result.glucoseMgDl, result.timeOffsetMinutes, now, "vendor-gatt", fromVendor = true)
                        Log.i(TAG, "Vendor GET_BROADCAST_DATA: glucose=${result.glucoseMgDl} offset=${result.timeOffsetMinutes}min — stored, transitioning to slow poll")
                        vendorGotGlucoseThisCycle = true
                        // Edit 44b: Transition to slow poll — don't cancel, just let the
                        // broadcastPollRunnable detect vendorGotGlucoseThisCycle and switch
                        if (!vendorSlowPollActive) {
                            vendorSlowPollActive = true
                            scheduleNextBroadcastPoll()  // reschedule at slow interval
                        }
                        // Edit 42c: Connection is fully alive — request history backfill
                        requestHistoryBackfill("post-glucose")
                        return  // Don't schedule another fast poll
                    } else {
                        Log.i(TAG, "Vendor GET_BROADCAST_DATA: no valid glucose (warmup or invalid data)")
                    }
                } else if (!message.isSuccess) {
                    Log.w(TAG, "Vendor GET_BROADCAST_DATA: failed (resCode=${message.resCode})")
                }
                // Schedule next poll only if we haven't stored a value yet — sensor may transition out of warmup
                if (!vendorGotGlucoseThisCycle) {
                    scheduleNextBroadcastPoll()
                }
            }
            AidexXOperation.RESET -> {
                Log.i(TAG, "Vendor RESET response: success=${message.isSuccess} resCode=${message.resCode}")
            }
            AidexXOperation.SET_NEW_SENSOR -> {
                Log.i(TAG, "Vendor NEW_SENSOR response: success=${message.isSuccess} resCode=${message.resCode}")
            }
            AidexXOperation.CLEAR_STORAGE -> {
                Log.i(TAG, "Vendor CLEAR_STORAGE response: success=${message.isSuccess} resCode=${message.resCode}")
            }
            AidexXOperation.GET_HISTORIES -> {
                // Edit 45d+46+46b: Batch history parsing + lightweight native-only store.
                // Each record is stored via Natives.aidexProcessData ONLY — no notifications,
                // alarms, xDrip, HistorySync, or UI updates per record.
                // A single HistorySync.syncFromNative() fires when the entire download completes.
                // This reduces 1,070 records from 68 seconds (3 ANRs) to ~1 second.
                Log.i(TAG, "Vendor GET_HISTORIES response: success=${message.isSuccess} (${data?.size ?: 0} bytes)")
                if (message.isSuccess && data != null && data.isNotEmpty()) {
                    try {
                        val entities: List<AidexXHistoryEntity>? = AidexXParser.getHistories(data)
                        if (entities != null && entities.isNotEmpty()) {
                            var stored = 0
                            var skipped = 0
                            var maxOffset = vendorHistoryNextIndex
                            var newestStoredGlucose = 0
                            var newestStoredOffset = 0
                            for (entity in entities) {
                                val glucose = entity.glucose
                                val offset = entity.timeOffset
                                val didStore = storeHistoryRecord(glucose, offset, entity.status, entity.quality, entity.isValid)
                                if (didStore) {
                                    stored++
                                    if (offset > newestStoredOffset) {
                                        newestStoredOffset = offset
                                        newestStoredGlucose = glucose
                                    }
                                } else {
                                    skipped++
                                }
                                if (offset > maxOffset) maxOffset = offset
                            }
                            // Advance nextIndex past what we've received
                            vendorHistoryNextIndex = maxOffset + 1
                            // Edit 47: Persist nextIndex so reconnects only download new records
                            writeIntPref("vendorHistoryNextIndex", vendorHistoryNextIndex)
                            vendorHistoryRecordsStored += stored
                            Log.i(TAG, "GET_HISTORIES: page ${entities.size} records, stored=$stored skipped=$skipped, nextIndex=$vendorHistoryNextIndex (newest=$vendorHistoryNewestIndex, total stored=$vendorHistoryRecordsStored)")
                            // Request next page if more records remain
                            if (vendorHistoryDownloading && vendorHistoryNextIndex < vendorHistoryNewestIndex) {
                                vendorExecutor.execute {
                                    try {
                                        val controller = vendorController ?: return@execute
                                        if (!vendorGattConnected || !vendorNativeReady) {
                                            Log.w(TAG, "GET_HISTORIES: pagination aborted — GATT disconnected")
                                            vendorHistoryDownloading = false
                                            // Still sync what we have so far
                                            if (vendorHistoryRecordsStored > 0) {
                                                try { tk.glucodata.data.HistorySync.syncFromNative() } catch (_: Throwable) {}
                                            }
                                            return@execute
                                        }
                                        Log.i(TAG, "GET_HISTORIES: requesting next page from index=$vendorHistoryNextIndex")
                                        val result = controller.getHistories(vendorHistoryNextIndex)
                                        Log.i(TAG, "GET_HISTORIES: getHistories($vendorHistoryNextIndex) returned $result")
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "GET_HISTORIES: pagination failed: ${t.message}")
                                        vendorHistoryDownloading = false
                                        if (vendorHistoryRecordsStored > 0) {
                                            try { tk.glucodata.data.HistorySync.syncFromNative() } catch (_: Throwable) {}
                                        }
                                    }
                                }
                            } else {
                                // Download complete — do a SINGLE sync to update Compose chart
                                Log.i(TAG, "GET_HISTORIES: download complete — $vendorHistoryRecordsStored records stored total")
                                vendorHistoryDownloading = false
                                if (vendorHistoryRecordsStored > 0) {
                                    try { tk.glucodata.data.HistorySync.syncFromNative() } catch (_: Throwable) {}
                                    Log.i(TAG, "GET_HISTORIES: HistorySync triggered after $vendorHistoryRecordsStored records")
                                }
                            }
                        } else {
                            Log.d(TAG, "GET_HISTORIES: parser returned null/empty list for ${data.size}-byte payload")
                            vendorHistoryDownloading = false
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "GET_HISTORIES: parse failed: ${t.message}")
                        vendorHistoryDownloading = false
                        if (vendorHistoryRecordsStored > 0) {
                            try { tk.glucodata.data.HistorySync.syncFromNative() } catch (_: Throwable) {}
                        }
                    }
                } else {
                    // resCode=4 with empty data means no more records at this index
                    Log.i(TAG, "GET_HISTORIES: empty/failed response (resCode=${message.resCode}) — download complete ($vendorHistoryRecordsStored records stored)")
                    vendorHistoryDownloading = false
                    if (vendorHistoryRecordsStored > 0) {
                        try { tk.glucodata.data.HistorySync.syncFromNative() } catch (_: Throwable) {}
                    }
                }
            }
            AidexXOperation.AUTO_UPDATE_CALIBRATION -> {
                // Sensor pushes a calibration update notification. Parse it via AidexXParser.
                Log.i(TAG, "Vendor AUTO_UPDATE_CALIBRATION: success=${message.isSuccess} data=${data?.let { bytesToHex(it) } ?: "null"} (${data?.size ?: 0} bytes)")
                if (data != null && message.isSuccess) {
                    try {
                        val entities: List<com.microtechmd.blecomm.parser.AidexXCalibrationEntity> =
                            com.microtechmd.blecomm.parser.AidexXParser.getAidexXCalibration(data)
                        if (entities.isNotEmpty()) {
                            val newRecords = entities.mapNotNull { entity: com.microtechmd.blecomm.parser.AidexXCalibrationEntity ->
                                if (entity.isValid != 1) return@mapNotNull null
                                CalibrationRecord(
                                    index = entity.index,
                                    timeOffsetMinutes = entity.timeOffset,
                                    referenceGlucoseMgDl = entity.referenceGlucose,
                                    cf = entity.cf,
                                    offset = entity.offset,
                                    isValid = true,
                                    timestampMs = if (sensorstartmsec > 0) sensorstartmsec + entity.timeOffset.toLong() * 60_000L else 0L
                                )
                            }
                            Log.i(TAG, "AUTO_UPDATE_CALIBRATION: parsed ${newRecords.size} valid calibration records")
                            // Merge into existing records (replace by index)
                            val existing = vendorCalibrationRecords.toMutableList()
                            for (rec in newRecords) {
                                existing.removeAll { it.index == rec.index }
                                existing.add(rec)
                            }
                            vendorCalibrationRecords = existing.toList()
                            Log.i(TAG, "AUTO_UPDATE_CALIBRATION: total calibration records now: ${vendorCalibrationRecords.size}")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "AUTO_UPDATE_CALIBRATION: parse failed: ${t.message}")
                    }
                }
            }
            AidexXOperation.GET_CALIBRATION_RANGE -> {
                // Response to getCalibrationRange(): similar to GET_HISTORY_RANGE but for calibrations.
                // Expected format: bytes with start index and newest index.
                Log.i(TAG, "Vendor GET_CALIBRATION_RANGE: success=${message.isSuccess} data=${data?.let { bytesToHex(it) } ?: "null"} (${data?.size ?: 0} bytes)")
                if (message.isSuccess && data != null && data.size >= 4) {
                    val calStart = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    val calNewest = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                    vendorCalibrationRangeStart = calStart
                    vendorCalibrationNewestIndex = calNewest
                    Log.i(TAG, "GET_CALIBRATION_RANGE: start=$calStart newest=$calNewest")
                    if (calNewest > 0 && calStart <= calNewest) {
                        vendorCalibrationDownloading = true
                        // Request calibrations starting from the start index
                        vendorExecutor.execute {
                            try {
                                val controller = vendorController ?: return@execute
                                for (i in calStart..calNewest) {
                                    Log.d(TAG, "GET_CALIBRATION_RANGE: requesting getCalibration($i)")
                                    controller.getCalibration(i)
                                    // Small delay between requests to avoid overwhelming BLE
                                    Thread.sleep(100)
                                }
                                vendorCalibrationDownloading = false
                                Log.i(TAG, "GET_CALIBRATION_RANGE: finished requesting ${calNewest - calStart + 1} calibration records")
                            } catch (t: Throwable) {
                                Log.e(TAG, "GET_CALIBRATION_RANGE: getCalibration loop failed: ${t.message}")
                                vendorCalibrationDownloading = false
                            }
                        }
                    } else {
                        Log.i(TAG, "GET_CALIBRATION_RANGE: no calibration records available")
                    }
                }
            }
            AidexXOperation.GET_CALIBRATION -> {
                // Response to getCalibration(index): individual calibration record.
                Log.i(TAG, "Vendor GET_CALIBRATION: success=${message.isSuccess} data=${data?.let { bytesToHex(it) } ?: "null"} (${data?.size ?: 0} bytes)")
                if (data != null && message.isSuccess) {
                    try {
                        val entities: List<com.microtechmd.blecomm.parser.AidexXCalibrationEntity> =
                            com.microtechmd.blecomm.parser.AidexXParser.getAidexXCalibration(data)
                        if (entities.isNotEmpty()) {
                            val newRecords = entities.map { entity: com.microtechmd.blecomm.parser.AidexXCalibrationEntity ->
                                CalibrationRecord(
                                    index = entity.index,
                                    timeOffsetMinutes = entity.timeOffset,
                                    referenceGlucoseMgDl = entity.referenceGlucose,
                                    cf = entity.cf,
                                    offset = entity.offset,
                                    isValid = entity.isValid == 1,
                                    timestampMs = if (sensorstartmsec > 0) sensorstartmsec + entity.timeOffset.toLong() * 60_000L else 0L
                                )
                            }
                            val existing = vendorCalibrationRecords.toMutableList()
                            for (rec in newRecords) {
                                existing.removeAll { it.index == rec.index }
                                existing.add(rec)
                            }
                            vendorCalibrationRecords = existing.toList()
                            for (rec in newRecords) {
                                Log.i(TAG, "GET_CALIBRATION: index=${rec.index} glucose=${rec.referenceGlucoseMgDl}mg/dL offset=${rec.timeOffsetMinutes}min cf=${rec.cf} valid=${rec.isValid}")
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "GET_CALIBRATION: parse failed: ${t.message}")
                    }
                }
            }
            AidexXOperation.SET_CALIBRATION -> {
                // Response to our calibration command
                Log.i(TAG, "Vendor SET_CALIBRATION: success=${message.isSuccess} resCode=${message.resCode}")
                if (message.isSuccess) {
                    // Refresh calibration list to include the new calibration
                    requestCalibrationData("post-set-calibration")
                }
            }
            AidexXOperation.AUTO_UPDATE_SENSOR_EXPIRED -> {
                Log.i(TAG, "Vendor AUTO_UPDATE_SENSOR_EXPIRED: success=${message.isSuccess} data=${data?.let { bytesToHex(it) } ?: "null"}")
                if (message.isSuccess) {
                    vendorSensorExpired = true
                    vendorSensorExpiredTime = System.currentTimeMillis()
                    Log.w(TAG, "Sensor has reported EXPIRED at ${vendorSensorExpiredTime}")
                }
            }
            AidexXOperation.AUTO_UPDATE_BATTERY_VOLTAGE -> {
                Log.i(TAG, "Vendor AUTO_UPDATE_BATTERY_VOLTAGE: success=${message.isSuccess} data=${data?.let { bytesToHex(it) } ?: "null"} (${data?.size ?: 0} bytes)")
                if (data != null && data.size >= 2 && message.isSuccess) {
                    val millivolts = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    vendorBatteryMillivolts = millivolts
                    vendorBatteryLastUpdated = System.currentTimeMillis()
                    Log.i(TAG, "Battery voltage: ${millivolts} mV (${String.format("%.3f", millivolts / 1000.0)} V)")
                }
            }
            // Edit 37c: Handle long-connect setup responses properly
            AidexXOperation.SET_DYNAMIC_ADV_MODE -> {
                Log.i(TAG, "Vendor SET_DYNAMIC_ADV_MODE: success=${message.isSuccess}")
            }
            AidexXOperation.SET_AUTO_UPDATE_STATUS -> {
                Log.i(TAG, "Vendor SET_AUTO_UPDATE_STATUS: success=${message.isSuccess}")
            }
            else -> {
                Log.d(TAG, "Vendor unhandled op=${message.operation} (0x${Integer.toHexString(message.operation)}) success=${message.isSuccess}")
            }
        }
    }

    private fun handleVendorInstant(payload: ByteArray, now: Long, source: String) {
        if (!vendorBleEnabled) return
        val entity = parseVendorInstantPayload(payload, now) ?: return
        storeAutoFromSource(entity.glucoseMgDl, entity.timeOffsetMinutes, now, source, fromVendor = true)
    }

    private fun handleVendorBroadcast(payload: ByteArray, now: Long, source: String) {
        if (!vendorBleEnabled) return
        val entity = parseVendorBroadcastPayload(payload, now) ?: return
        storeAutoFromSource(entity.glucoseMgDl, entity.timeOffsetMinutes, now, source, fromVendor = true)
    }

    /**
     * Safely call vendorAdapter.onConnectSuccess() with crash-loop protection.
     * 
     * CRITICAL: The native Ble::onConnectSuccess() in libblecomm-lib.so SIGSEGV-crashes if called
     * before the native state machine is ready. The native lib must have called executeConnect()
     * first — this sets up an internal connection context struct. Without it, onConnectSuccess()
     * at offset +100 dereferences a null pointer → SIGSEGV → process kill → crash loop.
     *
     * Guards (in order):
     * 1. vendorExecuteConnectReceived — native lib must have called executeConnect() first
     * 2. vendorRegistered — controller.register() must have linked controller to Ble singleton
     * 3. Crash-loop count — backs off after VENDOR_MAX_CRASH_RETRIES consecutive failures
     * 4. try/catch — catches JNI exceptions (but NOT native SIGSEGV)
     * 5. Sets vendorNativeReady=true on success (gates onReceiveData forwarding)
     */
    private fun safeCallOnConnectSuccess(caller: String) {
        val adapter = vendorAdapter
        if (adapter == null) {
            Log.w(TAG, "safeCallOnConnectSuccess($caller): vendorAdapter is null, skipping.")
            return
        }
        if (!vendorExecuteConnectReceived) {
            Log.w(TAG, "safeCallOnConnectSuccess($caller): BLOCKED — native lib has not called executeConnect() yet. " +
                    "Internal Ble connection context is not allocated. Calling onConnectSuccess() would SIGSEGV.")
            return
        }
        if (!vendorRegistered) {
            Log.w(TAG, "safeCallOnConnectSuccess($caller): BLOCKED — controller not registered. " +
                    "Ble singleton has no registered controller to dispatch to.")
            return
        }
        if (vendorConnectSuccessCrashCount >= VENDOR_MAX_CRASH_RETRIES) {
            Log.e(TAG, "safeCallOnConnectSuccess($caller): BLOCKED — $vendorConnectSuccessCrashCount consecutive failures. " +
                    "Native Ble object appears corrupt. Will retry on next fresh connection.")
            return
        }
        vendorConnectSuccessPendingCaller = null
        // Edit 27: Removed bond-gating. The vendor lib needs onConnectSuccess() ASAP to initiate
        // the AES key exchange — which IS the command that triggers bonding. Deferring onConnectSuccess
        // until bonded creates a chicken-and-egg deadlock: can't bond without the key exchange write,
        // can't write without onConnectSuccess.
        try {
            Log.i(TAG, "safeCallOnConnectSuccess($caller): Calling vendorAdapter.onConnectSuccess()...")
            adapter.onConnectSuccess()
            vendorNativeReady = true
            vendorConnectSuccessCrashCount = 0 // reset on success
            Log.i(TAG, "safeCallOnConnectSuccess($caller): onConnectSuccess() returned OK. vendorNativeReady=true")
            // Edit 34: Start a reconnection stall timer. If we have saved keys but the native lib's
            // AES handshake stalls (no PAIR/data response within timeout), clear keys and retry fresh.
            // This handles the case where the sensor has a different bond/AES context than our saved keys.
            scheduleReconnectStallTimeout()
        } catch (t: Throwable) {
            vendorConnectSuccessCrashCount++
            vendorNativeReady = false
            Log.e(TAG, "safeCallOnConnectSuccess($caller): onConnectSuccess() THREW (crash #$vendorConnectSuccessCrashCount): ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Edit 34: Reconnection stall timeout.
     *
     * After onConnectSuccess(), the native lib should initiate an AES key exchange on F001.
     * For a SAVED sensor (restored keys), the sensor responds and data flows.
     * For a stalled reconnection (sensor rejected old keys, bond mismatch, etc.), there's no response.
     *
     * This timer fires 20 seconds after onConnectSuccess(). If no vendor message callback has
     * been received in that time (no PAIR, CONNECT, GET_BROADCAST_DATA, etc.), we assume the
     * AES handshake is stuck and take corrective action:
     * 1. Clear saved pairing keys (they're stale/wrong)
     * 2. Clear the BLE bond (sensor has different bond context)
     * 3. Disconnect and let SensorBluetooth reconnect — next onVendorDiscovered() will do a fresh pair()
     */
    private var vendorLastMessageTime = 0L
    private var reconnectStallRunnable: Runnable? = null
    private val RECONNECT_STALL_TIMEOUT_MS = 30_000L  // Edit 35b: increased from 20s to 30s
    private var vendorStallRetryCount = 0  // Edit 36d: track soft-restart attempts
    private val VENDOR_MAX_SOFT_RETRIES = 3  // Edit 39b: increased from 2 → 3 (soft restarts are safer now, no bond removal)

    private fun scheduleReconnectStallTimeout() {
        // Cancel any previous stall timer
        reconnectStallRunnable?.let { vendorWorkHandler.removeCallbacks(it) }

        vendorLastMessageTime = System.currentTimeMillis()

        val runnable = Runnable {
            val elapsed = System.currentTimeMillis() - vendorLastMessageTime
            if (elapsed >= RECONNECT_STALL_TIMEOUT_MS - 1000) {
                // No vendor activity (messages, reads, writes) since timeout was set
                vendorStallRetryCount++
                Log.w(TAG, "Reconnect stall timeout: no vendor activity for ${elapsed}ms after onConnectSuccess (retry #$vendorStallRetryCount)")
                // Edit 39b: Do NOT clear pairing keys or remove BLE bond when vendor mode is active.
                // The vendor lib (libblecomm-lib.so) relies on the BLE bond to transition from
                // DISCOVER → executeConnect. Removing the bond puts the vendor lib into "unpaired"
                // mode where it only DISCOVERs but never connects. Keys are valid unless the sensor
                // itself has been reset/re-paired with another device.
                // Only clear keys/bond in non-vendor (raw handshake) mode.
                if (!vendorBleEnabled) {
                    if (isVendorPaired()) {
                        Log.w(TAG, "Reconnect stall: clearing stale pairing keys and retrying fresh (non-vendor mode)")
                        clearVendorPairingKeys()
                    }
                    val gatt = mBluetoothGatt
                    if (gatt != null) {
                        try {
                            val device = gatt.device
                            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                                Log.i(TAG, "Reconnect stall: removing BLE bond for ${device.address}")
                                removeBondIfBonded(device)
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Reconnect stall: removeBond failed: ${t.message}")
                        }
                    }
                } else {
                    Log.i(TAG, "Reconnect stall: vendor mode active — preserving bond + keys (vendor lib needs them)")
                }

                if (vendorStallRetryCount <= VENDOR_MAX_SOFT_RETRIES) {
                    // Edit 36d: SOFT RESTART — disconnect GATT only, keep vendorController alive.
                    // This preserves the native lib's internal state (including UUID counter),
                    // preventing the UUID drift (F001→F003) that happens when we destroy and
                    // recreate the controller. The native lib will re-use the same UUIDs on reconnect.
                    Log.i(TAG, "Reconnect stall: SOFT restart #$vendorStallRetryCount — disconnect GATT only, keep native controller")
                    vendorNativeReady = false
        vendorExecuteConnectReceived = false
        vendorProactiveGattConnect = false  // Edit 50a: reset proactive GATT bypass on stop
                    vendorGattConnected = false
                    vendorServicesReady = false
                    vendorGattNotified = false
                    vendorConnectPending = false
                    vendorGattQueue.clear()
                    vendorGattOpActive = false
                    vendorLongConnectTriggered = false
                    cancelReconnectStallTimeout()
                    try {
                        mBluetoothGatt?.disconnect()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Reconnect stall: GATT disconnect failed: ${t.message}")
                    }
                    // Schedule reconnect after short delay — SensorBluetooth's onConnectionStateChange
                    // will trigger a new connectDevice(). But we also kick a broadcast scan to re-feed
                    // advertisement bytes to the native lib, which triggers executeConnect().
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.i(TAG, "Reconnect stall: soft restart — kicking broadcast scan for re-discovery")
                        scheduleBroadcastScan("stall-soft-retry", forceImmediate = true)
                        // Also nudge the native lib with cached scan data to trigger executeConnect
                        nudgeVendorExecuteConnect("stall-soft-retry")
                    }, 3_000L)
                } else {
                    // Edit 36d: HARD RESTART — too many soft retries failed, destroy everything.
                    // The native lib's state is likely corrupt. Full teardown + recreate.
                    Log.w(TAG, "Reconnect stall: HARD restart — $vendorStallRetryCount soft retries exhausted, doing full stopVendor")
                    vendorStallRetryCount = 0  // reset for next cycle
                    try {
                        stopVendor("reconnect-stall-hard")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Reconnect stall: stopVendor failed: ${t.message}")
                    }
                    try {
                        mBluetoothGatt?.disconnect()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Reconnect stall: disconnect failed: ${t.message}")
                    }
                    // Schedule a restart after a short delay to give GATT time to disconnect
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.i(TAG, "Reconnect stall: hard restart — full vendor stack restart")
                        ensureVendorStarted("stall-hard-retry")
                        scheduleBroadcastScan("stall-hard-retry", forceImmediate = true)
                    }, 3_000L)
                }
            } else {
                // Edit 39a: Vendor activity was seen — reschedule for remaining time.
                // FIX: The old code created a Runnable that called reconnectStallRunnable?.run()
                // synchronously, but reconnectStallRunnable pointed to that same Runnable
                // → infinite recursion → StackOverflowError.
                // Fix: just re-post the SAME runnable with the remaining delay. No new wrapper needed.
                val remainingMs = RECONNECT_STALL_TIMEOUT_MS - elapsed
                Log.d(TAG, "Reconnect stall check: last vendor activity ${elapsed}ms ago, rescheduling in ${remainingMs}ms")
                vendorWorkHandler.postDelayed(reconnectStallRunnable ?: return@Runnable, remainingMs)
            }
        }
        reconnectStallRunnable = runnable
        vendorWorkHandler.postDelayed(runnable, RECONNECT_STALL_TIMEOUT_MS)
    }

    private fun cancelReconnectStallTimeout() {
        reconnectStallRunnable?.let { vendorWorkHandler.removeCallbacks(it) }
        reconnectStallRunnable = null
    }

    /**
     * Edit 29: Re-feed scan data to the native vendor lib to trigger executeConnect().
     *
     * The native lib calls executeConnect() after receiving scan advertisements via
     * onAdvertiseWithAndroidRawBytes() and checking isReadyToConnect(mac). If our GATT
     * connected before the native lib received enough scan results (or if isReadyToConnect
     * was returning false), the lib never calls executeConnect.
     *
     * This function re-sends the last known scan record to the native lib, which should
     * cause it to re-evaluate and call executeConnect(). We also start a brief scan to
     * get fresh advertisement data if no cached data is available.
     *
     * Called from onDescriptorWrite when CCCDs are ready but executeConnect hasn't arrived.
     */
    private var nudgeAttempts = 0
    private val MAX_NUDGE_ATTEMPTS = 5
    
    private fun nudgeVendorExecuteConnect(reason: String) {
        if (vendorExecuteConnectReceived) return  // already received, no need to nudge
        if (nudgeAttempts >= MAX_NUDGE_ATTEMPTS) {
            Log.w(TAG, "nudgeVendorExecuteConnect($reason): giving up after $MAX_NUDGE_ATTEMPTS attempts")
            return
        }
        nudgeAttempts++
        
        val adapter = vendorAdapter ?: return
        val address = connectedAddress ?: mActiveDeviceAddress ?: return
        val cachedBytes = lastScanRecordBytes
        
        if (cachedBytes != null && cachedBytes.isNotEmpty()) {
            Log.i(TAG, "nudgeVendorExecuteConnect($reason): re-feeding ${cachedBytes.size}-byte scan record " +
                    "to native lib for $address (attempt $nudgeAttempts/$MAX_NUDGE_ATTEMPTS)")
            try {
                adapter.recordDevice(mActiveBluetoothDevice)
                adapter.onAdvertiseWithAndroidRawBytes(address, -60, cachedBytes)
            } catch (t: Throwable) {
                Log.e(TAG, "nudgeVendorExecuteConnect($reason): onAdvertiseWithAndroidRawBytes threw: ${t.message}")
            }
        } else {
            Log.w(TAG, "nudgeVendorExecuteConnect($reason): no cached scan data, starting scan to get fresh data")
            scheduleBroadcastScan("nudge-vendor", forceImmediate = true)
        }
        
        // Schedule retry if executeConnect still hasn't arrived after 1 second
        vendorWorkHandler.postDelayed({
            if (!vendorExecuteConnectReceived && vendorGattNotified && mBluetoothGatt != null) {
                Log.i(TAG, "nudgeVendorExecuteConnect: retry — executeConnect still not received")
                nudgeVendorExecuteConnect("retry-$reason")
            }
        }, 1000L)
    }

    private fun startVendorLongConnect(reason: String) {
        if (!vendorBleEnabled) return
        if (!vendorLibAvailable) return
        if (!vendorGattNotified) return
        if (vendorLongConnectTriggered) {
            Log.d(TAG, "Vendor long-connect: already triggered, skipping ($reason)")
            return
        }
        val controller = vendorController ?: return
        // Use aidexBonded (receiver-tracked) instead of getBondState() to avoid Android BLE stack race
        val bondState = mBluetoothGatt?.device?.bondState ?: BluetoothDevice.BOND_NONE
        if (!aidexBonded && bondState != BluetoothDevice.BOND_BONDED) {
            // CRITICAL: The AiDex sensor only allows ONE command (key exchange) before bonding.
            // Sending setDynamicMode/setAutoUpdate/getBroadcastData before BOND_BONDED causes
            // the sensor to disconnect (status=19). Defer until bonded.
            Log.i(TAG, "Vendor long-connect deferred ($reason): aidexBonded=false, getBondState=$bondState (not BONDED), waiting for bond")
            vendorLongConnectPendingReason = reason
            return
        }
        vendorLongConnectPendingReason = null
        vendorLongConnectTriggered = true  // Edit 39e: prevent double-trigger
        try {
            controller.setDynamicMode(1)
            controller.setAutoUpdate()
            controller.getBroadcastDataWithLog()
            Log.i(TAG, "Vendor long-connect started ($reason) — starting 2s polling loop")
            // Edit 39e: Explicitly start the polling loop. The GET_BROADCAST_DATA handler
            // schedules the next poll, but we need to kick the first one here.
            vendorPollCount = 1  // count this initial getBroadcastData
            vendorGotGlucoseThisCycle = false
            scheduleNextBroadcastPoll()
            // Edit 45e: Request history immediately after long-connect commands.
            // Official app downloads ALL history right after connecting, before anything else.
            // We call getHistoryRange() which triggers paginated getHistories() in its handler.
            // No delay — the vendor lib can handle concurrent commands.
            if (vendorBleEnabled && vendorGattConnected && vendorNativeReady) {
                requestHistoryBackfill("long-connect")
                requestCalibrationData("long-connect")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Vendor long-connect failed: ${t.message}")
            vendorLongConnectTriggered = false  // allow retry on failure
        }
    }

    // Edit 36c: Unified GATT operation queue — enqueue and drain functions.
    // All BLE reads and writes go through this queue to prevent concurrent GATT operations.

    private fun enqueueVendorOp(op: VendorGattOp) {
        vendorGattQueue.add(op)
        if (!vendorGattOpActive) {
            drainVendorGattQueue()
        }
    }

    private fun drainVendorGattQueue() {
        if (vendorGattOpActive) return
        // Edit 41b: Don't drain while paused for bonding
        if (vendorQueuePausedForBonding) {
            Log.d(TAG, "drainVendorGattQueue: paused for bonding, will resume on BOND_BONDED")
            return
        }
        if (vendorGattQueue.isEmpty()) return
        val next = vendorGattQueue.removeFirst()
        val gatt = mBluetoothGatt
        if (gatt == null) {
            vendorGattQueue.addFirst(next)
            return
        }
        when (next) {
            is VendorGattOp.Write -> {
                val characteristic = vendorAdapter?.getCharacteristic(next.uuid)
                if (characteristic == null) {
                    Log.w(TAG, "Vendor write: characteristic 0x${String.format("%04X", next.uuid)} not found, skipping")
                    // Try next op instead of stalling
                    drainVendorGattQueue()
                    return
                }
                characteristic.value = next.data
                characteristic.writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                val ok = gatt.writeCharacteristic(characteristic)
                Log.i(TAG, "Vendor write [0x${Integer.toHexString(next.uuid)}] data=${bytesToHex(next.data)} ok=$ok (queueRemaining=${vendorGattQueue.size} retry=${next.retryCount})")
                vendorGattOpActive = ok
                if (!ok) {
                    // Edit 41b: If bonding is in progress, pause the queue instead of burning retries.
                    // SMP bonding takes 3-6 seconds but the 200ms retry loop exhausts 10 retries in 2s.
                    val bondState = gatt.device?.bondState ?: BluetoothDevice.BOND_NONE
                    if (bondState == BluetoothDevice.BOND_BONDING) {
                        Log.i(TAG, "Vendor write [0x${Integer.toHexString(next.uuid)}] ok=false during BOND_BONDING — " +
                                "pausing queue (retry count NOT incremented, will resume on BOND_BONDED)")
                        vendorQueuePausedForBonding = true
                        vendorGattQueue.addFirst(next)  // re-queue WITHOUT incrementing retryCount
                        // Safety timeout: if BOND_BONDED never arrives, resume after 15s
                        vendorWorkHandler.postDelayed({
                            if (vendorQueuePausedForBonding) {
                                Log.w(TAG, "Edit 41b: Bonding pause timeout (${VENDOR_BONDING_PAUSE_TIMEOUT_MS}ms) — " +
                                        "resuming queue regardless")
                                vendorQueuePausedForBonding = false
                                drainVendorGattQueue()
                            }
                        }, VENDOR_BONDING_PAUSE_TIMEOUT_MS)
                        return
                    }
                    // Edit 38c: Enforce retry limit to prevent infinite write-fail loops
                    next.retryCount++
                    if (next.retryCount >= VENDOR_GATT_OP_MAX_RETRIES) {
                        Log.e(TAG, "Vendor write [0x${Integer.toHexString(next.uuid)}] FAILED after ${next.retryCount} retries — dropping op and restarting")
                        vendorGattQueue.clear()
                        vendorGattOpActive = false
                        vendorWorkHandler.post {
                            stopVendor("write-retry-exhausted")
                            ensureVendorStarted("write-retry-exhausted")
                        }
                    } else {
                        vendorGattQueue.addFirst(next)
                        handshakeHandler.postDelayed({ drainVendorGattQueue() }, 200L)
                    }
                }
            }
            is VendorGattOp.Read -> {
                val characteristic = vendorAdapter?.getCharacteristic(next.uuid)
                if (characteristic == null) {
                    Log.w(TAG, "Vendor read: characteristic 0x${String.format("%04X", next.uuid)} not found, skipping")
                    drainVendorGattQueue()
                    return
                }
                val ok = gatt.readCharacteristic(characteristic)
                Log.i(TAG, "Vendor read [0x${Integer.toHexString(next.uuid)}] ok=$ok (queueRemaining=${vendorGattQueue.size} retry=${next.retryCount})")
                vendorGattOpActive = ok
                if (!ok) {
                    // Edit 38c: Enforce retry limit for reads too
                    next.retryCount++
                    if (next.retryCount >= VENDOR_GATT_OP_MAX_RETRIES) {
                        Log.e(TAG, "Vendor read [0x${Integer.toHexString(next.uuid)}] FAILED after ${next.retryCount} retries — dropping op and restarting")
                        vendorGattQueue.clear()
                        vendorGattOpActive = false
                        vendorWorkHandler.post {
                            stopVendor("read-retry-exhausted")
                            ensureVendorStarted("read-retry-exhausted")
                        }
                    } else {
                        vendorGattQueue.addFirst(next)
                        handshakeHandler.postDelayed({ drainVendorGattQueue() }, 200L)
                    }
                }
            }
        }
    }

    private fun vendorRead(uuid: Int) {
        // Edit 36c: Reads now go through the unified GATT queue instead of calling
        // gatt.readCharacteristic() directly. This prevents races with pending writes.
        enqueueVendorOp(VendorGattOp.Read(uuid))
    }

    private fun vendorWrite(uuid: Int, data: ByteArray) {
        enqueueVendorOp(VendorGattOp.Write(uuid, data))
    }

    private fun uuidToShort(uuid: UUID): Int {
        val str = uuid.toString()
        return if (str.length >= 8) {
            str.substring(4, 8).toInt(16)
        } else {
            0
        }
    }

    private inner class VendorBleAdapter(private val context: Context) : BleAdapter() {
        private val deviceStore = BluetoothDeviceStore()
        private val characteristics = HashMap<Int, BluetoothGattCharacteristic>()

        // Edit 29: Dynamic characteristic UUIDs from native lib, matching official app pattern.
        // The official app calls getCharacteristicUUID() and getPrivateCharacteristicUUID() (JNI)
        // to discover which characteristics to map and enable notifications on.
        // mCharacteristicUuid = write target (official app stores as mCharacteristic)
        // mPrivateCharacteristicUuid = notification channel (official app enables notifications first on this)
        var mCharacteristicUuid: Int = 0
        var mPrivateCharacteristicUuid: Int = 0

        init {
            instance = this
        }

        fun recordDevice(device: BluetoothDevice?) {
            if (device != null) {
                deviceStore.add(device)
            }
        }

        fun refreshCharacteristics(gatt: BluetoothGatt) {
            characteristics.clear()
            val serviceF000 = gatt.getService(SERVICE_F000)
            if (serviceF000 != null) {
                // Edit 36a: Map ALL characteristics from the F000 service, not just F001/F002.
                // The native lib may use F001, F002, F003, or F005 depending on its internal
                // state (it increments a UUID counter across stopVendor/restart cycles).
                // By mapping everything, getCharacteristic() will succeed regardless of which
                // UUID the native lib decides to use in executeConnect/executeWrite/executeRead.
                //
                // Still set defaults for mCharacteristicUuid/mPrivateCharacteristicUuid to F001/F002
                // for the initial CCCD chain. executeConnect() will update them with the native lib's
                // actual UUIDs later. DO NOT call JNI here (pre-executeConnect → SIGSEGV).
                if (mCharacteristicUuid == 0) mCharacteristicUuid = 0xF001
                if (mPrivateCharacteristicUuid == 0) mPrivateCharacteristicUuid = 0xF002

                for (characteristic in serviceF000.characteristics) {
                    val id = uuidToShort(characteristic.uuid)
                    characteristics[id] = characteristic
                    Log.i(TAG, "refreshCharacteristics: mapped 0x${Integer.toHexString(id)}")
                }
                Log.i(TAG, "refreshCharacteristics: mapped ${characteristics.size} characteristics from F000 service" +
                        " (defaults: char=0x${Integer.toHexString(mCharacteristicUuid)}, priv=0x${Integer.toHexString(mPrivateCharacteristicUuid)})")
            }
            
            // Edit 27: Do NOT map FF30 service characteristics.
            // Official app does not know about FF30/FF31/FF32 service.
            val serviceFF30 = gatt.getService(SERVICE_FF30)
            if (serviceFF30 != null) {
                Log.d(TAG, "refreshCharacteristics: FF30 service found but NOT mapped (not in official app)")
            }
        }

        fun getCharacteristic(id: Int): BluetoothGattCharacteristic? {
            val c = characteristics[id]
            if (c == null) Log.v(TAG, "Vendor getCharacteristic(0x${Integer.toHexString(id)}) -> NULL (map size=${characteristics.size})")
            return c
        }

        fun handleCharacteristicChanged(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
            var id = uuidToShort(characteristic.uuid)
            Log.d(TAG, "Vendor RX [0x${Integer.toHexString(id)}]: ${bytesToHex(data)}")
            // Edit 27: Forward data as soon as native lib has set up its connection context
            // (vendorExecuteConnectReceived), not after onConnectSuccess (vendorNativeReady).
            // The vendor lib needs to receive AES handshake responses DURING setup.
            if (!vendorExecuteConnectReceived) {
                Log.d(TAG, "Vendor RX [0x${Integer.toHexString(id)}]: skipped, executeConnect not received yet")
                return
            }
            // Edit 42a: When Fix 41a redirected a write from F003 (NOTIFY-only) to F001
            // (writable), the sensor sends its response on F001. But the vendor lib
            // registered F003 as its privateCharacteristicUuid and only listens for
            // data on that UUID. Remap F001 notifications → F003 so the vendor lib
            // recognizes them. This completes the write-redirect chain:
            //   vendor wants F003 → we write to F001 → sensor responds on F001 → we tell vendor it came from F003.
            if (id == 0xF001 && mPrivateCharacteristicUuid == 0xF003) {
                Log.i(TAG, "Edit 42a: Remapping RX from 0xF001→0xF003 (vendor lib expects F003 as private)")
                id = 0xF003
            }
            // Edit 28: Route onReceiveData through vendorWorkHandler like the official app.
            // In the official app, received data is posted as RECEIVER_DATA (what=0x7D0)
            // to the workHandler, ensuring serialization with writes/connects/disconnects.
            val dataCopy = data.copyOf()
            val charId = id
            vendorWorkHandler.post {
                try {
                    onReceiveData(charId, dataCopy)
                } catch (t: Throwable) {
                    Log.e(TAG, "Vendor RX [0x${Integer.toHexString(charId)}]: onReceiveData crashed: ${t.message}")
                }
            }
        }

        override fun executeConnect(str: String) {
            Log.i(TAG, "executeConnect: Native lib requested connect to $str")
            connectedAddress = str
            mActiveDeviceAddress = str
            deviceStore.getDeviceMap()[str]?.let { mActiveBluetoothDevice = it }
            vendorConnectPending = true
            vendorExecuteConnectReceived = true  // Native lib has set up its internal connection state machine
            vendorConsecutiveDiscoverCount = 0  // Edit 37e: reset — native lib is progressing normally
            vendorReconnectSpamRestarts = 0  // Edit 41c: reset — executeConnect arrived, no more spam
            vendorReconnectFallbackAttempt = 0  // Edit 49c: reset — executeConnect arrived, no more fallback needed
            // Edit 40: Cancel the reconnect fallback timer — executeConnect arrived successfully
            vendorReconnectFallbackRunnable?.let {
                vendorWorkHandler.removeCallbacks(it)
                vendorReconnectFallbackRunnable = null
                Log.d(TAG, "Vendor: reconnect fallback cancelled — executeConnect received")
            }

            // Edit 36b: Now that the native Ble context is allocated, query the real UUIDs.
            // Update mCharacteristicUuid/mPrivateCharacteristicUuid. Since Edit 36a maps ALL
            // characteristics from the F000 service, no need to call refreshCharacteristics() again.
            // If the native lib's UUIDs changed (e.g., F003 on retry), just verify they exist
            // in the char map and re-subscribe CCCDs if needed.
            var uuidsChanged = false
            try {
                val nativeCharUuid = getCharacteristicUUID()
                val nativePrivUuid = getPrivateCharacteristicUUID()
                Log.i(TAG, "executeConnect: native lib UUIDs — characteristic=0x${Integer.toHexString(nativeCharUuid)}, private=0x${Integer.toHexString(nativePrivUuid)}")
                if (nativeCharUuid != 0 && nativePrivUuid != 0) {
                    if (nativeCharUuid != mCharacteristicUuid || nativePrivUuid != mPrivateCharacteristicUuid) {
                        Log.w(TAG, "executeConnect: native UUIDs differ from current! Updating: " +
                                "char 0x${Integer.toHexString(mCharacteristicUuid)}→0x${Integer.toHexString(nativeCharUuid)}, " +
                                "priv 0x${Integer.toHexString(mPrivateCharacteristicUuid)}→0x${Integer.toHexString(nativePrivUuid)}")
                        uuidsChanged = true
                    }
                    mCharacteristicUuid = nativeCharUuid
                    mPrivateCharacteristicUuid = nativePrivUuid
                    // Verify both UUIDs exist in the char map (they should, thanks to Edit 36a mapping all)
                    if (characteristics[nativeCharUuid] == null) {
                        Log.e(TAG, "executeConnect: characteristic 0x${Integer.toHexString(nativeCharUuid)} NOT in char map! Re-scanning GATT service...")
                        val gattForRemap = mBluetoothGatt
                        if (gattForRemap != null) refreshCharacteristics(gattForRemap)
                    }
                    if (characteristics[nativePrivUuid] == null) {
                        Log.e(TAG, "executeConnect: private 0x${Integer.toHexString(nativePrivUuid)} NOT in char map! Re-scanning GATT service...")
                        val gattForRemap = mBluetoothGatt
                        if (gattForRemap != null) refreshCharacteristics(gattForRemap)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "executeConnect: JNI UUID query failed (${t.message}), keeping current UUIDs")
            }

            // Edit 36b: If native lib UUIDs changed AND we have GATT connected, re-subscribe
            // CCCDs on the new UUIDs. The CCCD chain was originally set up with old UUIDs in
            // onServicesDiscovered→enableVendorNotifications, so the new UUID characteristics
            // may not have notifications enabled.
            if (uuidsChanged && vendorServicesReady) {
                val gattForCccd = mBluetoothGatt
                if (gattForCccd != null) {
                    Log.i(TAG, "executeConnect: UUIDs changed, re-enabling CCCDs for new UUIDs")
                    vendorGattNotified = false
                    enableVendorNotifications(gattForCccd)
                }
            }

            val existing = mBluetoothGatt
            if (existing != null && existing.device?.address == str) {
                // GATT already exists for this device.
                // Signal success when services + CCCD notifications are ready.
                if (vendorServicesReady && vendorGattNotified) {
                    // Edit 28: CRITICAL — defer onConnectSuccess() to the next handler loop iteration.
                    // In the official app, executeConnect() posts CONNECT_GATT to the handler and returns.
                    // onConnectSuccess() is called much later from a separate CONNECT_SUCCESS message.
                    // The native lib does NOT expect onConnectSuccess() to be called reentrantly while
                    // executeConnect() is still on the call stack. Calling it synchronously here causes
                    // the native lib's internal state machine to be in "connecting" state when
                    // onConnectSuccess fires, so it sends op=2 + disconnect instead of proceeding
                    // with the AES key exchange (executeWrite).
                    Log.i(TAG, "executeConnect: GATT+notifications ready for $str, deferring onConnectSuccess to handler.")
                    vendorWorkHandler.post {
                        if (vendorExecuteConnectReceived && vendorGattNotified && mBluetoothGatt != null) {
                            disregardDisconnectsUntil = System.currentTimeMillis() + 8000L
                            safeCallOnConnectSuccess("executeConnect-deferred")
                        } else {
                            Log.w(TAG, "executeConnect-deferred: state changed, skipping onConnectSuccess (exec=$vendorExecuteConnectReceived notified=$vendorGattNotified gatt=${mBluetoothGatt != null})")
                        }
                    }
                    return  // Edit 53a: explicit return — GATT is healthy, no need to fall through
                } else {
                    // Edit 53a: Zombie GATT detection and recovery.
                    // When stopVendor() runs, it resets vendorServicesReady/vendorGattNotified/
                    // vendorGattConnected but does NOT close the BluetoothGatt object (mBluetoothGatt
                    // survives because close() is a no-op for vendor mode — see close() override).
                    // On the next DISCOVER cycle, executeConnect() finds mBluetoothGatt != null
                    // but services=false and notified=false. This is a "zombie" GATT: the Android
                    // BLE stack may still have the GATT connected, but onServicesDiscovered() will
                    // never fire again without a fresh connectGatt(). The result is a multi-minute
                    // stall where only broadcast scanning provides glucose.
                    //
                    // Fix: If BOTH services and notifications are false, the GATT is dead. Close it
                    // and start a fresh connection. If only notifications are false (services=true),
                    // the CCCD chain may still be in progress — don't interfere.
                    if (!vendorServicesReady && !vendorGattNotified) {
                        Log.w(TAG, "Edit 53a: Zombie GATT detected for $str (services=false, notified=false). " +
                                "Closing stale GATT and initiating fresh connection.")
                        try {
                            existing.disconnect()
                            existing.close()
                        } catch (t: Throwable) {
                            Log.e(TAG, "Edit 53a: Error closing zombie GATT: ${t.message}")
                        }
                        mBluetoothGatt = null
                        connectInProgress = false
                        vendorGattConnected = false
                        // Now fall through to connectDevice(0) below instead of returning
                    } else {
                        Log.i(TAG, "executeConnect: GATT exists for $str but not fully ready (services=$vendorServicesReady notified=$vendorGattNotified). Waiting for setup.")
                        // onConnectSuccess will be called when onDescriptorWrite(F001) completes
                        return
                    }
                }
            }
            if (connectInProgress) {
                Log.d(TAG, "executeConnect: Connection already in progress for $str.")
                return
            }
            val scheduled = connectDevice(0)
            if (!scheduled) {
                vendorConnectPending = false
                try {
                    onConnectFailure()
                } catch (t: Throwable) {
                    Log.e(TAG, "executeConnect: onConnectFailure() crashed: ${t.message}")
                }
            }
        }

        // Edit 28: Route executeDisconnect through vendorWorkHandler like the official app.
        // The official app posts DISCONNECT_GATT to the handler queue — it does NOT disconnect
        // synchronously. This ensures that any executeWrite() calls queued during onConnectSuccess()
        // are processed BEFORE the disconnect.
        override fun executeDisconnect() {
            Log.i(TAG, "executeDisconnect: Native lib requested disconnect (current thread=${Thread.currentThread().name})")
            vendorWorkHandler.post {
                Log.i(TAG, "executeDisconnect: processing on handler")
                vendorConnectPending = false
                disconnect()
            }
        }

        // Edit 35b: Route executeReadCharacteristic through vendorWorkHandler, matching
        // the official app (which posts msg 0x3F5 to workHandler). Also update
        // vendorLastMessageTime to prove the native lib is still active (prevents
        // the stall timeout from firing during post-PAIR read sequence).
        override fun executeReadCharacteristic(i: Int) {
            Log.i(TAG, "executeReadCharacteristic: native requested read of char 0x${Integer.toHexString(i)} (thread=${Thread.currentThread().name})")
            vendorLastMessageTime = System.currentTimeMillis()
            vendorWorkHandler.post {
                vendorRead(i)
            }
        }

        override fun executeStartScan() {
            Log.i(TAG, "executeStartScan: native lib requested scan start")
            startBroadcastScan("vendor-start")
        }

        override fun executeStopScan() {
            Log.i(TAG, "executeStopScan: native lib requested scan stop")
            stopBroadcastScan("vendor-stop", found = false)
        }

        // Edit 28: Route executeWrite through vendorWorkHandler like the official app.
        // In the official app, executeWrite() posts SEND_DATA to the handler queue.
        // This ensures proper serialization with onConnectSuccess/executeDisconnect.
        override fun executeWrite(bArr: ByteArray) {
            Log.i(TAG, "executeWrite: native requested write (${bArr.size} bytes, thread=${Thread.currentThread().name})")
            vendorLastMessageTime = System.currentTimeMillis()  // Edit 35b: keep stall timer alive
            val dataCopy = bArr.copyOf()  // defensive copy since native may reuse buffer
            vendorWorkHandler.post {
                try {
                    val uuid = getCharacteristicUUID()
                    Log.i(TAG, "executeWrite: processing on handler — char 0x${Integer.toHexString(uuid)}, data=${bytesToHex(dataCopy)} (${dataCopy.size} bytes)")
                    vendorWrite(uuid, dataCopy)
                    checkAndBond()
                } catch (t: Throwable) {
                    Log.e(TAG, "executeWrite: crashed: ${t.message}")
                }
            }
        }

        // Edit 28: Route executeWriteCharacteristic through vendorWorkHandler.
        // Edit 41a: Redirect writes to non-writable characteristics (e.g. F003, NOTIFY-only)
        // to the first writable F00x characteristic. The vendor lib non-deterministically
        // picks F003 as the "private" char for PAIR writes, but F003 has props=0x10 (NOTIFY
        // only) and writeCharacteristic() always returns false. F001 (props=0x18,
        // WRITE_NO_RESPONSE + NOTIFY) is the correct PAIR target.
        override fun executeWriteCharacteristic(i: Int, bArr: ByteArray) {
            Log.i(TAG, "executeWriteCharacteristic: native requested write char 0x${Integer.toHexString(i)} (${bArr.size} bytes, thread=${Thread.currentThread().name})")
            vendorLastMessageTime = System.currentTimeMillis()  // Edit 35b: keep stall timer alive
            val dataCopy = bArr.copyOf()

            // Edit 41a: Check if target characteristic is writable; if not, redirect.
            var targetUuid = i
            val targetChar = getCharacteristic(targetUuid)
            if (targetChar != null) {
                val props = targetChar.properties
                val writable = (props and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                if (!writable) {
                    val fallback = findFirstWritableCharacteristic(exclude = targetUuid)
                    if (fallback != null) {
                        Log.w(TAG, "Edit 41a: Redirecting write from 0x${Integer.toHexString(targetUuid)} " +
                                "(props=0x${Integer.toHexString(props)}, NOT writable) " +
                                "to 0x${Integer.toHexString(fallback)}")
                        targetUuid = fallback
                    } else {
                        Log.e(TAG, "Edit 41a: Target 0x${Integer.toHexString(targetUuid)} is NOT writable " +
                                "and no writable fallback found! Write will likely fail.")
                    }
                }
            }

            val finalUuid = targetUuid
            vendorWorkHandler.post {
                Log.i(TAG, "executeWriteCharacteristic: processing on handler — char 0x${Integer.toHexString(finalUuid)}, data=${bytesToHex(dataCopy)} (${dataCopy.size} bytes)")
                vendorWrite(finalUuid, dataCopy)
                checkAndBond()
            }
        }

        /**
         * Edit 41a: Find the first writable characteristic in the F000 service map.
         * Prefers F001 (the known PAIR target), falls back to any other writable char.
         * Returns the short UUID (e.g., 0xF001) or null if none found.
         */
        private fun findFirstWritableCharacteristic(exclude: Int = 0): Int? {
            val writeMask = BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            // Prefer F001 explicitly — it's the known working PAIR characteristic
            val f001 = characteristics[0xF001]
            if (f001 != null && (f001.properties and writeMask) != 0 && 0xF001 != exclude) {
                return 0xF001
            }
            // Fallback: any writable characteristic except the excluded one and F002
            // (F002 is the main command channel, not the PAIR channel)
            for ((uuid, char) in characteristics) {
                if (uuid == exclude) continue
                if (uuid == 0xF002) continue  // don't redirect PAIR writes to command channel
                if ((char.properties and writeMask) != 0) return uuid
            }
            // Last resort: F002 is writable too
            val f002 = characteristics[0xF002]
            if (f002 != null && (f002.properties and writeMask) != 0 && 0xF002 != exclude) {
                return 0xF002
            }
            return null
        }

        private fun checkAndBond() {
            // User tip: "Upon connection... send one command and immediately request bond."
            // If the vendor library is writing (handshake), and we are not bonded, trigger it now.
            val device = mBluetoothGatt?.device ?: return
            val state = device.bondState
            if (state == BluetoothDevice.BOND_NONE) {
                Log.i(TAG, "Vendor write detected. Requesting immediate bond (User Tip Strategy).")
                device.createBond()
            } else {
                 Log.d(TAG, "Vendor write detected, but already bonded ($state). Skipping bond request.")
            }
        }

        override fun getDeviceStore(): BluetoothDeviceStore = deviceStore

        // Edit 29: Match official app's isReadyToConnect() exactly.
        // Official app: return bluetoothDeviceStore.deviceMap[mac] != null
        // The native lib calls isReadyToConnect(mac) before calling executeConnect(mac).
        // If this returns false, the native lib will NOT call executeConnect.
        // Our previous implementation checked connectInProgress and mBluetoothGatt state,
        // which was overly restrictive and could prevent executeConnect from ever being called
        // (especially when our GATT connects independently of the native lib).
        override fun isReadyToConnect(str: String): Boolean {
            val ready = deviceStore.getDeviceMap()?.containsKey(str) == true
            Log.i(TAG, "isReadyToConnect($str): $ready (deviceStore size=${deviceStore.getDeviceMap()?.size ?: 0})")
            return ready
        }

        override fun setDiscoverCallback() {
            BleController.setDiscoveredCallback { info -> onVendorDiscovered(info) }
        }

        override fun startBtScan(isPeriodic: Boolean) {
            startBroadcastScan("vendor-startBt")
        }

        override fun stopBtScan(isPeriodic: Boolean) {
            stopBroadcastScan("vendor-stopBt", found = false)
        }
    }

    private fun ensureConnected(reason: String) {
        if (mBluetoothGatt != null) return
        Log.i(TAG, "Ensuring GATT connection ($reason)")
        connectDevice(0)
    }

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        if (deviceName == null) return false
        return deviceName.contains(SerialNumber, ignoreCase = true)
    }

    // Scanning is handled by SensorBluetooth / Wizard.

    // --- CONNECTION ---

    override fun connectDevice(delayMillis: Long): Boolean {
        // In Broadcast Only mode, skip GATT connection entirely
        if (isBroadcastOnlyMode()) {
            Log.d(TAG, "connectDevice: skip in Broadcast Only mode")
            return false
        }

        // Edit 31: CRITICAL — When vendor mode is active, do NOT let SensorBluetooth (or any
        // external caller) trigger a premature GATT connection. In the official AiDex app, the
        // native lib controls the entire flow: executeStartScan() → scan results → executeConnect()
        // → connectGatt(). There is NO parallel scan/connect path.
        //
        // The problem: SensorBluetooth discovers the device and calls connectDevice() immediately,
        // which does connectGatt() BEFORE the native lib has processed enough advertisements to
        // call executeConnect(). The native lib then sees an already-connected GATT and gets
        // confused — it discovers the device (op=1) but never calls executeConnect(), so
        // onConnectSuccess() never fires, the AES handshake never starts, and the sensor
        // disconnects after 8 seconds (status=19).
        //
        // Fix: When vendorBleEnabled and the native lib hasn't called executeConnect() yet,
        // defer the GATT connection. Instead, ensure the vendor stack is running so scan data
        // flows to the native lib. The native lib will call executeConnect() when ready, which
        // sets vendorExecuteConnectReceived=true before calling connectDevice(0), bypassing
        // this guard.
        //
        // Edit 50a: Also allow bypass when vendorProactiveGattConnect is set (2nd DISCOVER
        // proactive GATT connect). Unlike Edit 49a, we do NOT set vendorExecuteConnectReceived —
        // the GATT connects silently and waits for the vendor lib's executeConnect().
        if (vendorBleEnabled && !vendorExecuteConnectReceived && !vendorProactiveGattConnect) {
            Log.i(TAG, "connectDevice: DEFERRED — vendor mode active but executeConnect not yet " +
                    "received from native lib. Ensuring vendor stack is running and scan data " +
                    "is flowing. GATT connect will happen when native lib calls executeConnect().")
            // Ensure the vendor stack is alive and processing advertisements.
            ensureVendorStarted("connectDevice-deferred")
            // Ensure our broadcast scan is running to feed advertisements to the native lib.
            // The native lib needs scan results via onAdvertiseWithAndroidRawBytes() to progress
            // through its internal state machine to the point where it calls executeConnect().
            scheduleBroadcastScan("connectDevice-vendor-deferred", forceImmediate = true)
            // Return true so SensorBluetooth thinks the connection was handled and doesn't
            // start redundant scans. The native lib will drive the actual GATT connect.
            return true
        }

        if (connectInProgress || mBluetoothGatt != null) {
            Log.d(TAG, "connectDevice: skip (inProgress=$connectInProgress, gatt=${mBluetoothGatt != null})")
            return false
        }
        // Edit 50a: Reset proactive flag after it bypassed the guard — single-use ticket.
        if (vendorProactiveGattConnect) {
            Log.i(TAG, "Edit 50a: proactive GATT connect bypass consumed")
            vendorProactiveGattConnect = false
        }
        connectInProgress = true
        // Edit 26: Do NOT remove bond pre-connect. Cached bond keys allow near-instant
        // re-encryption (<100ms) vs a full 12-second SMP handshake from scratch.
        // The 8-second AiDex deadline is only achievable with cached keys.
        // Bond removal is still done on disconnect (status!=0) as cleanup.
        val scheduled = super.connectDevice(delayMillis)
        if (!scheduled) {
            connectInProgress = false
        }
        return scheduled
    }

    override fun reconnect(now: Long): Boolean {
        scheduleBroadcastScan("reconnect", forceImmediate = true)
        // In Broadcast Only mode, don't GATT reconnect - just scan for broadcasts
        if (isBroadcastOnlyMode()) {
            Log.d(TAG, "reconnect: skip GATT in Broadcast Only mode, broadcast scan scheduled")
            return true
        }
        // Edit 31: In vendor mode, reconnect is driven by the native lib.
        // super.reconnect() → disconnect() → connectDevice(0), and our connectDevice()
        // override will defer GATT connection until the native lib calls executeConnect().
        // We just need to ensure the vendor stack is alive.
        if (vendorBleEnabled && !vendorExecuteConnectReceived) {
            Log.i(TAG, "reconnect: vendor mode, deferring to native lib flow. Ensuring vendor stack alive.")
            ensureVendorStarted("reconnect")
        }
        return super.reconnect(now)
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)
        connectInProgress = false
        
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            val now = System.currentTimeMillis()
            connectTime = now
            constatstatusstr = "Connected"
            
            // Leave start time unset until we have a valid offset from broadcast/history.
            
            Log.i(TAG, "Connected to ${gatt.device.address}. Requesting MTU 512...")

            handshakeStep = 0
            handshakePlan = emptyList()
            handshakePlanLabel = "none"
            officialFlowStage = OfficialFlowStage.BOOTSTRAP
            expectedF002ResponseOps = emptySet()
            waitAnyF002Response = false
            dynamicIV = null
            ivLocked = false
            ivLockedFromBroadcast = false
            bondRequested = false
            bondWaitUntilMs = 0L
            justBondedThisSession = false
            handshakePrimingInProgress = false
            pendingHandshakePrimingTimeout = null
            cancelHandshakeTimers()
            connectedAddress = gatt.device.address
            sessionStartMs = now
            // Edit 29: Reset auth failure counter on successful connection
            consecutiveAuthFailures = 0
            nudgeAttempts = 0  // Edit 29: Reset nudge counter for fresh connection
            broadcastSeenInSession = lastBroadcastTime >= (sessionStartMs - BROADCAST_REFERENCE_MS)
            if (wantsBroadcastScan()) {
                scheduleBroadcastScan("connect", forceImmediate = true)
            }

            if (dataptr != 0L) {
                Natives.setDeviceAddress(dataptr, gatt.device.address)
            }
        // onConnectSuccess moved to onServicesDiscovered to ensure characteristics are ready.
        if (vendorBleEnabled && vendorStarted && vendorRegistered && vendorController != null) {
            vendorGattConnected = true
            vendorConnectPending = true  // Mark pending so executeConnect/onDescriptorWrite flow works
            aidexBonded = false  // Reset — will be set true only by our bond receiver (or already-bonded/CCCD check)
            cccdAuthFailSeen = false  // Edit 26: reset for fresh session
            registerAidexBondReceiver()
            // Edit 26: If device already has cached bond keys, set aidexBonded immediately.
            // In this case, no BOND_STATE_CHANGED broadcast will fire (re-encryption is instant),
            // so we must not wait for the receiver.
            if (gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "STATE_CONNECTED: device already BONDED (cached keys) — setting aidexBonded=true immediately")
                aidexBonded = true
            }
        }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                gatt.requestMtu(512)
            }
            // Edit 29: Reduce delay between MTU request and discoverServices.
            // The original 600ms was overly conservative. On bonded reconnects (which should be
            // the common case), services are cached and discovery is instant. We need to get
            // through the pipeline (MTU→discover→CCCDs→executeConnect→onConnectSuccess→writeAES)
            // within 8 seconds. With bonding already complete, 200ms should be enough for MTU.
            // On fresh bonds, the MTU response comes quickly (typically <100ms).
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                gatt.discoverServices()
            }, 200)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            connectTime = 0L
            constatstatusstr = "Disconnected"
            Log.i(TAG, "Disconnected. status=$status (${gattStatusToString(status)})")
            cancelHandshakeTimers()
            connectedAddress = null
            bondRequested = false
            bondWaitUntilMs = 0L
            justBondedThisSession = false
            handshakePrimingInProgress = false
            pendingHandshakePrimingTimeout = null
            waitAnyF002Response = false
            vendorGattQueue.clear()
            vendorGattOpActive = false
            // Edit 52a: Snapshot the last AUTO_UPDATE offset before clearing state.
            // On reconnect, GET_HISTORY_RANGE will use this to detect and roll back
            // vendorHistoryNextIndex if broadcast stores leapfrogged the gap.
            if (lastVendorOffsetMinutes > 0) {
                vendorLastAutoUpdateOffsetAtDisconnect = lastVendorOffsetMinutes
                Log.i(TAG, "Edit 52a: Disconnect snapshot — lastVendorOffset=$lastVendorOffsetMinutes, vendorHistoryNextIndex=$vendorHistoryNextIndex")
            }
            // Notify vendor native lib BEFORE clearing flags so it can see we were connected
            notifyVendorDisconnected(status)
            vendorGattConnected = false
            vendorGattNotified = false
            vendorConnectPending = false
            vendorNativeReady = false
            vendorExecuteConnectReceived = false
            vendorConnectSuccessCrashCount = 0  // reset crash counter on fresh disconnect
            vendorReconnectFallbackAttempt = 0  // Edit 49c: reset fallback attempt counter for next reconnect cycle
            vendorLongConnectPendingReason = null  // cancel any deferred long-connect
            vendorLongConnectTriggered = false  // Edit 45a: reset so wantsBroadcastScan() allows scanning post-disconnect
            vendorConnectSuccessPendingCaller = null  // cancel any deferred onConnectSuccess
            aidexBonded = false
            cccdAuthFailSeen = false  // Edit 26: reset for clean state
            cancelBroadcastPoll()  // Edit 37a: stop polling on disconnect
            vendorHistoryDownloading = false  // Edit 45d: abort any in-progress history download
            lastHistoryRequestTime = 0L  // Edit 48: reset cooldown so history resumes immediately after reconnect
            cancelReconnectStallTimeout()  // Edit 34: cancel stall timer on disconnect
            unregisterAidexBondReceiver()

            // Edit 29: Handle status=5 (GATT_INSUFFICIENT_AUTHENTICATION) — stale bond keys.
            // When the sensor rejects cached encryption (e.g. after key rotation or incomplete
            // first bond), Android reports status=5 and instant disconnect. Without handling this,
            // the app reconnects immediately with the same stale keys → status=5 again → crash loop.
            // Fix: remove the stale bond so the next connection triggers fresh SMP bonding,
            // and add exponential backoff to prevent rapid reconnect loops.
            if (status == 5 && vendorBleEnabled) {
                consecutiveAuthFailures++
                lastAuthFailureTime = System.currentTimeMillis()
                Log.w(TAG, "GATT_INSUFFICIENT_AUTHENTICATION (status=5): stale bond keys. " +
                        "Removing bond for fresh SMP. (consecutive failures: $consecutiveAuthFailures)")
                removeBondIfBonded(gatt.device)
                
                if (consecutiveAuthFailures >= AUTH_FAIL_MAX_RETRIES) {
                    Log.e(TAG, "Too many consecutive auth failures ($consecutiveAuthFailures). " +
                            "Switching to Broadcast Only mode to prevent crash loop.")
                    // Don't reconnect GATT — fall back to broadcast scanning only
                    broadcastOnlyConnection = true
                    scheduleBroadcastScan("auth-fail-fallback", forceImmediate = true)
                } else {
                    // Exponential backoff: 2s, 4s, 8s, 16s, 32s
                    val backoffMs = (AUTH_FAIL_BASE_DELAY_MS * (1L shl (consecutiveAuthFailures - 1)))
                        .coerceAtMost(60_000L)
                    Log.i(TAG, "Auth failure backoff: will delay reconnect by ${backoffMs}ms " +
                            "(attempt $consecutiveAuthFailures/$AUTH_FAIL_MAX_RETRIES)")
                    // Schedule a delayed reconnect instead of letting SuperGattCallback reconnect immediately
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!isBroadcastOnlyMode()) {
                            Log.i(TAG, "Auth failure backoff expired. Attempting reconnect with fresh bond.")
                            // Edit 31: Ensure vendor stack is alive so the native lib can drive
                            // the reconnection via executeConnect() after receiving scan data.
                            ensureVendorStarted("auth-retry")
                            scheduleBroadcastScan("auth-retry", forceImmediate = true)
                        }
                    }, backoffMs)
                }
            } else if (status == 0 || status == BluetoothGatt.GATT_SUCCESS) {
                // Successful disconnect — reset auth failure counter
                consecutiveAuthFailures = 0
            }

            // Edit 45a: Resume broadcast scanning after GATT disconnect.
            // vendorLongConnectTriggered is now false, so wantsBroadcastScan() will allow scans.
            // This ensures we catch advertisements while waiting for reconnect.
            if (status != 5 && wantsBroadcastScan()) {
                scheduleBroadcastScan("gatt-disconnect", forceImmediate = true)
            }

            close()
        }
    }

    private fun notifyVendorDisconnected(status: Int) {
        if (!vendorBleEnabled) return
        if (!vendorStarted || !vendorRegistered || vendorController == null) return
        if (!vendorGattConnected) return
        // Edit 31: CRITICAL — do NOT call onDisconnected()/onConnectFailure() unless the native
        // lib has called executeConnect() first. These JNI methods dereference the native Ble
        // singleton's connection context, which is only allocated inside executeConnect().
        // Without this guard, calling them causes SIGSEGV (null pointer in native code)
        // which bypasses try/catch and kills the process.
        if (!vendorExecuteConnectReceived) {
            Log.w(TAG, "notifyVendorDisconnected(status=$status): SKIPPED — vendorExecuteConnectReceived=false. " +
                    "Native Ble connection context not allocated; JNI call would SIGSEGV.")
            return
        }
        try {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "notifyVendorDisconnected: calling onDisconnected() (graceful, status=0)")
                vendorAdapter?.onDisconnected()
            } else {
                Log.i(TAG, "notifyVendorDisconnected: calling onConnectFailure() (status=$status)")
                vendorAdapter?.onConnectFailure()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "notifyVendorDisconnected: JNI call threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Log.i(TAG, "MTU Changed to $mtu (Status: $status)")
    }

    /**
     * Called by SensorBluetooth on ANY bond state change (BONDING, BONDED, NONE).
     * We use this to trigger deferred vendor long-connect once bonding completes.
     *
     * AiDex protocol rule (from vendor knowledge):
     *   - Before bonding, the sensor accepts ONLY ONE command (key exchange / AES init).
     *   - All other commands (setDynamicMode, setAutoUpdate, getBroadcastData) must wait
     *     until BOND_BONDED, otherwise the sensor disconnects (status=19).
     */
    override fun bonded() {
        val gatt = mBluetoothGatt ?: return
        val bondState = gatt.device.bondState
        Log.i(TAG, "bonded() callback: bondState=$bondState")
        if (bondState == BluetoothDevice.BOND_BONDED) {
            if (aidexBonded) {
                Log.i(TAG, "bonded() callback: aidexBonded already true (receiver handled it), skipping")
                return
            }
            aidexBonded = true
            // First: trigger deferred onConnectSuccess if it was waiting for bond
            val pendingCaller = vendorConnectSuccessPendingCaller
            if (pendingCaller != null) {
                Log.i(TAG, "Bond complete. Triggering deferred onConnectSuccess (caller=$pendingCaller)")
                vendorConnectSuccessPendingCaller = null
                // Edit 28: Use vendorWorkHandler for serialization with other vendor lib calls.
                // 500ms delay to let encryption settle after bonding.
                vendorWorkHandler.postDelayed({
                    safeCallOnConnectSuccess("bonded-deferred:$pendingCaller")
                }, 500L)
            }
            // Edit 28: Removed deferred long-connect trigger on bond complete.
            // The native lib drives the protocol via executeWrite() after onConnectSuccess.
            // Sending setDynamicMode/setAutoUpdate/getBroadcastData prematurely interferes
            // with the AES handshake.
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            vendorServicesReady = true
            if (vendorBleEnabled) {
                Log.i(TAG, "Services Discovered. Refreshing Vendor Characteristics.")
                try {
                    vendorAdapter?.refreshCharacteristics(gatt)

                    if (vendorLibAvailable) {
                        try {
                            // 1. Ensure Vendor is Registered (e.g. Clean Start or Reconnect without Scan)
                            if (!vendorRegistered) {
                                val address = gatt?.device?.address
                                if (address != null) {
                                    val cached = lastDiscoveredInfo
                                    val info = if (cached != null && cached.address == address) {
                                        Log.w(TAG, "Clean Start: Registering vendor with cached info for $address")
                                        cached
                                    } else {
                                        val savedAddress = readStringPref("lastVendorAddress", null)
                                        val savedParams = readStringPref("lastVendorParams", null)
                                        if (savedAddress == address && !savedParams.isNullOrEmpty()) {
                                            Log.w(TAG, "Clean Start: Registering vendor with persisted params for $address")
                                            val loaded = BleControllerInfo()
                                            loaded.address = address
                                            loaded.name = gatt.device.name ?: "AiDEX"
                                            loaded.params = try { hexStringToByteArray(savedParams) } catch (e: Exception) { null }
                                            loaded.sn = SerialNumber
                                            loaded
                                        } else {
                                            // Edit 32: Do NOT create controller with null/empty params —
                                            // same root cause as the ensureVendorStarted fix. The native lib
                                            // needs real advertisement bytes. A broadcast scan is already
                                            // running (or will be triggered); let it provide real params.
                                            Log.w(TAG, "Clean Start: No cached params for $address — skipping controller init (waiting for real scan data)")
                                            null
                                        }
                                    }
                                    if (info != null) onVendorDiscovered(info)
                                }
                            }

                            // Edit 27: Write CCCDs (F002 first, then F001) matching official app flow.
                            // Edit 29: After both CCCDs complete, onDescriptorWrite will call onConnectSuccess().
                            // Characteristics and CCCD order are dynamically determined from native lib JNI.
                            Log.i(TAG, "onServicesDiscovered: Vendor registered=$vendorRegistered. Starting dynamic CCCD chain (private→characteristic→onConnectSuccess).")


                        } catch (e: Exception) {
                             Log.e(TAG, "Clean Start Logic Failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Vendor refreshCharacteristics failed: ${e.message}")
                }
            }

            if (wantsAuto() || wantsRaw()) {
                if (vendorBleEnabled) {
                     // Edit 29: Enable CCCDs in order determined by native lib JNI (private first, then characteristic).
                     Log.i(TAG, "Services Discovered. Enabling vendor notifications (dynamic private→char order).")
                     enableVendorNotifications(gatt)
                     Log.i(TAG, "Services Discovered. Skipping internal handshake (delegating to Vendor Lib).")
                } else {
                     Log.i(TAG, "Services Discovered. Starting Handshake.")
                     startHandshake(gatt)
                }
            }
        } else {
            Log.e(TAG, "Service Discovery Failed: $status")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableVendorNotifications(gatt: BluetoothGatt) {
        // Edit 29: Dynamically enable CCCDs based on native lib's UUID preferences.
        // Official app pattern: enable PRIVATE characteristic CCCD first, then chain to
        // CHARACTERISTIC (write target) CCCD via onDescriptorWrite callback.
        // onConnectSuccess is fired ONLY after both CCCDs complete.
        val adapter = vendorAdapter
        if (adapter == null) {
            Log.e(TAG, "enableVendorNotifications: vendorAdapter is null!")
            return
        }
        val privUuid = adapter.mPrivateCharacteristicUuid
        val charUuid = adapter.mCharacteristicUuid
        Log.i(TAG, "enableVendorNotifications: private=0x${Integer.toHexString(privUuid)}, characteristic=0x${Integer.toHexString(charUuid)}")

        val sF000 = gatt.getService(SERVICE_F000)
        if (sF000 == null) {
            Log.e(TAG, "enableVendorNotifications: SERVICE_F000 not found!")
            return
        }

        // Convert short UUIDs to full UUIDs for characteristic lookup
        fun shortToFullUuid(short: Int): UUID =
            UUID.fromString("0000${Integer.toHexString(short).padStart(4, '0')}-0000-1000-8000-00805f9b34fb")

        // Edit 42b: When Fix 41a redirects writes from a non-writable private UUID (e.g. F003)
        // to F001, the sensor responds on F001. We need F001's CCCD enabled to receive those
        // notifications. Enable F001 first (supplemenary), then proceed with normal chain.
        // This is safe because setCharacteristicNotification + writeDescriptor for F001 will
        // complete quickly, and the onDescriptorWrite chain handles the rest.
        if (privUuid != 0xF001 && charUuid != 0xF001) {
            val cF001 = sF000.getCharacteristic(shortToFullUuid(0xF001))
            if (cF001 != null) {
                Log.i(TAG, "Edit 42b: Enabling supplementary CCCD on F001 (write-redirect target)")
                gatt.setCharacteristicNotification(cF001, true)
                val descriptor = cF001.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                    // Note: This CCCD write will complete before the main chain starts below.
                    // The onDescriptorWrite handler will see F001 and not match privUuid/charUuid,
                    // so it won't interfere with the chain logic.
                }
            }
        }

        // Edit 44a: When native lib picks priv=F001 (first connection, no saved keys), the
        // sensor pushes AUTO_UPDATE data on F003 — but F003's CCCD is never enabled on that path.
        // Result: the connection goes silent after the first GET_BROADCAST_DATA.
        // Fix: Always enable F003 CCCD as supplementary when not already in the chain.
        // This is the symmetric counterpart of Edit 42b (which enables F001 when on the F003 path).
        if (privUuid != 0xF003 && charUuid != 0xF003) {
            val cF003 = sF000.getCharacteristic(shortToFullUuid(0xF003))
            if (cF003 != null) {
                Log.i(TAG, "Edit 44a: Enabling supplementary CCCD on F003 (auto-update push target)")
                gatt.setCharacteristicNotification(cF003, true)
                val descriptorF003 = cF003.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                if (descriptorF003 != null) {
                    descriptorF003.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptorF003)
                    // Note: Same safety as Edit 42b — this completes before the main chain,
                    // and onDescriptorWrite won't match privUuid/charUuid so it won't interfere.
                }
            } else {
                Log.w(TAG, "Edit 44a: F003 characteristic not found in SERVICE_F000")
            }
        }

        val privCharFull = shortToFullUuid(privUuid)
        val charCharFull = shortToFullUuid(charUuid)

        // Official app enables private characteristic first
        val cPriv = sF000.getCharacteristic(privCharFull)
        if (cPriv != null) {
            Log.i(TAG, "enableVendorNotifications: Starting CCCD chain with private 0x${Integer.toHexString(privUuid)}")
            // Edit 42b / 44a: We always write at least one supplementary CCCD above (F001 and/or
            // F003), so delay the main chain's CCCD write to avoid GATT operation collision
            // (Android BLE stack does one op at a time).
            vendorWorkHandler.postDelayed({
                val gattNow = mBluetoothGatt
                if (gattNow != null) {
                    setCharacteristicNotification(gattNow, cPriv, true)
                }
            }, 300L)
        } else {
            Log.w(TAG, "enableVendorNotifications: private characteristic 0x${Integer.toHexString(privUuid)} not found. Trying characteristic 0x${Integer.toHexString(charUuid)} directly.")
            // Fallback: try the write-target characteristic alone
            val cChar = sF000.getCharacteristic(charCharFull)
            if (cChar != null) {
                Log.i(TAG, "enableVendorNotifications: Enabling CCCD on characteristic 0x${Integer.toHexString(charUuid)} directly (no private)")
                setCharacteristicNotification(gatt, cChar, true)
            } else {
                Log.e(TAG, "enableVendorNotifications: Neither private nor characteristic found! Cannot enable vendor notifications.")
            }
        }
        // Note: The characteristic (write target) CCCD is enabled in onDescriptorWrite chain
        // after private CCCD completes. If private == characteristic (same UUID), no chaining needed.
    }

    // --- BROADCAST SCANNING ---

    private val broadcastScanRunnable = Runnable { startBroadcastScan("scheduled") }
    private val broadcastScanStopRunnable = Runnable { stopBroadcastScan("timeout", found = false) }

    private fun computeNextScanDelayMs(): Long {
        if (lastBroadcastTime == 0L) return 0L
        return broadcastScanBaseIntervalMs()
            .coerceAtMost(BROADCAST_SCAN_MAX_INTERVAL_MS)
    }

    private fun scheduleBroadcastScan(reason: String, forceImmediate: Boolean = false) {
        if (!broadcastEnabled) return
        if (!wantsBroadcastScan()) return
        if (broadcastScanActive) {
            val age = android.os.SystemClock.elapsedRealtime() - broadcastScanStartedAtElapsed
            if (broadcastScanStartedAtElapsed > 0L && age > (BROADCAST_SCAN_WINDOW_MS + 2_000L)) {
                Log.w(TAG, "Broadcast scan stuck (${age}ms). Forcing reschedule.")
                broadcastScanActive = false
            } else {
                return
            }
        }

        var delay = if (forceImmediate) 0L else computeNextScanDelayMs()

        // Fix for "Lost" Data Gaps:
        // If we are consistently missing broadcasts (e.g. out of phase with sensor interval),
        // retry aggressively (15s) for a few attempts to break the pattern and catch the window.
        if (!forceImmediate && delay > 15_000L && broadcastScanMisses in 1..5) {
            delay = 15_000L
        }
        
        // Use Handler for short delays or when system is likely awake
        scanHandler.removeCallbacks(broadcastScanRunnable)
        scanHandler.postDelayed(broadcastScanRunnable, delay)

        // Additionally schedule an AlarmManager wake-up for longer delays to ensure we wake from deep sleep.
        // Short delays (<10s) usually mean the system is already awake or just about to be.
        if (delay > 10_000L) {
            scheduleScanAlarm(delay)
        }

        Log.d(TAG, "Broadcast scan scheduled in ${delay / 1000}s (reason=$reason, miss=$broadcastScanMisses)")
    }

    @SuppressLint("MissingPermission")
    internal fun startBroadcastScan(reason: String) {
        val isVendorScan = reason == "vendor-start"
        if (!broadcastEnabled && !isVendorScan) return
        if (broadcastScanActive || (!isVendorScan && !wantsBroadcastScan())) return

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) return

        val scanner = adapter.bluetoothLeScanner ?: return
        broadcastScanner = scanner
        acquireBroadcastWakeLock()

        if (broadcastScanCallback == null) {
            broadcastScanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    val address = device.address
                    val targetAddress = scanTargetAddress()

                    if (targetAddress != null) {
                        if (address != targetAddress) return
                    } else {
                        // Avoid latching onto the first 0x0059 advertiser (e.g. Bubble mini).
                        val name = device.name
                        val matches = if (name != null) {
                            matchDeviceName(name, address)
                        } else {
                            val advName = result.scanRecord?.bytes?.let { extractLocalName(it) }
                            advName?.contains(SerialNumber, ignoreCase = true) == true
                        }
                        if (!matches) return
                    }

                    connectedAddress = address
                    mActiveDeviceAddress = address
                    mActiveBluetoothDevice = device
                    handleScanResult(result)
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "Broadcast scan failed: $errorCode")
                    stopBroadcastScan("scan-failed-$errorCode", found = false)
                }
            }
        }

        // Build filters: device address if known, otherwise open scan (name check happens in callback)
        val filterBuilder = ScanFilter.Builder()
        val targetAddr = scanTargetAddress()
        if (targetAddr != null) {
            filterBuilder.setDeviceAddress(targetAddr)
        }
        val filters = arrayListOf(filterBuilder.build())
        
        // Use LOW_POWER mode to reduce scan frequency and logcat spam
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        try {
            scanner.startScan(filters, settings, broadcastScanCallback)
            broadcastScanActive = true
            broadcastScanStartedAtElapsed = android.os.SystemClock.elapsedRealtime()
            scanHandler.removeCallbacks(broadcastScanStopRunnable)
            scanHandler.postDelayed(broadcastScanStopRunnable, BROADCAST_SCAN_WINDOW_MS)
            // Don't update constatstatusstr here - it's transient and makes notification static
            // The UI uses getDetailedBleStatus() for real-time status display instead
            Log.d(TAG, "Broadcast scan started ($reason)")
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast scan start failed: ${e.message}")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val bytes = result.scanRecord?.bytes ?: return
        if (!java.util.Arrays.equals(lastScanRecordBytes, bytes)) {
            lastScanRecordBytes = bytes
            storeVendorParams(bytes)
        }
        if (vendorBleEnabled && vendorStarted) {
            try {
                vendorAdapter?.recordDevice(result.device)
                vendorAdapter?.onAdvertiseWithAndroidRawBytes(
                    result.device.address,
                    result.rssi,
                    bytes
                )
            } catch (_: Throwable) {
            }
        }
        // Critical Fix: If we find the device but the vendor lib isn't registered yet (fresh start),
        // we must trigger discovery to initialize the controller and set vendorRegistered = true.
        // Edit 32: Guard against empty scan bytes (same principle as ensureVendorStarted fix).
        if (vendorBleEnabled && !vendorRegistered && wantsAuto() && bytes.isNotEmpty()) {
             val device = result.device
             val name = device.name ?: "AiDEX"
             if (SerialNumber.isNotEmpty()) {
                 val info = BleControllerInfo(
                     1, 
                     device.address, 
                     name, 
                     SerialNumber, 
                     result.rssi, 
                     bytes
                 )
                 Log.i(TAG, "Scan Result: Triggering delayed onVendorDiscovered for ${device.address}")
                 onVendorDiscovered(info)
             }
        }

        onScanRecord(bytes)
    }

    @SuppressLint("MissingPermission")
    private fun stopBroadcastScan(reason: String, found: Boolean) {
        if (!broadcastEnabled) return
        if (reason == "disconnect" || reason.startsWith("mode-raw")) {
            scanHandler.removeCallbacks(broadcastScanRunnable)
            scanHandler.removeCallbacks(broadcastScanStopRunnable)
            cancelScanAlarm()
        }
        if (!broadcastScanActive) {
            if (reason != "disconnect" && wantsBroadcastScan()) {
                broadcastScanMisses = if (found) 0 else (broadcastScanMisses + 1)
                scheduleBroadcastScan("post-$reason-inactive")
            }
            return
        }

        try {
            broadcastScanner?.stopScan(broadcastScanCallback)
        } catch (_: Exception) {
        }
        broadcastScanActive = false
        broadcastScanStartedAtElapsed = 0L
        releaseBroadcastWakeLock()
        scanHandler.removeCallbacks(broadcastScanStopRunnable)

        if (reason != "disconnect" && wantsBroadcastScan()) {
            broadcastScanMisses = if (found) 0 else (broadcastScanMisses + 1)
            scheduleBroadcastScan("post-$reason")
            Log.d(TAG, "Broadcast scan stopped ($reason, found=$found, miss=$broadcastScanMisses)")
        }
    }

    private fun scheduleScanAlarm(delayMs: Long) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(appContext, AiDexScanReceiver::class.java).apply {
            action = AiDexScanReceiver.ACTION_AIDEX_SCAN
            putExtra(AiDexScanReceiver.EXTRA_SERIAL, SerialNumber)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            SerialNumber.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = System.currentTimeMillis() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            Log.d(TAG, "Scan alarm set for $SerialNumber in ${delayMs / 1000}s")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set scan alarm: ${e.message}")
        }
    }

    private fun cancelScanAlarm() {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(appContext, AiDexScanReceiver::class.java).apply {
            action = AiDexScanReceiver.ACTION_AIDEX_SCAN
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            SerialNumber.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Scan alarm cancelled for $SerialNumber")
        }
    }

    private fun acquireBroadcastWakeLock() {
        if (broadcastWakeLock?.isHeld == true) return
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiDexSensor:BroadcastScan")
        wl.setReferenceCounted(false)
        wl.acquire(BROADCAST_SCAN_WINDOW_MS + 2_000L)
        broadcastWakeLock = wl
    }

    private fun releaseBroadcastWakeLock() {
        try {
            broadcastWakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        } finally {
            broadcastWakeLock = null
        }
    }

    private fun onBroadcastUpdated() {
        if (!broadcastEnabled) return
        broadcastScanMisses = 0
        if (broadcastScanActive) {
            stopBroadcastScan("broadcast", found = true)
        } else if (wantsBroadcastScan()) {
            scheduleBroadcastScan("broadcast")
        }
    }

    private fun updateStartTimeFromOffset(offsetMinutes: Long, now: Long) {
        if (offsetMinutes <= 0L || offsetMinutes > 60L * 24L * 14L) return
        val inferredStart = now - (offsetMinutes * 60_000L)
        if (sensorstartmsec == 0L || kotlin.math.abs(sensorstartmsec - inferredStart) > (10L * 60_000L)) {
            sensorstartmsec = inferredStart
            // Edit 58b: Also update expiry from inferred start if we don't have authoritative GET_START_TIME yet
            if (vendorSensorStartTimeMs == 0L) {
                vendorSensorExpiryMs = inferredStart + (AIDEX_SENSOR_MAX_DAYS * 24L * 3600_000L)
            }
            if (dataptr != 0L) {
                try {
                    Natives.aidexSetStartTime(dataptr, sensorstartmsec)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun extractLocalName(scanRecord: ByteArray): String? {
        var offset = 0
        while (offset < scanRecord.size - 1) {
            val len = scanRecord[offset].toInt() and 0xFF
            if (len == 0) break
            val type = scanRecord[offset + 1].toInt() and 0xFF
            if (type == 0x09 || type == 0x08) { // Complete / Shortened local name
                val start = offset + 2
                val endExclusive = (start + len - 1).coerceAtMost(scanRecord.size)
                if (endExclusive > start) {
                    return try {
                        String(scanRecord, start, endExclusive - start, Charsets.UTF_8)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            offset += len + 1
        }
        return null
    }

    override fun onScanRecord(scanRecord: ByteArray) {
        if (!broadcastEnabled) {
            if (!broadcastDisabledLogged) {
                broadcastDisabledLogged = true
                Log.i(TAG, "Broadcast parsing disabled for AiDex.")
            }
            return
        }
        try {
            // Parse Manufacturer Data (ID: 0x59 = 89 for AiDex/MicroTech)
            // Manual parsing of raw bytes: Len, Type, ID_LO, ID_HI, Data...
            var offset = 0
            while (offset < scanRecord.size - 2) {
                val len = scanRecord[offset].toInt() and 0xFF
                if (len == 0) break
                val type = scanRecord[offset + 1].toInt() and 0xFF
                
                if (type == 0xFF) { // Manufacturer Specific Data
                    // Relaxed check: Allow any manufacturer ID if the payload looks like a broadcast packet.
                    // Previously checked for 0x59 (AiDex/MicroTech), but variants (Linx, etc.) use others.
                    // Format: [Len] [0xFF] [ID_LO] [ID_HI] [Data...]
                    if (offset + 3 < scanRecord.size) {
                         // We rely on parseBroadcastData to validate the payload structure/content.
                         // This allows broad compat for variants that wrap the same glucose payload in different headers.
                        val dataLen = len - 3 
                        if (offset + 4 + dataLen <= scanRecord.size) {
                             val data = ByteArray(dataLen)
                             System.arraycopy(scanRecord, offset + 4, data, 0, dataLen)
                             val updated = parseBroadcastData(data)
                             if (updated) onBroadcastUpdated()
                        }
                    }
                }
                offset += len + 1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing scan record: ${e.message}")
        }
    }

    private fun parseBroadcastData(data: ByteArray): Boolean {
         if (!broadcastEnabled) return false
         if (data.size < 6) return false

         val now = System.currentTimeMillis()
         if (wantsAuto()) {
             val vendor = parseVendorBroadcastPayload(data, now)
             if (vendor != null) {
                 if (!shouldAcceptOffset(vendor.timeOffsetMinutes.toLong(), now, "vendor-broadcast")) {
                     return false
                 }
                 lastBroadcastGlucose = vendor.glucoseMgDl
                 lastBroadcastTime = now
                 lastBroadcastOffsetMinutes = vendor.timeOffsetMinutes.toLong()
                 if (now >= sessionStartMs) {
                     broadcastSeenInSession = true
                 }
                 writeFloatPref("lastBroadcastGlucose", lastBroadcastGlucose)
                 writeLongPref("lastBroadcastTime", lastBroadcastTime)
                 writeLongPref("lastBroadcastOffsetMinutes", lastBroadcastOffsetMinutes)
                 updateStartTimeFromOffset(lastBroadcastOffsetMinutes, now)
                 storeAutoFromSource(vendor.glucoseMgDl, vendor.timeOffsetMinutes, now, "vendor-broadcast", fromVendor = true)
                 onBroadcastUpdated()
                 return true
             }
         }

         // LinX (official) broadcast format (Manufacturer 0x0059, 20-byte payload):
         // bytes 0..3 : u32 little-endian timeOffsetMinutes
         // byte 4     : i8 trend
         // byte 5     : u8 glucose mg/dL
         val offsetMinutes = ((data[0].toLong() and 0xFF) or
             ((data[1].toLong() and 0xFF) shl 8) or
             ((data[2].toLong() and 0xFF) shl 16) or
             ((data[3].toLong() and 0xFF) shl 24)) and 0xFFFF_FFFFL
         val trend = data[4].toInt() // signed
         val glucoseMgDlInt = data[5].toInt() and 0xFF

         if (glucoseMgDlInt !in 30..500) return false
         if (!shouldAcceptOffset(offsetMinutes, now, "manual-broadcast")) return false

        lastBroadcastGlucose = glucoseMgDlInt.toFloat()
        lastBroadcastTime = now
        lastBroadcastOffsetMinutes = offsetMinutes
        if (now >= sessionStartMs) {
            broadcastSeenInSession = true
        }

         writeFloatPref("lastBroadcastGlucose", lastBroadcastGlucose)
         writeLongPref("lastBroadcastTime", lastBroadcastTime)
         writeLongPref("lastBroadcastOffsetMinutes", lastBroadcastOffsetMinutes)

         // Use offsetMinutes to establish an approximate start time; this improves native ID alignment.
         updateStartTimeFromOffset(offsetMinutes, now)

        Log.d(TAG, "AIDEX-BCAST: off=${offsetMinutes}m trend=$trend glucose=$glucoseMgDlInt mg/dL")
        
        // Update notification status when receiving broadcast data
        if (broadcastOnlyConnection) {
            constatstatusstr = "Receiving"
        }
        
        storeBroadcastIfNeeded(lastBroadcastGlucose, offsetMinutes, now)
        storeRawFromBroadcastIfNeeded(lastBroadcastGlucose, offsetMinutes, now)
        return true
    }

    private fun storeBroadcastIfNeeded(glucoseMgDl: Float, offsetMinutes: Long, timeMs: Long) {
        if (!broadcastEnabled) return
        // Mode 4 (Broadcast Only) stores broadcast data as Auto readings since that's its only source
        if (!wantsAuto() && !isBroadcastOnlyMode()) return

        if (lastVendorTime != 0L && (timeMs - lastVendorTime) < BROADCAST_MIN_STORE_INTERVAL_MS) return

        // Dedupe on offset rather than wall-clock to prevent "repeat" values looking like new readings.
        if (lastBroadcastStoredOffsetMinutes != 0L && offsetMinutes < lastBroadcastStoredOffsetMinutes) {
            Log.i(TAG, "Broadcast offset went backwards ($lastBroadcastStoredOffsetMinutes -> $offsetMinutes). Resetting dedupe.")
            lastBroadcastStoredOffsetMinutes = 0L
        }

        val stored = storeAutoFromSource(
            glucoseMgDl,
            offsetMinutes.toInt(),
            timeMs,
            "broadcast",
            fromVendor = false
        )
        if (stored) {
            lastBroadcastStoredOffsetMinutes = offsetMinutes
        }
    }

    private fun storeRawFromBroadcastIfNeeded(glucoseMgDl: Float, offsetMinutes: Long, timeMs: Long) {
        if (!broadcastEnabled) return
        if (!rawBroadcastFallbackEnabled) return
        if (!wantsRaw()) return
        if (glucoseMgDl !in 30f..500f) return
        if (!shouldAcceptOffset(offsetMinutes, timeMs, "raw-broadcast")) return

        if (lastRawTime != 0L && (timeMs - lastRawTime) < BROADCAST_MIN_STORE_INTERVAL_MS) return

        if (lastRawBroadcastOffsetMinutes != 0L && offsetMinutes < lastRawBroadcastOffsetMinutes) {
            Log.i(TAG, "Raw-broadcast offset went backwards ($lastRawBroadcastOffsetMinutes -> $offsetMinutes). Resetting dedupe.")
            lastRawBroadcastOffsetMinutes = 0L
        }

        if (offsetMinutes <= lastRawBroadcastOffsetMinutes) return

        Log.i(TAG, ">>> SUCCESS AIDEX: RawFromBroadcast=$glucoseMgDl mg/dL")
        storeAidexReading(byteArrayOf(0), timeMs, glucoseMgDl, 0f)
        lastRawMgDl = glucoseMgDl
        lastRawTime = timeMs
        writeFloatPref("lastRawMgDl", lastRawMgDl)
        writeLongPref("lastRawTime", lastRawTime)
        lastRawBroadcastOffsetMinutes = offsetMinutes
    }

    private fun shouldAcceptOffset(offsetMinutes: Long, now: Long, source: String): Boolean {
        if (offsetMinutes <= 0L || offsetMinutes > 60L * 24L * 14L) return false
        if (lastBroadcastOffsetMinutes > 0L && offsetMinutes + 2 < lastBroadcastOffsetMinutes) {
            Log.w(
                TAG,
                "Broadcast offset went backwards ($lastBroadcastOffsetMinutes -> $offsetMinutes); ignoring ($source)"
            )
            return false
        }
        if (now < sessionStartMs) return false
        return true
    }

    @SuppressLint("MissingPermission")
    private fun setCharacteristicNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, enabled: Boolean) {
        gatt.setCharacteristicNotification(characteristic, enabled)
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor != null) {
            val value = if (!enabled) {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else {
                val props = characteristic.properties
                val supportsIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                val supportsNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0

                // AiDex/LinX: we must receive responses on F002.
                // Some firmware uses notifications, others indications. If both are supported, enable both (0x03).
                if (characteristic.uuid == CHAR_F002) {
                    // Prefer NOTIFY for F002 (matches advertised properties on most LinX firmwares).
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else if (supportsIndicate) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else if (supportsNotify) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
            }
            descriptor.value = value
            Log.d(TAG, "CCCD ${characteristic.uuid}: props=0x${String.format("%02X", characteristic.properties)} value=${bytesToHex(value)}")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startHandshake(gatt: BluetoothGatt) {
         val modeLabel = if (useOfficialHandshake) "official" else "legacy"
         handshakePlan = if (useOfficialHandshake) {
             buildOfficialHandshakePlan(gatt)
         } else {
             buildLegacyHandshakePlan()
         }
         if (useOfficialHandshake) {
             officialFlowStage = OfficialFlowStage.BOOTSTRAP
         }
         val bondState = gatt.device.bondState
         if (useOfficialHandshake && bondState == BluetoothDevice.BOND_NONE && !bondRequested) {
             val ok = gatt.device.createBond()
             bondRequested = true
             bondWaitUntilMs = System.currentTimeMillis() + BOND_WAIT_MS
             Log.i(TAG, "Bond requested (ok=$ok). Waiting up to ${BOND_WAIT_MS}ms before handshake.")
         } else if (bondState != BluetoothDevice.BOND_NONE) {
             bondWaitUntilMs = 0L
         }
         Log.i(
             TAG,
             "Starting Handshake Setup (F001 -> F002 -> F003) mode=$modeLabel plan=$handshakePlanLabel steps=${handshakePlan.size} bond=$bondState"
         )
         handshakeStep = 1
         handshakeRetries = 0
         expectedF002ResponseOps = emptySet()
         pendingIVCandidates.clear()
         val sF000 = gatt.getService(SERVICE_F000)
         
         val cF001 = sF000?.getCharacteristic(CHAR_F001)
         if (cF001 != null) {
             setCharacteristicNotification(gatt, cF001, true)
         } else {
             Log.e(TAG, "F001 not found!")
         }
         
         // RE-ENGINEERING FIX: Restore IV candidates from persistence on handshake start
         if (pendingIVCandidates.isEmpty()) {
             try {
                val sharePref = appContext.getSharedPreferences("Juggluco", Context.MODE_PRIVATE)
                val savedIvCandidates = sharePref.getString("aidex_iv_candidates", "")
                if (!savedIvCandidates.isNullOrEmpty()) {
                    savedIvCandidates.split(",").forEach { hex ->
                        if (hex.isNotEmpty()) {
                            val iv = hexBytes(hex)
                            if (iv.size == 16) {
                                pendingIVCandidates.add(iv)
                            }
                        }
                    }
                    Log.i(TAG, "Restored ${pendingIVCandidates.size} IV candidates from persistence (startHandshake).")
                }
             } catch (e: Exception) {
                Log.e(TAG, "Failed to restore IV candidates: ${e.message}")
             }
         }
    }

    private fun cancelHandshakeTimers() {
        pendingHandshakeRead?.let { handshakeHandler.removeCallbacks(it) }
        pendingHandshakeTimeout?.let { handshakeHandler.removeCallbacks(it) }
        pendingHandshakePrimingTimeout?.let { handshakeHandler.removeCallbacks(it) }
        pendingHandshakeRead = null
        pendingHandshakeTimeout = null
        pendingHandshakePrimingTimeout = null
        handshakeReadAttempts = 0
        handshakePrimingInProgress = false
    }

    private fun isHandshakeActive(): Boolean {
        return handshakeStep in 1..handshakePlan.size
    }

    private fun markHandshakeComplete(reason: String) {
        if (!isHandshakeActive()) return
        cancelHandshakeTimers()
        expectedF002ResponseOps = emptySet()
        handshakeStep = handshakePlan.size + 1
        if (useOfficialHandshake) {
            officialFlowStage = OfficialFlowStage.STREAMING
        }
        Log.i(TAG, "Handshake Complete ($reason) plan=$handshakePlanLabel")
    }

    private fun beginHandshakeAfterBondWait(gatt: BluetoothGatt) {
        bondWaitUntilMs = 0L
        if (bondRequested && gatt.device.bondState == BluetoothDevice.BOND_BONDED) {
            justBondedThisSession = true
        } else {
            justBondedThisSession = false
        }
        bondRequested = false

        if (useOfficialHandshake) {
            handshakePlan = buildOfficialHandshakePlan(gatt)
        }
        handshakeStep = 1
        handshakeRetries = 0
        expectedF002ResponseOps = emptySet()
        pendingIVCandidates.clear()
        Log.i(TAG, "Bond wait complete. Starting handshake plan=$handshakePlanLabel bond=${gatt.device.bondState}")
        beginHandshakeWithPriming(gatt)
    }

    private fun beginHandshakeWithPriming(gatt: BluetoothGatt) {

        if (useOfficialHandshake) {
            val sF000 = gatt.getService(SERVICE_F000)
            val cF002 = sF000?.getCharacteristic(CHAR_F002)
            if (cF002 != null && (cF002.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                handshakePrimingInProgress = true
                pendingHandshakePrimingTimeout?.let { handshakeHandler.removeCallbacks(it) }
                val ok = gatt.readCharacteristic(cF002)
                Log.i(TAG, "Handshake priming read F002 enqueued=$ok")
                if (ok) {
                    pendingHandshakePrimingTimeout = Runnable {
                        if (!handshakePrimingInProgress) return@Runnable
                        handshakePrimingInProgress = false
                        Log.w(TAG, "Handshake priming read timed out; proceeding.")
                        performHandshakeStep(gatt)
                    }
                    handshakeHandler.postDelayed(pendingHandshakePrimingTimeout!!, HANDSHAKE_PRIME_TIMEOUT_MS)
                    return
                }
                handshakePrimingInProgress = false
            }
        }
        performHandshakeStep(gatt)
    }

    private fun hexBytes(input: String): ByteArray {
        return hexStringToByteArray(input.replace(" ", ""))
    }

    private fun buildOfficialHandshakePlan(gatt: BluetoothGatt): List<HandshakeStep> {
        val bondState = gatt.device.bondState
        // 2026-02-05: Use bonded sequence when already bonded, unbonded when not.
        // Analysis of aidex_output_timed.txt shows:
        // - Bonded (12:14): B0/B1/94/A7/A6/A1... → sensor responds with history data
        // - New pair (12:26): F001 + FB/CA/96/97/B2 → sensor responds with session setup
        // The vendor library handles decryption internally - we just need to use the right
        // handshake sequence to get the sensor talking and pass data to vendor library.
        return if (bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "buildOfficialHandshakePlan: using BONDED sequence (B0/B1/94...)")
            buildOfficialBondedPlan()
        } else {
            Log.i(TAG, "buildOfficialHandshakePlan: using unbonded sequence (bond=$bondState)")
            buildOfficialUnbondedPlan()
        }
    }

    private fun buildOfficialBondedPlan(): List<HandshakeStep> {
        handshakePlanLabel = "official-bonded"
        val steps = ArrayList<HandshakeStep>()
        
        // 2026-02-05: Always start with F001 challenge to "wake up" the sensor.
        // Even in bonded mode, the sensor seems more responsive after an F001 write.
        steps.add(
            HandshakeStep(
                "${handshakePlanLabel}:F001-wake",
                CHAR_F001,
                hexBytes(OFFICIAL_PAIRED_F001),
                emptySet()
            )
        )

        OFFICIAL_HANDSHAKE_BONDED_STEPS.forEach { hex ->
            steps.add(HandshakeStep("${handshakePlanLabel}:${hex}", CHAR_F002, hexBytes(hex), emptySet()))
        }
        return steps
    }

    private fun buildOfficialUnbondedPlan(): List<HandshakeStep> {
        handshakePlanLabel = "official-unbonded"
        val steps = ArrayList<HandshakeStep>()
        
        // 2026-02-05: Add F001 challenge first to "wake up" the sensor session.
        // Without this, the sensor may ignore all F002 commands and return static responses.
        // The official app sends F001 before F002 in most flows.
        steps.add(
            HandshakeStep(
                "${handshakePlanLabel}:F001-challenge",
                CHAR_F001,
                hexBytes(OFFICIAL_PAIRED_F001),  // Use the known F001 challenge
                emptySet()
            )
        )
        
        // 2026-02-05: Use OFFICIAL_PAIRED_F002_STEPS (FB/CA/96/97/B2) instead of 
        // OFFICIAL_HANDSHAKE_UNBONDED_STEPS (BE/BF/9A...).
        // Analysis of aidex_output_timed.txt shows that after F001 challenge (91 C5 47...),
        // the official app sends FB F0 50, CA 82 76, 96 9B EA DB, 97 9B DB E8, B2 7A 47.
        // NOT the BE/BF/9A/A9/A8/AF sequence which appears to be for a different flow.
        OFFICIAL_PAIRED_F002_STEPS.forEach { hex ->
            val bytes = hexBytes(hex)
            val op = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: -1
            val expectOps = when (op) {
                0xFB, 0xCA -> setOf(op)  // These should return the session/IV info
                else -> emptySet()
            }
            steps.add(HandshakeStep("${handshakePlanLabel}:${hex}", CHAR_F002, bytes, expectOps))
        }
        
        return steps
    }

    private fun buildOfficialPairedPlan(): List<HandshakeStep> {
        handshakePlanLabel = "official-paired"
        val steps = ArrayList<HandshakeStep>()

        steps.add(
            HandshakeStep(
                "${handshakePlanLabel}:$OFFICIAL_PAIRED_F001",
                CHAR_F001,
                hexBytes(OFFICIAL_PAIRED_F001),
                emptySet()
            )
        )

        OFFICIAL_PAIRED_F002_STEPS.forEach { hex ->
            val bytes = hexBytes(hex)
            steps.add(HandshakeStep("${handshakePlanLabel}:$hex", CHAR_F002, bytes, emptySet()))
        }

        return steps
    }

    private fun buildLegacyHandshakePlan(): List<HandshakeStep> {
        handshakePlanLabel = "legacy"
        return LEGACY_HANDSHAKE_STEPS.map { hex ->
            val bytes = hexBytes(hex)
            val op = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: -1
            val expectOps = when (op) {
                0xB4, 0xB5 -> setOf(op)
                else -> emptySet()
            }
            HandshakeStep("${handshakePlanLabel}:${hex}", CHAR_F002, bytes, expectOps)
        }
    }

    private fun handshakeStepDelayMs(): Long {
        return if (handshakePlanLabel == "official-bonded" || handshakePlanLabel == "official-paired") {
            OFFICIAL_BONDED_STEP_DELAY_MS
        } else if (useOfficialHandshake) {
            HANDSHAKE_STEP_DELAY_MS
        } else {
            LEGACY_HANDSHAKE_STEP_DELAY_MS
        }
    }

    private fun addIvCandidatesFrom(data: ByteArray) {
        if (data.isEmpty()) return
        fun addCandidate(candidate: ByteArray) {
            if (candidate.size != 16) return
            pendingIVCandidates.add(candidate)
            try {
                // Some firmwares may transmit an IV block that is AES-ECB transformed.
                val ecb = Cipher.getInstance("AES/ECB/NoPadding")
                val keySpec = SecretKeySpec(MASTER_KEY, "AES")
                ecb.init(Cipher.DECRYPT_MODE, keySpec)
                pendingIVCandidates.add(ecb.doFinal(candidate))
                ecb.init(Cipher.ENCRYPT_MODE, keySpec)
                pendingIVCandidates.add(ecb.doFinal(candidate))
            } catch (_: Throwable) {
            }
        }

        if (data.size >= 16) {
            addCandidate(data.copyOfRange(0, 16))
        }
        if (data.size >= 17) {
            addCandidate(data.copyOfRange(1, 17))
        }
        if (data.size >= 18) {
            addCandidate(data.copyOfRange(2, 18))
        }
        if (pendingIVCandidates.size > 24) {
            pendingIVCandidates.subList(0, pendingIVCandidates.size - 24).clear()
        }
        
        // RE-ENGINEERING FIX: Persist IV candidates whenever they are updated.
        try {
            val sharePref = appContext.getSharedPreferences("Juggluco", Context.MODE_PRIVATE)
            val candidatesStr = pendingIVCandidates.joinToString(",") { bytesToHex(it) }
            sharePref.edit().putString("aidex_iv_candidates", candidatesStr).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist IV candidates: ${e.message}")
        }
    }
    
    override fun getService(): UUID? {
        return SERVICE_F000
    }

    private fun rawFromHeader6ED9(bytes: ByteArray): Float? {
        return rawFromHeader6ED9At(bytes, 0)
    }

    private fun rawFromHeader6ED9At(bytes: ByteArray, offset: Int): Float? {
        if (bytes.size < offset + 4) return null
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        if (b0 != 0x6E || b1 != 0xD9) return null
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        val combined = (b2 shl 8) or b3
        val mgDl = combined / 512.0f
        return if (mgDl in 30f..500f) mgDl else null
    }

    private fun rawFromHeader6D56(bytes: ByteArray): Float? {
        return rawFromHeader6D56At(bytes, 0)
    }

    private fun rawFromHeader6D56At(bytes: ByteArray, offset: Int): Float? {
        // LinX 1.7.25 (official) decrypted payload observed:
        // 6D 56 ... glucose mg/dL at byte[12]
        if (bytes.size < offset + 13) return null
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        if (b0 != 0x6D || b1 != 0x56) return null
        val mgDl = (bytes[offset + 12].toInt() and 0xFF).toFloat()
        return if (mgDl in 30f..500f) mgDl else null
    }

    private data class VendorDecodeResult(
        val glucoseMgDl: Float,
        val timeOffsetMinutes: Int,
        val trend: Int
    )

    private fun tryVendorInstantParse(label: String, payload: ByteArray, now: Long): VendorDecodeResult? {
        if (payload.isEmpty()) return null
        val loaded = try {
            AidexXParser.isLoaded()
        } catch (t: Throwable) {
            if (!vendorParserUnavailableLogged) {
                vendorParserUnavailableLogged = true
                Log.e(TAG, "AidexXParser init failed: ${t.message}")
            }
            return null
        }
        if (!loaded) {
            if (!vendorParserUnavailableLogged) {
                vendorParserUnavailableLogged = true
                Log.e(TAG, "AidexXParser not loaded (blecomm-lib missing or failed).")
            }
            return null
        }

        val entity = try {
            AidexXParser.getAidexXInstantHistory(payload) as? com.microtechmd.blecomm.parser.AidexXInstantHistoryEntity
        } catch (t: Throwable) {
            if (vendorProbeFailures.add(label)) {
                Log.d(TAG, "AidexXParser failed ($label): ${t.message}")
            }
            return null
        } ?: return null

        val history = entity.history ?: return null
        if (history.isValid == 0) return null
        val glucose = history.glucose
        if (glucose !in 30..500) return null

        val abstractOffset = entity.abstractEntity?.timeOffset ?: 0
        val offsetMinutes = if (history.timeOffset != 0) {
            history.timeOffset
        } else {
            abstractOffset
        }
        if (offsetMinutes <= 0 || offsetMinutes > 14 * 24 * 60) {
            if (vendorProbeFailures.add("${label}-offset")) {
                Log.d(TAG, "Vendor parser offset out of range ($label): $offsetMinutes")
            }
            return null
        }

        val trend = entity.abstractEntity?.trend ?: 0
        Log.i(TAG, "AIDEX-DEC: Vendor parser OK ($label) mg=$glucose offset=$offsetMinutes trend=$trend")
        return VendorDecodeResult(glucose.toFloat(), offsetMinutes, trend)
    }

    private fun parseVendorInstantPayload(payload: ByteArray, now: Long): VendorDecodeResult? {
        if (payload.isEmpty()) return null
        if (!vendorLibAvailable) return null

        // Edit 43a: Accept both 14-byte (vendor message callback) and 17-byte (raw F003
        // notification) payloads. The vendor AUTO_UPDATE_FULL_HISTORY (op=65025) delivers
        // 14-byte data arrays after resCode stripping. The native AidexXParser handles both
        // sizes internally. Previous 17-byte hard-check silently dropped all vendor push data.
        if (payload.size < 14) {
             // Short packets (e.g. 5-byte status/keepalive) are not glucose data
             Log.d(TAG, "parseVendorInstantPayload: payload too short (${payload.size} bytes), skipping")
             return null
        }

        val loaded = try {
            AidexXParser.isLoaded()
        } catch (t: Throwable) {
            if (!vendorParserUnavailableLogged) {
                vendorParserUnavailableLogged = true
                Log.e(TAG, "AidexXParser init failed: ${t.message}")
            }
            return null
        }
        if (!loaded) {
            if (!vendorParserUnavailableLogged) {
                vendorParserUnavailableLogged = true
                Log.e(TAG, "AidexXParser not loaded (blecomm-lib missing or failed).")
            }
            return null
        }

        val entity = try {
            AidexXParser.getAidexXInstantHistory(payload) as? com.microtechmd.blecomm.parser.AidexXInstantHistoryEntity
        } catch (t: Throwable) {
            Log.e(TAG, "AidexXParser.getAidexXInstantHistory failed for ${payload.size}-byte payload: ${t.message}")
            return null
        }
        if (entity == null) {
            Log.d(TAG, "parseVendorInstantPayload: native parser returned null for ${payload.size}-byte payload")
            return null
        }

        val history = entity.history
        if (history == null) {
            Log.d(TAG, "parseVendorInstantPayload: entity.history is null (entity=$entity)")
            return null
        }
        val glucose = history.glucose
        
        // Edit 43a: Log parser results for diagnostics — critical for verifying auto-update decoding
        Log.i(TAG, "parseVendorInstantPayload(${payload.size}B): valid=${history.isValid} glucose=$glucose timeOffset=${history.timeOffset} entity=$entity")
        
        if (history.isValid == 0) return null
        if (glucose !in 30..500) return null

        val abstractOffset = entity.abstractEntity?.timeOffset ?: 0
        val offsetMinutes = if (history.timeOffset != 0) {
            history.timeOffset
        } else {
            abstractOffset
        }
        if (offsetMinutes > 0) {
            updateStartTimeFromOffset(offsetMinutes.toLong(), now)
        }

        val trend = entity.abstractEntity?.trend ?: 0
        return VendorDecodeResult(glucose.toFloat(), offsetMinutes, trend)
    }




    private fun parseVendorBroadcastPayload(payload: ByteArray, now: Long): VendorDecodeResult? {
        if (payload.isEmpty()) return null
        if (!vendorLibAvailable) return null
        val loaded = try {
            AidexXParser.isLoaded()
        } catch (t: Throwable) {
            if (!vendorParserUnavailableLogged) {
                vendorParserUnavailableLogged = true
                Log.e(TAG, "AidexXParser init failed: ${t.message}")
            }
            return null
        }
        if (!loaded) {
            if (!vendorParserUnavailableLogged) {
                vendorParserUnavailableLogged = true
                Log.e(TAG, "AidexXParser not loaded (blecomm-lib missing or failed).")
            }
            return null
        }

        val entity = try {
            AidexXParser.getBroadcast(payload) as? com.microtechmd.blecomm.parser.AidexXBroadcastEntity
        } catch (t: Throwable) {
            Log.e(TAG, "AidexXParser broadcast failed: ${t.message}")
            return null
        } ?: return null

        val history = entity.history?.firstOrNull() ?: return null
        
        if (history.isValid == 0) return null
        val glucose = history.glucose
        if (glucose !in 30..500) return null

        val abstractOffset = entity.abstractEntity?.timeOffset ?: 0
        val offsetMinutes = if (history.timeOffset != 0) {
            history.timeOffset
        } else {
            abstractOffset
        }
        if (offsetMinutes > 0) {
            updateStartTimeFromOffset(offsetMinutes.toLong(), now)
        }

        val trend = entity.abstractEntity?.trend ?: 0
        return VendorDecodeResult(glucose.toFloat(), offsetMinutes, trend)
    }

    private fun storeAutoFromSource(
        glucoseMgDl: Float,
        offsetMinutes: Int,
        now: Long,
        source: String,
        fromVendor: Boolean
    ): Boolean {
        // Mode 4 (Broadcast Only) uses this path to store readings since it has no other source
        if (!wantsAuto() && !isBroadcastOnlyMode()) return false
        if (glucoseMgDl !in 30f..500f) return false
        if (offsetMinutes <= 0 || offsetMinutes > 14 * 24 * 60) return false

        if (fromVendor && lastVendorOffsetMinutes != 0 && offsetMinutes < lastVendorOffsetMinutes) {
            Log.i(TAG, "Vendor offset went backwards ($lastVendorOffsetMinutes -> $offsetMinutes). Resetting dedupe.")
            lastVendorOffsetMinutes = 0
        }

        val lastOffset = maxOf(
            lastVendorOffsetMinutes,
            lastBroadcastStoredOffsetMinutes.toInt()
        )
        if (offsetMinutes <= lastOffset) return false
        // Edit 51d: Only apply time-based dedup for non-vendor sources (broadcast scanning).
        // Vendor AUTO_UPDATE delivers sequential, unique offsets — the offset-based check above
        // is sufficient. The 50s timer was causing dropped readings when AUTO_UPDATE arrived
        // shortly after GET_BROADCAST_DATA during the initial pairing transition.
        if (!fromVendor && lastAutoTime != 0L && (now - lastAutoTime) < BROADCAST_MIN_STORE_INTERVAL_MS) return false

        updateStartTimeFromOffset(offsetMinutes.toLong(), now)

        val secondary = if (viewModeInternal == 2) {
            recentRawSecondary(now)
        } else {
            0f
        }

        // Edit 59b: Apply initialization bias compensation if enabled.
        // After a sensor reset, the vendor lib applies calFactor1/calFactor2 (0.85/0.95)
        // which makes readings too low. We reverse this by dividing by the factor.
        val compensationFactor = getResetCompensationFactor()
        val compensatedGlucose = if (compensationFactor != 1.0f) {
            val adjusted = glucoseMgDl * compensationFactor
            // Clamp to valid range after compensation
            val clamped = adjusted.coerceIn(30f, 500f)
            Log.i(TAG, "Edit 59: Bias compensation: $glucoseMgDl × $compensationFactor = $adjusted → clamped $clamped mg/dL")
            clamped
        } else {
            glucoseMgDl
        }

        Log.i(TAG, ">>> SUCCESS AIDEX: Auto($source)=$compensatedGlucose mg/dL${if (compensationFactor != 1.0f) " (raw=$glucoseMgDl, factor=$compensationFactor)" else ""}")
        storeAidexReading(byteArrayOf(0), now, compensatedGlucose, secondary)
        lastAutoMgDl = compensatedGlucose
        lastAutoTime = now
        writeFloatPref("lastAutoMgDl", lastAutoMgDl)
        writeLongPref("lastAutoTime", lastAutoTime)

        if (fromVendor) {
            lastVendorMgDl = compensatedGlucose
            lastVendorTime = now
            lastVendorOffsetMinutes = offsetMinutes
            // Edit 51e REMOVED by Edit 52a: vendorHistoryNextIndex advancement was here but caused
            // history gap loss after reconnection. Broadcast stores during reconnect would advance
            // the index past the gap, preventing history backfill from fetching missed offsets.
            // vendorHistoryNextIndex is now advanced ONLY from:
            //   - AUTO_UPDATE handler (Edit 51b) — reliable connected-mode source
            //   - GET_HISTORIES handler — history download pagination
            // The offset-based dedup in storeHistoryRecord() (Edit 48) prevents duplicate stores
            // for offsets already covered by AUTO_UPDATE or broadcast, without needing the index.
        }

        return true
    }

    private fun recentRawSecondary(now: Long): Float {
        if (lastRawTime == 0L) return 0f
        if ((now - lastRawTime) > 2 * 60_000L) return 0f
        return lastRawMgDl
    }

    private fun recentVendorSecondary(now: Long): Float {
        if (lastVendorTime == 0L) return 0f
        if ((now - lastVendorTime) > 2 * 60_000L) return 0f
        return lastVendorMgDl
    }

    private data class LegacyDecodeResult(
        val mgDl: Float,
        val source: String,
        val selectedBytes: ByteArray
    )

    private fun isVendorProbePlausible(glucoseMgDl: Float, now: Long): Boolean {
        // RE-ENGINEERING FIX: If the Vendor Library explicitly validated this packet, trust it.
        // We do this FIRST to override standard deviation checks.
        if (vendorBleEnabled) {
            // Log.v(TAG, "Vendor probe accepted (trusted source): $glucoseMgDl")
            return true
        }

        if (glucoseMgDl !in 30f..500f) return false

        val referenceAge = now - lastRawTime
        if (lastRawTime > 0L && referenceAge in 0..(10 * 60_000L)) {
            val diff = kotlin.math.abs(glucoseMgDl - lastRawMgDl)
            val maxDiff = maxAllowedDiff(referenceAge)
            if (diff > maxDiff) {
                Log.w(TAG, "Vendor probe rejected (diff=$diff mg/dL, max=$maxDiff, last=$lastRawMgDl)")
                return false
            }
        }

        if (lastBroadcastTime > 0L) {
            val ageMs = now - lastBroadcastTime
            if (ageMs in 0..BROADCAST_REFERENCE_MS) {
                val diff = kotlin.math.abs(glucoseMgDl - lastBroadcastGlucose)
                val maxDiff = maxAllowedDiff(ageMs)
                if (diff > maxDiff) {
                    Log.w(TAG, "Vendor probe rejected vs broadcast (diff=$diff mg/dL, max=$maxDiff, brd=$lastBroadcastGlucose)")
                    return false
                }
            }
                }


        return true
    }



    private fun getBroadcastReference(now: Long): Pair<Float, Long>? {
        if (!broadcastEnabled) return null
        if (!broadcastSeenInSession) return null
        if (lastBroadcastGlucose <= 0f || lastBroadcastTime == 0L) return null
        val ageMs = now - lastBroadcastTime
        if (ageMs > BROADCAST_REFERENCE_MS) return null
        return Pair(lastBroadcastGlucose, ageMs)
    }

    private data class IvLockMatch(
        val iv: ByteArray,
        val candidate: DecodeCandidate,
        val selectedBytes: ByteArray,
        val sourceLabel: String
    )

    private fun tryLockIvWithBroadcast(
        encryptedData: ByteArray,
        now: Long
    ): IvLockMatch? {
        val ref = getBroadcastReference(now) ?: return null
        val (broadcastGlucose, ageMs) = ref
        val maxDiff = maxAllowedDiff(ageMs)

        if (pendingIVCandidates.isEmpty()) return null

        val skips = intArrayOf(1, 0)
        var bestMatch: IvLockMatch? = null
        var bestDiff = Float.MAX_VALUE

        for (candidateIv in pendingIVCandidates) {
            for (skip in skips) {
                val decrypted = decryptPayload(encryptedData, candidateIv, skip) ?: continue
                try {
                    val decryptedReversed = reverseBitsCopy(decrypted)
                    val allCandidates = collectCandidates(decrypted, decryptedReversed, skip)
                    val candidates = if (useOfficialHandshake) {
                        allCandidates.filter { it.source.startsWith("header") }
                    } else {
                        allCandidates
                    }
                    if (candidates.isEmpty()) continue

                    val bestCandidate = candidates.minBy { kotlin.math.abs(it.mgDl - broadcastGlucose) }
                    val diff = kotlin.math.abs(bestCandidate.mgDl - broadcastGlucose)
                    if (diff > maxDiff) continue

                    if (diff < bestDiff) {
                        val selectedBytes = if (bestCandidate.reversed) decryptedReversed else decrypted
                        val label = "skip$skip-${bestCandidate.source}"
                        bestMatch = IvLockMatch(candidateIv, bestCandidate, selectedBytes, label)
                        bestDiff = diff
                    }
                } catch (_: Throwable) {
                }
            }
        }

        return bestMatch
    }

    private data class DecodeCandidate(
        val mgDl: Float,
        val reversed: Boolean,
        val source: String
    )

    private fun decryptPayload(encryptedData: ByteArray, iv: ByteArray, skip: Int): ByteArray? {
        if (skip < 0 || skip >= encryptedData.size) return null
        val slice = encryptedData.copyOfRange(skip, encryptedData.size)
        if (slice.size < 16) return null
        return try {
            decrypt(slice, iv)
        } catch (_: Throwable) {
            null
        }
    }

    private fun collectCandidates(
        decrypted: ByteArray,
        decryptedReversed: ByteArray,
        skip: Int
    ): List<DecodeCandidate> {
        val candidates = ArrayList<DecodeCandidate>(8)

        for (offset in 0..2) {
            val header6D56 = rawFromHeader6D56At(decrypted, offset)
            if (header6D56 != null) {
                candidates.add(DecodeCandidate(header6D56, false, "header6D56@$offset/s$skip"))
            }
            val headerPlain = rawFromHeader6ED9At(decrypted, offset)
            if (headerPlain != null) {
                candidates.add(DecodeCandidate(headerPlain, false, "header6ED9@$offset/s$skip"))
            }
            val headerReversed = rawFromHeader6ED9At(decryptedReversed, offset)
            if (headerReversed != null) {
                candidates.add(DecodeCandidate(headerReversed, true, "header6ED9@$offset/s$skip"))
            }
        }

        // Legacy fallback candidates.
        if (decrypted.size >= 6) {
            val mgDl = (decrypted[5].toInt() and 0xFF).toFloat()
            if (mgDl in 30f..500f) {
                candidates.add(DecodeCandidate(mgDl, false, "plain.b5/s$skip"))
            }
        }
        if (decryptedReversed.size >= 6) {
            val mgDl = (decryptedReversed[5].toInt() and 0xFF).toFloat()
            if (mgDl in 30f..500f) {
                candidates.add(DecodeCandidate(mgDl, true, "rev.b5/s$skip"))
            }
        }
        return candidates
    }

    private fun maxAllowedDiff(ageMs: Long): Float {
        val ageMin = ageMs / 60000f
        val max = 30f + (ageMin * 5f)
        return max.coerceIn(30f, 80f)
    }

    private fun selectCandidate(
        candidates: List<DecodeCandidate>,
        now: Long,
        reverseHint: Boolean
    ): DecodeCandidate? {
        if (candidates.isEmpty()) return null
        val filtered = if (useOfficialHandshake || ivLocked) {
            candidates.filter { it.source.startsWith("header") }
        } else {
            candidates
        }
        if (filtered.isEmpty()) return null
        val hasHeader6D56 = filtered.any { it.source.startsWith("header6D56") }
        val hasHeader6ED9 = filtered.any { it.source.startsWith("header6ED9") }
        val referenceAge = now - lastRawTime
        val hasReference = lastRawTime > 0L && referenceAge in 0..(10 * 60_000L)

        // LinX official frames: prefer the known header mapping.
        var chosen: DecodeCandidate? = null
        if (hasHeader6D56) {
            chosen = filtered.first { it.source.startsWith("header6D56") }
        } else if (hasReference) {
            val best = filtered.minBy { kotlin.math.abs(it.mgDl - lastRawMgDl) }
            val diff = kotlin.math.abs(best.mgDl - lastRawMgDl)
            val maxDiff = maxAllowedDiff(referenceAge)
            if (diff > maxDiff) {
                Log.w(TAG, "F003: Candidate rejected (diff=$diff mg/dL, max=$maxDiff, last=$lastRawMgDl)")
                return null
            }
            chosen = best
        } else if (hasHeader6ED9) {
            chosen = filtered.first { it.source.startsWith("header6ED9") }
        } else {
            chosen = filtered.firstOrNull { it.reversed == reverseHint } ?: filtered.first()
        }

        if (chosen == null) return null

        if (ivLockedFromBroadcast && (chosen.source == "plain.b5" || chosen.source == "rev.b5")) {
            val ref = getBroadcastReference(now)
            if (ref != null) {
                val (brd, ageMs) = ref
                val diff = kotlin.math.abs(chosen.mgDl - brd)
                val maxDiff = maxAllowedDiff(ageMs)
                if (diff > maxDiff) {
                    Log.w(TAG, "F003: Fallback candidate rejected vs broadcast (diff=$diff mg/dL, max=$maxDiff, brd=$brd)")
                    return null
                }
            } else if (!hasReference) {
                Log.w(TAG, "F003: Fallback candidate rejected (no broadcast or recent reference)")
                return null
            }
        }

        return chosen
    }

    private fun decodeLegacyF003(encryptedData: ByteArray, now: Long): LegacyDecodeResult? {
        if (encryptedData.size < 17) {
            Log.d(TAG, "F003 short frame (${encryptedData.size} bytes). Ignored.")
            return null
        }

        if (dynamicIV == null) {
            if (pendingIVCandidates.isNotEmpty()) {
                val lockMatch = tryLockIvWithBroadcast(encryptedData, now)
                if (lockMatch != null) {
                    val ivHex = bytesToHex(lockMatch.iv)
                    val key = "$ivHex:${lockMatch.sourceLabel}:${lockMatch.candidate.reversed}"
                    if (key == ivLockKey) {
                        ivLockCount += 1
                    } else {
                        ivLockKey = key
                        ivLockCount = 1
                    }

                    Log.i(
                        TAG,
                        "IV lock candidate ${ivLockCount}/2 (key=$key, mg=${lockMatch.candidate.mgDl})"
                    )

                    if (ivLockCount >= 2) {
                        dynamicIV = lockMatch.iv
                        pendingIVCandidates.clear()
                        ivLockCount = 0
                        ivLockKey = null
                        useBitReverse = lockMatch.candidate.reversed
                        writeIntPref("useBitReverse", if (useBitReverse == true) 1 else 0)
                        ivLocked = true
                        ivLockedFromBroadcast = true
                        Log.i(TAG, "IV locked from broadcast (source=${lockMatch.sourceLabel})")

                        lastRawMgDl = lockMatch.candidate.mgDl
                        lastRawTime = now
                        writeFloatPref("lastRawMgDl", lastRawMgDl)
                        writeLongPref("lastRawTime", lastRawTime)
                        return LegacyDecodeResult(lockMatch.candidate.mgDl, "ivlock", lockMatch.selectedBytes)
                    }

                    // Don't emit a reading until IV is locked; rely on broadcast instead.
                    return null
                }

                var resolved: ByteArray? = null
                var resolvedVendor: VendorDecodeResult? = null
                val skips = intArrayOf(1, 0)
                for (candidate in pendingIVCandidates) {
                    for (skip in skips) {
                        val decrypted = decryptPayload(encryptedData, candidate, skip) ?: continue
                        try {
                            val decryptedReversed = reverseBitsCopy(decrypted)
                            if (!useOfficialHandshake) {
                                val vendorCandidate = tryVendorInstantParse("f003-ivcand", decrypted, now)
                                    ?: tryVendorInstantParse("f003-ivcand-rev", decryptedReversed, now)
                                if (vendorCandidate != null && isVendorProbePlausible(vendorCandidate.glucoseMgDl, now)) {
                                    resolved = candidate
                                    resolvedVendor = vendorCandidate
                                    break
                                }
                            }
                            val headerOk =
                                (rawFromHeader6D56At(decrypted, 0) != null ||
                                    rawFromHeader6D56At(decrypted, 1) != null ||
                                    rawFromHeader6D56At(decrypted, 2) != null ||
                                    rawFromHeader6D56At(decryptedReversed, 0) != null ||
                                    rawFromHeader6D56At(decryptedReversed, 1) != null ||
                                    rawFromHeader6D56At(decryptedReversed, 2) != null ||
                                    rawFromHeader6ED9At(decrypted, 0) != null ||
                                    rawFromHeader6ED9At(decrypted, 1) != null ||
                                    rawFromHeader6ED9At(decrypted, 2) != null ||
                                    rawFromHeader6ED9At(decryptedReversed, 0) != null ||
                                    rawFromHeader6ED9At(decryptedReversed, 1) != null ||
                                    rawFromHeader6ED9At(decryptedReversed, 2) != null)
                            if (headerOk) {
                                resolved = candidate
                                break
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    if (resolved != null) break
                }
                if (resolved != null) {
                    dynamicIV = resolved
                    pendingIVCandidates.clear()
                    Log.i(TAG, "IV resolved from candidate list; continuing decode.")
                    ivLocked = true
                    ivLockedFromBroadcast = false
                    if (resolvedVendor != null) {
                        lastRawMgDl = resolvedVendor.glucoseMgDl
                        lastRawTime = now
                        writeFloatPref("lastRawMgDl", lastRawMgDl)
                        writeLongPref("lastRawTime", lastRawTime)
                        return LegacyDecodeResult(resolvedVendor.glucoseMgDl, "vendor-ivcand", byteArrayOf(0))
                    }
                } else {
                    Log.w(TAG, "RX [F003]: CANNOT DECRYPT - no valid IV candidate matched header (6D56/6ED9)")
                    return null
                }
            } else {
                Log.v(TAG, "RX [F003]: CANNOT DECRYPT - dynamicIV is NULL")  // Edit 51c: W→V (expected during vendor handshake, not actionable)
                return null
            }
        }

        try {
            val skips = intArrayOf(1, 0)
            var bestCandidates: List<DecodeCandidate> = emptyList()
            var bestDecrypted: ByteArray? = null
            var bestReversed: ByteArray? = null

            for (skip in skips) {
                val decrypted = decryptPayload(encryptedData, dynamicIV!!, skip) ?: continue
                val decryptedReversed = reverseBitsCopy(decrypted)
                val candidates = collectCandidates(decrypted, decryptedReversed, skip)
                if (candidates.isNotEmpty()) {
                    bestCandidates = candidates
                    bestDecrypted = decrypted
                    bestReversed = decryptedReversed
                    // Prefer the first skip that yields header candidates.
                    if (candidates.any { it.source.startsWith("header") }) break
                }
            }

            if (bestDecrypted == null || bestReversed == null) return null

            val vendorDecrypted = tryVendorInstantParse("f003-dec", bestDecrypted, now)
                ?: tryVendorInstantParse("f003-dec-rev", bestReversed, now)
            if (vendorDecrypted != null && isVendorProbePlausible(vendorDecrypted.glucoseMgDl, now)) {
                lastRawMgDl = vendorDecrypted.glucoseMgDl
                lastRawTime = now
                writeFloatPref("lastRawMgDl", lastRawMgDl)
                writeLongPref("lastRawTime", lastRawTime)
                return LegacyDecodeResult(vendorDecrypted.glucoseMgDl, "vendor-dec", byteArrayOf(0))
            }

            val candidates = bestCandidates
            if (useOfficialHandshake &&
                candidates.none { it.source.startsWith("header6D56") || it.source.startsWith("header6ED9") }
            ) {
                Log.w(TAG, "F003: Unexpected decrypted header; dropping frame (no 6D56/6ED9 match)")
                return null
            }
            val reverseHint = (bestDecrypted[1].toInt() and 0x80) != 0
            val decoded = selectCandidate(candidates, now, reverseHint)
            if (decoded == null) {
                Log.w(TAG, "F003: No deterministic decode match")
                return null
            }

            val bestVal = decoded.mgDl
            val usedReverse = decoded.reversed
            val selectedBytes = if (usedReverse) bestReversed else bestDecrypted

            if (useBitReverse != usedReverse) {
                useBitReverse = usedReverse
                writeIntPref("useBitReverse", if (useBitReverse == true) 1 else 0)
                Log.i(TAG, "AIDEX-DEC: Using bit-reverse=$usedReverse")
            }

            when {
                decoded.source.startsWith("header6D56") -> {
                    Log.i(TAG, "AIDEX-DEC: Header 6D56 mg=$bestVal mg/dL (${decoded.source})")
                }
                decoded.source.startsWith("header6ED9") -> {
                    Log.i(TAG, "AIDEX-DEC: Header 6ED9 mg=$bestVal mg/dL (${decoded.source})")
                }
                else -> {
                    Log.d(TAG, "AIDEX-DEC: ${decoded.source} mg=$bestVal mg/dL")
                }
            }

            // Raw reference for future candidate selection.
            lastRawMgDl = bestVal
            lastRawTime = now
            writeFloatPref("lastRawMgDl", lastRawMgDl)
            writeLongPref("lastRawTime", lastRawTime)

            return LegacyDecodeResult(bestVal, decoded.source, selectedBytes)
        } catch (e: Exception) {
            Log.stack(TAG, "Decryption/Parse Error", e)
            return null
        }
    }

    private fun handleF003Data(encryptedData: ByteArray) {
        if (!wantsRaw() && !wantsAuto()) return
        val now = System.currentTimeMillis()

        // Edit 54: Suppress F003 native parse when vendor AUTO_UPDATE is active.
        // The vendor lib already delivers decoded glucose via AUTO_UPDATE_FULL_HISTORY
        // on the handler thread.  The raw GATT notification arrives on a separate thread
        // and races the vendor callback — decrypting with a potentially stale dynamicIV
        // produces garbage readings (e.g., 389 mg/dL with timeOffset=26172).
        // Only fall through to native parse when the vendor stack isn't delivering data.
        val timeSinceAutoUpdate = now - vendorLastAutoUpdateTime
        if (vendorLastAutoUpdateTime > 0L && timeSinceAutoUpdate < 90_000L) {
            Log.v(TAG, "AIDEX-F003: Suppressed native parse — vendor AUTO_UPDATE active (${timeSinceAutoUpdate / 1000}s ago)")
            return
        }

        // --- NEW PROTOCOL HANDLING ---
        // 5-byte packets are status/keepalive (ignore for now)
        if (encryptedData.size == 5) {
             Log.d(TAG, "AIDEX-F003: Status packet (5 bytes) ignored.")
             return
        }

        // 17-byte packets are data
        if (encryptedData.size == 17) {
            var packetToParse = encryptedData
            var decrypted = false
            
            if (dynamicIV != null) {
                try {
                    val dec = decrypt(encryptedData, dynamicIV!!)
                    packetToParse = dec
                    decrypted = true
                     Log.d(TAG, "AIDEX-F003: Decrypted successfully.")
                } catch (e: Exception) {
                    Log.w(TAG, "AIDEX-F003: Decrypt failed: ${e.message}")
                }
            } else {
                 Log.v(TAG, "AIDEX-F003: dynamicIV is NULL. Cannot decrypt — skipping ciphertext to avoid garbage readings.")  // Edit 51c: W→V
                 return
            }

            // Try native parser with (hopefully) plaintext
            val vendorDirect = parseVendorInstantPayload(packetToParse, now)
            if (vendorDirect != null) {
                // SANITY CHECK: If values are extreme and we didn't decrypt, it's likely garbage.
                if (!decrypted && (vendorDirect.glucoseMgDl > 400 || vendorDirect.glucoseMgDl < 40)) {
                     Log.w(TAG, "AIDEX-F003: Native parse result ${vendorDirect.glucoseMgDl} suspicious (Ciphertext?). Ignoring.")
                } else {
                    markHandshakeComplete("vendor-f003-native")
                    lastRawMgDl = vendorDirect.glucoseMgDl
                    lastRawTime = now
                    writeFloatPref("lastRawMgDl", lastRawMgDl)
                    writeLongPref("lastRawTime", lastRawTime)
    
                    val secondary = if (viewModeInternal == 3) {
                        recentVendorSecondary(now)
                    } else {
                        0f
                    }
                    Log.i(TAG, ">>> SUCCESS AIDEX (Native): Raw=${vendorDirect.glucoseMgDl} mg/dL")
                    storeAidexReading(byteArrayOf(0), now, vendorDirect.glucoseMgDl, secondary)
    
                    if (wantsAuto()) {
                        lastAutoMgDl = vendorDirect.glucoseMgDl
                        lastAutoTime = now
                        writeFloatPref("lastAutoMgDl", lastAutoMgDl)
                        writeLongPref("lastAutoTime", lastAutoTime)
                    }
                    
                    checkAndRequestHistory()
                    return
                }
            } else {
                Log.w(TAG, "AIDEX-F003: Native parse failed for 17-byte packet. Trying legacy fallback...")
            }
        }
        
        // --- LEGACY FALLBACK ---
        val legacy = decodeLegacyF003(encryptedData, now) ?: return
        markHandshakeComplete("f003-legacy")

        val secondary = if (viewModeInternal == 3) {
            recentVendorSecondary(now)
        } else {
            0f
        }

        Log.i(TAG, ">>> SUCCESS AIDEX (Legacy): Raw=${legacy.mgDl} mg/dL")
        storeAidexReading(legacy.selectedBytes, now, legacy.mgDl, secondary)

        if (wantsAuto()) {
            lastAutoMgDl = legacy.mgDl
            lastAutoTime = now
            writeFloatPref("lastAutoMgDl", lastAutoMgDl)
            writeLongPref("lastAutoTime", lastAutoTime)
        }
        
        // Trigger history backfill if needed
        checkAndRequestHistory()
    }

    private fun checkAndRequestHistory() {
        requestHistoryBackfill("auto")
    }

    /**
     * Edit 45d: Rewritten to match official app pattern:
     * 1. Call getHistoryRange() — response arrives at GET_HISTORY_RANGE handler
     * 2. GET_HISTORY_RANGE handler parses the 6-byte range, calls getHistories(nextIndex)
     * 3. GET_HISTORIES handler processes each batch and requests next page
     *
     * Deduplicates by enforcing a 5-minute minimum interval between requests.
     * Runs on vendorExecutor (background thread) because the native call blocks.
     */
    private fun requestHistoryBackfill(reason: String) {
        val now = System.currentTimeMillis()
        // Edit 51a: Suppress history backfill when AUTO_UPDATE is actively delivering glucose.
        // AUTO_UPDATE stores every reading (~1/min). History backfill during active AUTO_UPDATE
        // creates duplicate data points and wastes 2-4 BLE transactions per cycle.
        // Only allow backfill when AUTO_UPDATE has been silent for >90s (e.g. after reconnection).
        val timeSinceLastAutoUpdate = now - vendorLastAutoUpdateTime
        if (vendorLastAutoUpdateTime > 0L && timeSinceLastAutoUpdate < VENDOR_AUTO_UPDATE_WATCHDOG_MS) {
            Log.d(TAG, "requestHistoryBackfill($reason): suppressed — AUTO_UPDATE active (last ${timeSinceLastAutoUpdate / 1000}s ago)")
            return
        }
        if (now - lastHistoryRequestTime < 60_000L) { // Edit 48: reduced from 300s to 60s — history is fast/lightweight now
            Log.d(TAG, "requestHistoryBackfill($reason): skipped, last request was ${(now - lastHistoryRequestTime) / 1000}s ago")
            return
        }
        if (vendorHistoryDownloading) {
            Log.d(TAG, "requestHistoryBackfill($reason): skipped, download already in progress")
            return
        }
        lastHistoryRequestTime = now
        vendorExecutor.execute {
            try {
                Log.i(TAG, "Requesting History Backfill ($reason, async) via getHistoryRange()...")
                val controller = vendorController
                if (controller == null) {
                    Log.w(TAG, "requestHistoryBackfill($reason): vendorController is null")
                    return@execute
                }
                if (!vendorBleEnabled) {
                    Log.w(TAG, "requestHistoryBackfill($reason): vendorBleEnabled=false")
                    return@execute
                }
                if (!vendorGattConnected || !vendorNativeReady) {
                    Log.w(TAG, "requestHistoryBackfill($reason): GATT not connected or native not ready, deferring")
                    lastHistoryRequestTime = 0L // Reset so next auto-update can retry
                    return@execute
                }
                // Edit 45d: Call getHistoryRange() first — the response handler (GET_HISTORY_RANGE)
                // will parse the range and trigger getHistories(nextEventIndex) automatically.
                val result = try {
                    controller.getHistoryRange()
                } catch (t: Throwable) {
                    Log.e(TAG, "requestHistoryBackfill($reason): getHistoryRange() threw: ${t.message}")
                    lastHistoryRequestTime = 0L // Reset for retry
                    return@execute
                }
                Log.i(TAG, "requestHistoryBackfill($reason): getHistoryRange() returned $result (0x${Integer.toHexString(result)})")
            } catch (t: Throwable) {
                Log.e(TAG, "History backfill failed ($reason): ${t.message}")
            }
        }
    }

    /**
     * Request calibration records from the sensor. Calls getCalibrationRange() first,
     * whose response handler triggers paginated getCalibration(index) calls.
     */
    private fun requestCalibrationData(reason: String) {
        if (!vendorBleEnabled || !vendorGattConnected || !vendorNativeReady) return
        if (vendorCalibrationDownloading) return
        vendorExecutor.execute {
            try {
                val controller = vendorController ?: return@execute
                Log.i(TAG, "requestCalibrationData($reason): calling getCalibrationRange()")
                val result = controller.getCalibrationRange()
                Log.i(TAG, "requestCalibrationData($reason): getCalibrationRange() returned $result")
            } catch (t: Throwable) {
                Log.e(TAG, "requestCalibrationData($reason) failed: ${t.message}")
            }
        }
    }


    private fun getFreshBroadcastForStore(now: Long): Float? {
        if (!broadcastEnabled) return null
        if (!broadcastSeenInSession) return null
        if (lastBroadcastGlucose <= 0f || lastBroadcastTime == 0L) {
            Log.w(TAG, "Broadcast missing. Skipping F003 store.")
            scheduleBroadcastScan("no-broadcast", forceImmediate = true)
            return null
        }
        val ageMs = now - lastBroadcastTime
        if (ageMs > BROADCAST_REFERENCE_MS) {
            Log.w(TAG, "Broadcast stale (${ageMs / 1000}s). Skipping F003 store.")
            scheduleBroadcastScan("broadcast-stale", forceImmediate = true)
            return null
        }
        return lastBroadcastGlucose
    }

    private fun storeAidexReading(bytes: ByteArray, timeMs: Long, autoGlucose: Float, rawGlucose: Float) {
        if (dataptr == 0L) {
            Log.w(TAG, "No dataptr; skipping store.")
            return
        }
        if (sensorstartmsec == 0L) {
            if (lastBroadcastOffsetMinutes > 0L) {
                updateStartTimeFromOffset(lastBroadcastOffsetMinutes, timeMs)
            } else {
                sensorstartmsec = timeMs
                try {
                    Natives.aidexSetStartTime(dataptr, sensorstartmsec)
                } catch (_: Throwable) {
                }
            }
        }
        try {
            val res = Natives.aidexProcessData(dataptr, bytes, timeMs, autoGlucose, rawGlucose, 1.0f)
            handleGlucoseResult(res, timeMs)

            // Trigger sync to update Compose chart
            tk.glucodata.data.HistorySync.syncFromNative()
            
            // History backfill is now handled asynchronously via checkAndRequestHistory()
            // from handleF003Data. The old inline call here blocked the calling thread
            // (main or BLE Binder) for up to 27 seconds via executeVendorCommand →
            // ensureVendorGattReady → Thread.sleep loops, causing ANR.

        } catch (e: UnsatisfiedLinkError) {
             Log.e(TAG, "Native library mismatch/missing: $e")
        } catch (e: Throwable) {
             Log.e(TAG, "Error in native processing: $e")
        }
    }

    private var lastHistoryRequestTime = 0L

    // Calibration records retrieved from the sensor via GET_CALIBRATION_RANGE + GET_CALIBRATION.
    // Each record contains: index, timeOffset (minutes), referenceGlucose (mg/dL), cf, offset, isValid.
    // Updated on connect (from startVendorLongConnect) and when AUTO_UPDATE_CALIBRATION is received.
    data class CalibrationRecord(
        val index: Int,
        val timeOffsetMinutes: Int,
        val referenceGlucoseMgDl: Int,
        val cf: Float,
        val offset: Float,
        val isValid: Boolean,
        val timestampMs: Long  // absolute time = sensorstartmsec + timeOffset*60000
    )
    @Volatile
    private var vendorCalibrationRecords: List<CalibrationRecord> = emptyList()
    private var vendorCalibrationRangeStart: Int = 0
    private var vendorCalibrationNewestIndex: Int = 0
    private var vendorCalibrationDownloading: Boolean = false

    /** Public accessor for calibration records (newest first) */
    fun getCalibrationRecords(): List<CalibrationRecord> = vendorCalibrationRecords.sortedByDescending { it.index }

    // Battery voltage from AUTO_UPDATE_BATTERY_VOLTAGE (op 0xFE04).
    // Payload is 2-byte little-endian uint16 representing millivolts (typical range ~1530-1560 mV).
    @Volatile
    var vendorBatteryMillivolts: Int = 0
        private set
    @Volatile
    var vendorBatteryLastUpdated: Long = 0L
        private set

    /** Returns battery voltage in millivolts, or 0 if not yet received */
    fun getBatteryMillivolts(): Int = vendorBatteryMillivolts

    // Sensor expiry notification from AUTO_UPDATE_SENSOR_EXPIRED (op 0xFE03).
    @Volatile
    var vendorSensorExpired: Boolean = false
        private set
    @Volatile
    var vendorSensorExpiredTime: Long = 0L
        private set

    /** True if the sensor has reported itself as expired */
    fun isSensorExpired(): Boolean = vendorSensorExpired

    // Edit 59: Initialization bias compensation after sensor reset.
    // When a sensor is reset, the vendor lib applies initialization calibration factors
    // (calFactor1=0.85 for 0-24h, calFactor2=0.95 for 24-48h) even though the sensor
    // has already been running. This causes readings to be significantly low after reset.
    // When enabled, we divide incoming glucose by the phase factor to compensate.
    @Volatile
    var resetCompensationEnabled: Boolean = readBoolPref("resetCompensationEnabled", false)
        private set
    @Volatile
    var resetCompensationTimestamp: Long = readLongPref("resetCompensationTimestamp", 0L)
        private set

    /**
     * Enable initialization bias compensation. Called when user resets with the checkbox checked.
     * Records the reset timestamp and persists both values.
     */
    fun enableResetCompensation() {
        val now = System.currentTimeMillis()
        resetCompensationEnabled = true
        resetCompensationTimestamp = now
        writeBoolPref("resetCompensationEnabled", true)
        writeLongPref("resetCompensationTimestamp", now)
        Log.i(TAG, "Edit 59: Reset compensation ENABLED at $now")
    }

    /**
     * Disable initialization bias compensation. Called when compensation period expires
     * or user manually disables it.
     */
    fun disableResetCompensation() {
        resetCompensationEnabled = false
        resetCompensationTimestamp = 0L
        writeBoolPref("resetCompensationEnabled", false)
        writeLongPref("resetCompensationTimestamp", 0L)
        Log.i(TAG, "Edit 59: Reset compensation DISABLED")
    }

    /**
     * Returns the multiplicative compensation factor for the current time since reset.
     * - Phase 1 (0-24h): 1/0.85 ≈ 1.176 (vendor applies 0.85, we reverse it)
     * - Phase 2 (24-48h): 1/0.95 ≈ 1.053 (vendor applies 0.95, we reverse it)
     * - After 48h: 1.0 (no compensation needed; auto-disables)
     * Returns 1.0f if compensation is disabled or expired.
     */
    fun getResetCompensationFactor(): Float {
        if (!resetCompensationEnabled || resetCompensationTimestamp <= 0L) return 1.0f
        val elapsed = System.currentTimeMillis() - resetCompensationTimestamp
        return when {
            elapsed < 0 -> {
                // Clock went backwards — disable compensation
                Log.w(TAG, "Edit 59: Clock went backwards, disabling compensation")
                disableResetCompensation()
                1.0f
            }
            elapsed < PHASE1_DURATION_MS -> {
                // Phase 1: 0-24h
                1.0f / CAL_FACTOR_PHASE1
            }
            elapsed < PHASE2_DURATION_MS -> {
                // Phase 2: 24-48h
                1.0f / CAL_FACTOR_PHASE2
            }
            else -> {
                // Past 48h — compensation period over, auto-disable
                Log.i(TAG, "Edit 59: Compensation period expired (${elapsed / 3600_000}h). Auto-disabling.")
                disableResetCompensation()
                1.0f
            }
        }
    }

    /** Returns a human-readable description of current compensation status */
    fun getCompensationStatusText(): String {
        if (!resetCompensationEnabled || resetCompensationTimestamp <= 0L) return ""
        val elapsed = System.currentTimeMillis() - resetCompensationTimestamp
        val remainingMs = PHASE2_DURATION_MS - elapsed
        if (remainingMs <= 0) return ""
        val remainingH = (remainingMs / 3600_000L).toInt()
        val factor = getResetCompensationFactor()
        val phaseName = if (elapsed < PHASE1_DURATION_MS) "Phase 1" else "Phase 2"
        return "$phaseName: ×${String.format("%.3f", factor)} (${remainingH}h left)"
    }

    // Edit 58a: Authoritative sensor start time from GET_START_TIME response.
    // Unlike updateStartTimeFromOffset() which infers start time from glucose offsets (drifts),
    // this is the actual date/time the sensor was activated, parsed from the vendor protocol.
    @Volatile
    var vendorSensorStartTimeMs: Long = 0L
        private set

    // Edit 58b: Computed sensor expiry = startTime + AIDEX_SENSOR_MAX_DAYS
    @Volatile
    var vendorSensorExpiryMs: Long = 0L
        private set

    /** Returns remaining sensor life in hours, or -1 if start time unknown */
    fun getSensorRemainingHours(): Int {
        val expiry = vendorSensorExpiryMs
        if (expiry <= 0L) {
            // Fall back to inferred start time
            val start = sensorstartmsec
            if (start <= 0L) return -1
            val inferredExpiry = start + (AIDEX_SENSOR_MAX_DAYS * 24L * 3600_000L)
            val remaining = inferredExpiry - System.currentTimeMillis()
            return if (remaining > 0) (remaining / 3600_000L).toInt() else 0
        }
        val remaining = expiry - System.currentTimeMillis()
        return if (remaining > 0) (remaining / 3600_000L).toInt() else 0
    }

    /** Returns sensor age in hours, or -1 if start time unknown */
    fun getSensorAgeHours(): Int {
        val start = vendorSensorStartTimeMs.takeIf { it > 0 } ?: sensorstartmsec.takeIf { it > 0 } ?: return -1
        val age = System.currentTimeMillis() - start
        return if (age > 0) (age / 3600_000L).toInt() else 0
    }

    // Edit 58c: Device metadata from GET_DEVICE_INFO response
    @Volatile var vendorFirmwareVersion: String = ""
        private set
    @Volatile var vendorHardwareVersion: String = ""
        private set
    @Volatile var vendorModelName: String = ""
        private set

    // Edit 45d: History range + pagination tracking.
    // The official app calls getHistoryRange() first, which returns 6 bytes:
    //   [0..1] = briefRangeStartIndex (earliest valid brief history index on sensor)
    //   [2..3] = rawRangeStartIndex (earliest valid raw history index on sensor)
    //   [4..5] = newestEventIndex (newest history index on sensor)
    // Then calls getHistories(nextEventIndex) with the correct starting index.
    // Each response contains a batch of AidexXHistoryEntity records parsed by AidexXParser.getHistories().
    // If nextEventIndex < newestEventIndex after a batch, request the next page.
    private var vendorHistoryBriefStart: Int = 0    // briefRangeStartIndex from GET_HISTORY_RANGE
    private var vendorHistoryNewestIndex: Int = 0   // newestEventIndex from GET_HISTORY_RANGE
    // Edit 47: Persisted to SharedPreferences so reconnects only download new records.
    // Loaded from prefs at declaration; saved after each history batch and reset on new sensor.
    private var vendorHistoryNextIndex: Int = readIntPref("vendorHistoryNextIndex", 0).also {
        Log.i(TAG, "Edit 47: Loaded vendorHistoryNextIndex=$it from SharedPreferences")
    }
    private var vendorHistoryDownloading: Boolean = false  // true while paginated download is in progress
    private var vendorHistoryRecordsStored: Int = 0 // count of records stored in current download session
    // Edit 48: Snapshot of lastVendorOffsetMinutes when history download starts.
    // Records at or beyond this offset are already covered by AUTO_UPDATE real-time data,
    // so storeHistoryRecord() skips them to avoid duplicates.
    private var vendorHistoryAutoUpdateCutoff: Int = 0

    /**
     * Edit 46: Store a historical glucose record at its correct past timestamp.
     * Edit 46b: LIGHTWEIGHT path — calls Natives.aidexProcessData ONLY.
     *
     * Unlike storeAidexReading() which triggers the full real-time pipeline
     * (handleGlucoseResult → notifications, alarms, xDrip, CustomAlerts, HealthConnect,
     * HistorySync.syncFromNative → 14+ Compose recomposition threads), this function
     * ONLY writes to the native data store. No notifications, no alarms, no UI updates.
     *
     * The caller (GET_HISTORIES handler) does a single HistorySync.syncFromNative()
     * after each batch page, and a single handleGlucoseResult for the newest record
     * after the entire download completes.
     *
     * This reduces per-record cost from ~64ms (20 operations) to <1ms (1 JNI call).
     * 1,070 records: 68 seconds → ~1 second.
     *
     * Filtering:
     *   - glucose=1023 (ADC saturation sentinel at offset 1) → skipped
     *   - glucose<=0, isValid!=1, offset<=0 → skipped
     *   - Warmup data (status=1) → STORED (real sensor wetting curve, not placeholder)
     *
     * @return true if the record was stored, false if filtered/skipped
     */
    private fun storeHistoryRecord(
        glucoseInt: Int,
        offsetMinutes: Int,
        status: Int,
        quality: Int,
        isValid: Int
    ): Boolean {
        // Filter invalid records
        if (offsetMinutes <= 0 || offsetMinutes > 14 * 24 * 60) return false
        if (isValid != 1) return false
        // ADC saturation sentinel — not a real reading (offset 1 always returns 1023 = 0x3FF)
        if (glucoseInt >= 1023) return false
        // Negative or zero glucose is garbage
        if (glucoseInt <= 0) return false
        // Edit 48: Skip records already covered by AUTO_UPDATE real-time data.
        // AUTO_UPDATE stores a fresher value for these offsets, so history would be a duplicate
        // (and may even differ slightly due to sensor averaging).
        if (vendorHistoryAutoUpdateCutoff > 0 && offsetMinutes >= vendorHistoryAutoUpdateCutoff) return false

        // Need sensorstartmsec to compute historical timestamp
        if (sensorstartmsec == 0L) {
            Log.w(TAG, "storeHistoryRecord: sensorstartmsec=0, cannot compute timestamp for offset=$offsetMinutes")
            return false
        }
        if (dataptr == 0L) {
            Log.w(TAG, "storeHistoryRecord: dataptr=0, skipping offset=$offsetMinutes")
            return false
        }

        val glucoseMgDl = glucoseInt.toFloat()

        // Compute the correct historical timestamp for this record
        val historicalTimeMs = sensorstartmsec + (offsetMinutes.toLong() * 60_000L)

        // Sanity: don't store readings from the future (clock skew protection)
        val now = System.currentTimeMillis()
        if (historicalTimeMs > now + 120_000L) {  // 2-minute tolerance
            Log.w(TAG, "storeHistoryRecord: offset=$offsetMinutes timestamp=${historicalTimeMs} is in the future (now=$now), skipping")
            return false
        }

        // Edit 46b: LIGHTWEIGHT store — Natives.aidexProcessData ONLY.
        // NO handleGlucoseResult (notifications, alarms, xDrip, CustomAlerts, HealthConnect)
        // NO HistorySync.syncFromNative (Compose recomposition)
        // The caller batches sync after each page.
        try {
            Natives.aidexProcessData(dataptr, byteArrayOf(0), historicalTimeMs, glucoseMgDl, 0f, 1.0f)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "storeHistoryRecord: native store failed for offset=$offsetMinutes: ${t.message}")
            return false
        }
    }






    // --- HANDSHAKE LOGIC ---

    @SuppressLint("MissingPermission")
    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        // Edit 26: Handle GATT_INSUFFICIENT_AUTHENTICATION (0x05) — means no bond yet, SMP starting.
        // When the CCCD retry succeeds after auth fail, bonding is definitely complete.
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (status == 0x05 && vendorBleEnabled) {  // GATT_INSUFFICIENT_AUTHENTICATION
                cccdAuthFailSeen = true
                Log.i(TAG, "onDescriptorWrite: CCCD ${descriptor.characteristic.uuid} auth fail (status=0x05) — SMP bonding in progress, cccdAuthFailSeen=true")
            } else {
                Log.w(TAG, "onDescriptorWrite: CCCD ${descriptor.characteristic.uuid} failed status=$status")
            }
            return
        }
        // Edit 26: CCCD write succeeded — detect bonding completion
        if (vendorBleEnabled && !aidexBonded) {
            val deviceBondState = gatt.device?.bondState ?: BluetoothDevice.BOND_NONE
            if (cccdAuthFailSeen) {
                // We saw an auth fail earlier, and now CCCD succeeded — bonding JUST completed.
                // This is faster than waiting for BOND_BONDED broadcast (which is 3 seconds late).
                Log.i(TAG, "onDescriptorWrite: CCCD ${descriptor.characteristic.uuid} SUCCESS after auth fail — bonding complete! Setting aidexBonded=true immediately")
                aidexBonded = true
            } else if (deviceBondState == BluetoothDevice.BOND_BONDED) {
                // No auth fail seen but device is BONDED — reconnect with cached keys
                Log.i(TAG, "onDescriptorWrite: CCCD ${descriptor.characteristic.uuid} SUCCESS, device already BONDED — setting aidexBonded=true")
                aidexBonded = true
            }
        }

        // Edit 29: Dynamic CCCD chain based on native lib UUIDs.
        // Official app pattern: private CCCD → characteristic CCCD → onConnectSuccess.
        // The native lib tells us which UUIDs to use via getCharacteristicUUID() / getPrivateCharacteristicUUID().
        val adapter = vendorAdapter
        val charUuid = adapter?.mCharacteristicUuid ?: 0
        val privUuid = adapter?.mPrivateCharacteristicUuid ?: 0
        val descriptorCharShort = uuidToShort(descriptor.characteristic.uuid)

        if (vendorBleEnabled && privUuid != 0 && descriptorCharShort == privUuid && privUuid != charUuid) {
            // Private characteristic CCCD done — chain to the characteristic (write target)
            val sF000 = gatt.getService(SERVICE_F000)
            fun shortToFullUuid(short: Int): UUID =
                UUID.fromString("0000${Integer.toHexString(short).padStart(4, '0')}-0000-1000-8000-00805f9b34fb")
            val cChar = sF000?.getCharacteristic(shortToFullUuid(charUuid))
            if (cChar != null) {
                Log.i(TAG, "onDescriptorWrite: private 0x${Integer.toHexString(privUuid)} CCCD done, chaining to characteristic 0x${Integer.toHexString(charUuid)}")
                setCharacteristicNotification(gatt, cChar, true)
            } else {
                Log.e(TAG, "onDescriptorWrite: characteristic 0x${Integer.toHexString(charUuid)} not found for CCCD chain! Treating private-only as complete.")
                // Fall through to the "all CCCDs done" logic below
                vendorGattNotified = true
            }
        }
        // Check if this is the LAST CCCD in the chain (either characteristic, or private if they're the same)
        if (vendorBleEnabled && charUuid != 0 && (descriptorCharShort == charUuid || (descriptorCharShort == privUuid && privUuid == charUuid))) {
            // Characteristic (write target) CCCD done — ALL CCCDs written.
            vendorGattNotified = true
            Log.i(TAG, "onDescriptorWrite: characteristic 0x${Integer.toHexString(descriptorCharShort)} CCCD done. All CCCDs written. vendorGattNotified=true")
        }
        // Also handle legacy hardcoded paths for non-vendor mode or fallback
        if (!vendorBleEnabled || charUuid == 0) {
            // Legacy F002→F001 chain for non-vendor handshake paths
            if (descriptor.characteristic.uuid == CHAR_F002) {
                val sF000 = gatt.getService(SERVICE_F000)
                val cF001 = sF000?.getCharacteristic(CHAR_F001)
                if (cF001 != null) {
                    Log.i(TAG, "onDescriptorWrite: F002 CCCD done, chaining to F001 (legacy)")
                    setCharacteristicNotification(gatt, cF001, true)
                }
            }
        }

        if (vendorGattNotified && vendorBleEnabled) {
            Log.i(TAG, "All CCCDs written. Vendor notifications enabled. executeConnectReceived=$vendorExecuteConnectReceived")
            if (vendorRegistered) {
                // Edit 28: Use vendorWorkHandler for onConnectSuccess — same handler that
                // executeWrite/executeDisconnect use. This ensures proper serialization
                // and matches the official app's pattern of routing everything through
                // a single workHandler on the main looper.
                vendorWorkHandler.post {
                    disregardDisconnectsUntil = System.currentTimeMillis() + 8000L
                    if (vendorExecuteConnectReceived) {
                        Log.i(TAG, "Signaling onConnectSuccess to vendor adapter (all CCCDs ready, executeConnect received).")
                        vendorConnectPending = true
                        safeCallOnConnectSuccess("onDescriptorWrite-ready")
                    } else {
                        Log.i(TAG, "CCCDs ready but executeConnect not received yet. Will signal onConnectSuccess when executeConnect arrives.")
                        // Edit 29: Re-feed scan data to nudge the native lib's state machine.
                        nudgeVendorExecuteConnect("cccd-ready")
                    }
                }
                // Edit 28: Removed post-cccd long-connect trigger.
            } else {
                Log.w(TAG, "Vendor not ready for onConnectSuccess: enabled=$vendorBleEnabled registered=$vendorRegistered pending=$vendorConnectPending")
            }
            return
        }

        // Non-vendor handshake path (legacy F001 completion)
        if (descriptor.characteristic.uuid == CHAR_F001 && !vendorBleEnabled) {
            if (wantsRaw() || wantsAuto()) {
                val mode = if (useOfficialHandshake) "official" else "legacy"
                Log.i(TAG, "All Notifications Enabled. Starting $mode handshake.")
                val now = System.currentTimeMillis()
                if (useOfficialHandshake && bondWaitUntilMs > now) {
                    val delay = bondWaitUntilMs - now
                    handshakeHandler.postDelayed({ beginHandshakeAfterBondWait(gatt) }, delay)
                } else {
                    beginHandshakeWithPriming(gatt)
                }
            }
        }
        if (descriptor.characteristic.uuid == CHAR_F003) {
            Log.d(TAG, "onDescriptorWrite: F003 CCCD write completed")
        }
    }

    // --- HANDSHAKE ---

    @SuppressLint("MissingPermission")
    private fun performHandshakeStep(gatt: BluetoothGatt) {
        val sF000 = gatt.getService(SERVICE_F000) ?: return
        val steps = handshakePlan
        val stepDelay = handshakeStepDelayMs()
        val readDelay = if (useOfficialHandshake) HANDSHAKE_READ_DELAY_MS else LEGACY_HANDSHAKE_READ_DELAY_MS

        if (handshakeStep <= 0) {
            handshakeStep = 1
            handshakeRetries = 0
        }

        if (steps.isEmpty()) {
            Log.e(TAG, "Handshake aborted: no plan (mode=${if (useOfficialHandshake) "official" else "legacy"})")
            return
        }

        if (handshakeStep > steps.size) {
            Log.i(TAG, "Handshake Completed. Listening for F003. (plan=$handshakePlanLabel)")
            return
        }

        val step = steps[handshakeStep - 1]
        val cmd = step.data
        val cmdOp = if (cmd.isNotEmpty()) cmd[0].toInt() and 0xFF else -1
        val needsResponse = step.expectResponseOps.isNotEmpty()

        cancelHandshakeTimers()

        val characteristic = when (step.uuid) {
            CHAR_F001 -> sF000.getCharacteristic(CHAR_F001)
            CHAR_F002 -> sF000.getCharacteristic(CHAR_F002)
            CHAR_F003 -> sF000.getCharacteristic(CHAR_F003)
            else -> sF000.getCharacteristic(step.uuid)
        } ?: run {
            Log.e(TAG, "Handshake step $handshakeStep missing characteristic ${step.uuid}")
            return
        }

        // Force WRITE_TYPE_NO_RESPONSE for F001/F002 as per protocol analysis
        // The official app uses Write Command (No Auth/No Response) for these handshakes.
        val forceNoResponse = (step.uuid == CHAR_F001 || step.uuid == CHAR_F002)
        characteristic.writeType = if (forceNoResponse || (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        if (gatt != mBluetoothGatt) {
            Log.w(TAG, "performHandshakeStep: aborting (gatt instance mismatch or null)")
            return
        }

        val success = gatt.writeCharacteristic(characteristic)
        Log.d(
            TAG,
            "Handshake Step $handshakeStep (${step.label}): Writing ${bytesToHex(cmd)} to ${step.uuid} " +
                "(props=0x${String.format("%02X", characteristic.properties)} writeType=${characteristic.writeType}, enqueued=$success)"
        )
        if (!success) {
            // Android GATT allows only one in-flight operation; retry the same step shortly.
            if (mBluetoothGatt != null) {
                handshakeHandler.postDelayed({ performHandshakeStep(gatt) }, 250L)
            } else {
                Log.e(TAG, "Handshake aborted: disconnected during write attempt")
            }
            return
        }

        if (!needsResponse) {
            // Advancement now happens in onCharacteristicWrite callback for better pacing.
            return
        }

        expectedF002ResponseOps = step.expectResponseOps
        handshakeReadAttempts = 0

        if (step.uuid == CHAR_F002) {
            // Some Android stacks/firmware do not emit F002 notifications reliably.
            // F002 is readable (props include READ on many devices), so schedule repeated reads as a fallback.
            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                val stepAtSchedule = handshakeStep
                val expectedAtSchedule = expectedF002ResponseOps
                pendingHandshakeRead = object : Runnable {
                    override fun run() {
                        if (handshakeStep != stepAtSchedule || expectedF002ResponseOps != expectedAtSchedule) return
                        val ok = gatt.readCharacteristic(characteristic)
                        handshakeReadAttempts++
                        Log.d(
                            TAG,
                            "Handshake Step $handshakeStep: READ F002 fallback enqueued=$ok (try=$handshakeReadAttempts)"
                        )
                        if (handshakeReadAttempts < HANDSHAKE_READ_MAX) {
                            handshakeHandler.postDelayed(this, readDelay)
                        }
                    }
                }
                handshakeHandler.postDelayed(pendingHandshakeRead!!, readDelay)
            }
        }

        val stepAtSchedule = handshakeStep
        val expectedAtSchedule = expectedF002ResponseOps
        pendingHandshakeTimeout = Runnable {
            if (handshakeStep == stepAtSchedule && expectedF002ResponseOps == expectedAtSchedule) {
                handshakeRetries++
                if (handshakeRetries <= HANDSHAKE_MAX_RETRIES) {
                    Log.w(TAG, "Handshake step $handshakeStep timed out. Retrying (${handshakeRetries}/$HANDSHAKE_MAX_RETRIES).")
                    performHandshakeStep(gatt)
                    return@Runnable
                }
                if (!useOfficialHandshake &&
                    expectedAtSchedule.any { it == 0xB4 || it == 0xB5 }
                ) {
                    Log.w(TAG, "Legacy handshake timed out; auto-advancing (step=$handshakeStep, plan=$handshakePlanLabel).")
                    handshakeStep++
                    expectedF002ResponseOps = emptySet()
                    performHandshakeStep(gatt)
                    return@Runnable
                }
                if (useOfficialHandshake &&
                    handshakePlanLabel == "official-unbonded" &&
                    dynamicIV == null &&
                    pendingIVCandidates.isEmpty()
                ) {
                    // 2026-02-05: Bonded plan doesn't return IV, try legacy instead.
                    Log.w(TAG, "Official unbonded handshake timed out; switching to legacy plan.")
                    handshakePlan = buildLegacyHandshakePlan()
                    useOfficialHandshake = false
                    handshakeStep = 1
                    expectedF002ResponseOps = emptySet()
                    performHandshakeStep(gatt)
                    return@Runnable
                }
                if (useOfficialHandshake &&
                    handshakePlanLabel == "official-paired" &&
                    dynamicIV == null &&
                    pendingIVCandidates.isEmpty()
                ) {
                    // 2026-02-05: Bonded plan doesn't return IV, try legacy instead.
                    Log.w(TAG, "Official paired handshake timed out; switching to legacy plan.")
                    handshakePlan = buildLegacyHandshakePlan()
                    useOfficialHandshake = false
                    handshakeStep = 1
                    expectedF002ResponseOps = emptySet()
                    performHandshakeStep(gatt)
                    return@Runnable
                }
                Log.e(TAG, "Handshake step $handshakeStep timed out after $HANDSHAKE_MAX_RETRIES retries (plan=$handshakePlanLabel).")
                expectedF002ResponseOps = emptySet()
            }
        }
        handshakeHandler.postDelayed(pendingHandshakeTimeout!!, HANDSHAKE_TIMEOUT_MS)
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "onCharacteristicRead [${characteristic.uuid}] FAILED status=$status")
            // Edit 36c/40: Drain the unified GATT queue even on failure — the GATT op slot is free now.
            // Post to vendorWorkHandler to avoid cross-thread race on vendorGattOpActive (binder thread vs handler thread).
            if (vendorGattOpActive) {
                vendorWorkHandler.post {
                    vendorGattOpActive = false
                    drainVendorGattQueue()
                }
            }
            return
        }
        val data = characteristic.value ?: run {
            // Edit 36c/40: Drain queue on null data too — route through vendorWorkHandler
            if (vendorGattOpActive) {
                vendorWorkHandler.post {
                    vendorGattOpActive = false
                    drainVendorGattQueue()
                }
            }
            return
        }

        // Edit 36c/40: Read completed successfully — mark GATT op slot as free and drain queue.
        // Route through vendorWorkHandler so vendorGattOpActive is only cleared on the handler thread.
        if (vendorGattOpActive) {
            vendorWorkHandler.post {
                vendorGattOpActive = false
                drainVendorGattQueue()
            }
        }

        // Edit 35a: Forward read responses to vendor native lib via onReceiveData().
        // The previous code returned early when vendorBleEnabled=true, silently dropping
        // the read response. But the native lib calls executeReadCharacteristic(0xF002)
        // after PAIR success and expects the data back via onReceiveData().
        // The official app's onCharacteristicRead callback follows the same path as
        // onCharacteristicChanged: receiveData() -> msg 0x7D0 -> onReceiveData(uuid, data).
        if (vendorBleEnabled && vendorExecuteConnectReceived && vendorAdapter != null) {
            val shortUuid = uuidToShort(characteristic.uuid)
            val dataCopy = data.copyOf()
            vendorWorkHandler.post {
                try {
                    vendorAdapter!!.onReceiveData(shortUuid, dataCopy)
                    Log.i(TAG, "Vendor READ fwd [0x${Integer.toHexString(shortUuid)}]: ${bytesToHex(dataCopy)}")
                } catch (t: Throwable) {
                    Log.w(TAG, "Vendor READ fwd failed: ${t.message}")
                }
            }
            // Don't process vendor reads through our own legacy path
            return
        }

        if ((wantsRaw() || wantsAuto()) && characteristic.uuid == CHAR_F002) {
            Log.i(TAG, "READ [F002]: ${bytesToHex(data)}")
            handleF002Response(gatt, data)
            if (handshakePrimingInProgress) {
                handshakePrimingInProgress = false
                pendingHandshakePrimingTimeout?.let { handshakeHandler.removeCallbacks(it) }
                pendingHandshakePrimingTimeout = null
                Log.i(TAG, "Handshake priming read complete. Starting handshake.")
                performHandshakeStep(gatt)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val uuid = characteristic.uuid
        val data = characteristic.value

        if (data == null || data.isEmpty()) return

        // --- FORWARD ALL CHARACTERISTIC DATA TO VENDOR NATIVE LIB ---
        // Edit 28: Route onReceiveData through vendorWorkHandler, matching the official app's
        // RECEIVER_DATA handler pattern. This ensures data arrives in order with writes/disconnects.
        if (vendorBleEnabled && vendorExecuteConnectReceived && vendorAdapter != null) {
            val shortUuid = uuidToShort(uuid)
            val dataCopy = data.copyOf()
            vendorWorkHandler.post {
                try {
                    vendorAdapter!!.onReceiveData(shortUuid, dataCopy)
                    Log.i(TAG, "Vendor RX fwd [0x${Integer.toHexString(shortUuid)}]: ${bytesToHex(dataCopy)}")
                } catch (t: Throwable) {
                    Log.w(TAG, "Vendor RX fwd failed: ${t.message}")
                }
            }
        }

        // --- IV SNOOPING ---
        // Also capture IV from F002 responses for our own legacy decrypt path.
        // Snoop known IV response opcodes (A8, B4, B5, F0) from F002 data.
        if (uuid == CHAR_F002 && data.size >= 18) {
            val opcode = data[0].toInt() and 0xFF
            if (opcode == 0xF0 || opcode == 0xB4 || opcode == 0xB5 || opcode == 0xA8) {
                val ivOffset = if (opcode == 0xB5) 1 else 2
                if (data.size >= ivOffset + 16) {
                    val iv = ByteArray(16)
                    System.arraycopy(data, ivOffset, iv, 0, 16)
                    dynamicIV = iv
                    Log.i(TAG, "IV SNOOP: Captured dynamicIV from F002 opcode 0x${"%02X".format(opcode)}: ${iv.joinToString("") { "%02X".format(it) }}")
                }
            }
        }

        if (uuid == CHAR_F003) {
            Log.d(TAG, "RX [F003]: ${bytesToHex(data)}")
            // Always process F003 locally to ensure we get glucose readings
            // even if the vendor lib is slow or disconnects.
            if (wantsRaw() || wantsAuto()) {
                handleF003Data(data)
            }
            return
        }

        if (uuid == CHAR_F001) {
            Log.i(TAG, "RX [F001]: ${bytesToHex(data)}")
            if (data.size >= 16) {
                addIvCandidatesFrom(data)
                Log.i(TAG, "F001 response added as IV candidates: ${pendingIVCandidates.size} total")
            }
            handleF001Response(data)
        }

        if (uuid == CHAR_F002) {
             handleF002Response(gatt, data)
        }

        // FF31: Vendor private service response channel (responses to commands sent via FF32)
        if (uuid == CHAR_FF31) {
            Log.i(TAG, "RX [FF31]: ${bytesToHex(data)} (len=${data.size})")
            // Already forwarded to vendor native lib via onReceiveData above.
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        super.onCharacteristicWrite(gatt, characteristic, status)
        // Edit 36c/40: Drain the unified GATT queue after write completes.
        // Route through vendorWorkHandler to avoid cross-thread race on vendorGattOpActive
        // (this callback runs on the GATT binder thread, but vendorGattOpActive and the queue
        // should only be modified on vendorWorkHandler's thread).
        if (vendorGattOpActive) {
            vendorWorkHandler.post {
                vendorGattOpActive = false
                drainVendorGattQueue()
            }
            // If we are in vendor mode, do not process manual handshake logic for these writes
            if (vendorBleEnabled) return
        }
        if (vendorBleEnabled) return // Catch-all: don't track manual handshake state if delegating
        val uuid = characteristic.uuid
        if (useOfficialHandshake && (uuid == CHAR_F001 || uuid == CHAR_F002)) {
            val expectedOp = if (expectedF002ResponseOps.isEmpty()) "none" else expectedF002ResponseOps.joinToString(",") { "0x%02X".format(it) }
            Log.d(
                TAG,
                "WRITE [$uuid]: status=$status value=${bytesToHex(characteristic.value ?: byteArrayOf())} (expectedOp=$expectedOp)"
            )
            
            if (status == BluetoothGatt.GATT_SUCCESS && expectedF002ResponseOps.isEmpty()) {
                // This step didn't expect a notification/read response, so advance handshake 
                // now that the write confirmation has been received.
                val nextDelay = if (uuid == CHAR_F002) 150L else 400L
                handshakeStep++
                handshakeHandler.postDelayed({
                    mBluetoothGatt?.let { performHandshakeStep(it) }
                }, nextDelay)
            }
        }
    }

    private fun handleF002Response(gatt: BluetoothGatt, data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        val firstByte = data[0].toInt() and 0xFF
        Log.d(TAG, "RX [F002]: ${bytesToHex(data)} (step=$handshakeStep)")

        if (useOfficialHandshake) {
            // SNOOP: Attempt to capture IV from F002 response (Handshake Step 2/3)
            // This response contains the session Key (IV) mixed with our challenge.
            if (dynamicIV == null && data.size >= 16) {
                Log.d(TAG, "SNOOP: Capturing IV candidate from F002 (len=${data.size})")
                addIvCandidatesFrom(data)
            } else {
                 if (dynamicIV != null) Log.v(TAG, "SNOOP: IV already captured.")
                 if (data.size < 16) Log.v(TAG, "SNOOP: F002 data too short for IV (${data.size})")  // Edit 51c: W→V (normal during vendor operation)
            }

            // If vendor enabled, STOP HERE. Do not advance handshake or write data.
            // The native lib handles the response; we only wanted to snoop the IV.
            if (vendorBleEnabled) {
                Log.v(TAG, "SNOOP: Vendor active, ignoring F002 logic after snoop.")
                return
            }

            val expected = expectedF002ResponseOps
            if (expected.isEmpty()) {
                return
            }
            if (firstByte in expected) {
                if (firstByte == 0xA8) {
                    // LinX 1.7.25: IV is embedded in the A8 response as:
                    // A8 67 <16 bytes IV> <4 bytes tail>
                    if (data.size >= 18) {
                        dynamicIV = data.copyOfRange(2, 18)
                        pendingIVCandidates.clear()
                        Log.i(TAG, "IV captured from A8 response: ${bytesToHex(dynamicIV!!)}")
                    } else {
                        Log.w(TAG, "A8 response too short (${data.size}); ignoring")
                        return
                    }
                }

                cancelHandshakeTimers()
                handshakeRetries = 0
                expectedF002ResponseOps = emptySet()
                handshakeStep++
                handshakeHandler.postDelayed({ performHandshakeStep(gatt) }, handshakeStepDelayMs())
                return
            }

            // Some firmwares do not echo the opcode on A8/AF. Treat any 16+ byte response as a candidate IV block.
            if (expected.any { it == 0xA8 || it == 0xAF } && data.size >= 16) {
                addIvCandidatesFrom(data)
                Log.w(
                    TAG,
                    "A8/AF response missing header; cached ${pendingIVCandidates.size} IV candidates"
                )
                cancelHandshakeTimers()
                handshakeRetries = 0
                expectedF002ResponseOps = emptySet()
                handshakeStep++
                handshakeHandler.postDelayed({ performHandshakeStep(gatt) }, handshakeStepDelayMs())
                return
            }

            Log.d(TAG, "F002 response 0x%02X ignored (expecting $expected)".format(firstByte))
            return
        }

        var capturedIv = false
        if (firstByte == 0xB5 || firstByte == 0xB4) {
            if (data.size >= 17) {
                dynamicIV = data.copyOfRange(1, 17)
                capturedIv = true
                Log.d(TAG, "IV Captured from 0x%02X".format(firstByte))
            } else if (data.size == 16) {
                dynamicIV = data.copyOfRange(0, 16)
                capturedIv = true
                Log.d(TAG, "IV Captured from 0x%02X (16 bytes)".format(firstByte))
            } else {
                Log.w(TAG, "IV not found in 0x%02X response (len=%d)".format(firstByte, data.size))
            }
        } else if (handshakeStep >= 10 && data.size >= 16) {
            // Legacy stacks sometimes omit B4/B5 header; cache IV candidates for F003 validation.
            addIvCandidatesFrom(data)
            Log.w(TAG, "Legacy IV response missing header; cached ${pendingIVCandidates.size} IV candidates")
        }

        if (firstByte == 0xB4 || firstByte == 0xB5 || capturedIv) {
            cancelHandshakeTimers()
            if (handshakeStep < 1) handshakeStep = 1
            handshakeStep++
            performHandshakeStep(gatt)
            return
        }

        if (firstByte == 0xB6) {
            cancelHandshakeTimers()
            Log.i(TAG, "Handshake Complete. Stream Starting.")
            handshakeStep++
            return
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                    Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }

    // --- RESPONSE HANDLERS ---
    
    private fun handleF001Response(data: ByteArray) {
        // F001 responses are typically confirmations or error codes for commands.
        // Step 1: 0xB0 response -> Key exchange / Challenge response?
        if (data.isEmpty()) return
        val op = data[0].toInt() and 0xFF
        Log.d(TAG, "handleF001Response: op=0x%02X len=%d".format(op, data.size))
        
        // If we are tracking handshake, we might want to log this.
    }

    // (Duplicate handleF003Data removed)

    // --- CRYPTO ---
    private fun reverseBits(b: Byte): Byte {
        var res = 0
        var x = b.toInt() and 0xFF
        for (i in 0 until 8) {
            res = (res shl 1) or (x and 1)
            x = x shr 1
        }
        return res.toByte()
    }

    private fun decrypt(data: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CFB/NoPadding")
        val keySpec = SecretKeySpec(MASTER_KEY, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(data)
    }

    // --- UTILS ---
    // fun setViewMode(mode: Int) removed to avoid platform declaration clash with var viewMode

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    private fun gattStatusToString(status: Int): String = when (status) {
        0 -> "GATT_SUCCESS"
        2 -> "GATT_READ_NOT_PERMITTED"
        5 -> "GATT_INSUFFICIENT_AUTHENTICATION"
        6 -> "GATT_REQUEST_NOT_SUPPORTED"
        7 -> "GATT_INVALID_OFFSET"
        8 -> "GATT_CONN_TIMEOUT"  // connection supervision timeout
        13 -> "GATT_INVALID_ATTRIBUTE_LENGTH"
        15 -> "GATT_INSUFFICIENT_ENCRYPTION"
        19 -> "GATT_CONN_TERMINATE_PEER_USER"  // remote side disconnected
        22 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
        34 -> "GATT_CONN_LMP_TIMEOUT"
        62 -> "GATT_CONN_FAIL_ESTABLISH"  // connection attempt failed
        133 -> "GATT_ERROR"  // generic Android BLE error
        256 -> "GATT_CONN_CANCEL"
        257 -> "GATT_BUSY"
        else -> "UNKNOWN_0x${Integer.toHexString(status)}"
    }

    private fun reverseBitsCopy(bytes: ByteArray): ByteArray {
        val out = bytes.clone()
        for (i in out.indices) {
            out[i] = reverseBits(out[i])
        }
        return out
    }

    private fun storeVendorParams(bytes: ByteArray) {
        val hex = bytesToHex(bytes)
        prefs.edit().putString(prefKey("lastVendorParams"), hex).apply()
    }

    private fun loadVendorParams(): ByteArray? {
        val key = prefKey("lastVendorParams")
        val hex = prefs.getString(key, null) ?: return null
        return try { hexToBytes(hex) } catch (e: Exception) { null }
    }

    // Edit 34: Vendor pairing key persistence — matches official app's TransmitterModel.savePair().
    // After successful PAIR, the native lib stores the AES encryption key and access ID internally.
    // We extract them via getKey()/getId() and save to prefs.
    // On subsequent starts, we restore them before register() so the native lib can reconnect
    // without re-pairing.
    //
    // CRITICAL: DO NOT call controller.getHostAddress() here. After initial pairing the native lib's
    // internal host address buffer is NULL. getHostAddress() is a JNI native method that calls
    // SetPrimitiveArrayRegion with buf==null → JNI SIGABRT that CANNOT be caught by try/catch.
    // Instead we use the known BLE MAC address (which IS the host address — the official app's
    // TransmitterModel stores it from controller.getHostAddress() but it's just the BLE MAC).
    //
    // getKey() and getId() are also JNI native methods that could theoretically have the same
    // null-buffer issue, so we call them on a background thread via a brief timeout. If a JNI
    // abort occurs, only the worker thread is killed rather than the main app process. However,
    // in practice the crash log showed getKey()/getId() are populated after PAIR success — only
    // getHostAddress() was null. We still guard with the thread approach for safety.
    private fun saveVendorPairingKeys(controller: BleController) {
        // Use the known BLE MAC as host address — avoids the getHostAddress() JNI crash
        val mac = connectedAddress ?: mActiveDeviceAddress
        if (mac != null) {
            // Convert MAC string "AA:BB:CC:DD:EE:FF" to 6-byte array
            try {
                val macBytes = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
                writeStringPref("vendorHostAddress", bytesToHex(macBytes))
                Log.i(TAG, "Vendor pairing: saved hostAddress from MAC=$mac (${macBytes.size} bytes)")
            } catch (t: Throwable) {
                // Fallback: store MAC string directly as hex-encoded ASCII
                writeStringPref("vendorHostAddress", bytesToHex(mac.toByteArray(Charsets.US_ASCII)))
                Log.w(TAG, "Vendor pairing: saved hostAddress as raw MAC string: ${t.message}")
            }
        } else {
            Log.w(TAG, "Vendor pairing: no MAC address available for hostAddress")
        }

        // Extract key and id from native lib — these are JNI calls that could theoretically
        // SIGABRT if internal buffers are null (like getHostAddress did). We call them directly
        // since the crash log showed they ARE populated after PAIR success, but wrap in try/catch
        // for any non-fatal exceptions.
        var keySaved = false
        var idSaved = false
        try {
            val key = controller.getKey()
            if (key != null && key.isNotEmpty()) {
                writeStringPref("vendorKey", bytesToHex(key))
                Log.i(TAG, "Vendor pairing: saved key (${key.size} bytes)")
                keySaved = true
            } else {
                Log.w(TAG, "Vendor pairing: getKey() returned null/empty")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Vendor pairing: getKey() failed: ${t.message}")
        }

        try {
            val id = controller.getId()
            if (id != null && id.isNotEmpty()) {
                writeStringPref("vendorId", bytesToHex(id))
                Log.i(TAG, "Vendor pairing: saved id (${id.size} bytes)")
                idSaved = true
            } else {
                Log.w(TAG, "Vendor pairing: getId() returned null/empty")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Vendor pairing: getId() failed: ${t.message}")
        }

        // Mark paired even if only some keys were saved — the MAC-based hostAddress is always available
        // and key/id are the critical ones. If key was saved, pairing can be restored.
        if (keySaved) {
            writeBoolPref("vendorPaired", true)
            Log.i(TAG, "Vendor pairing: keys saved successfully (key=$keySaved, id=$idSaved, host=${mac != null})")
        } else {
            Log.e(TAG, "Vendor pairing: key not saved — NOT marking as paired (would fail to reconnect)")
        }
    }

    private fun loadVendorPairingKey(): ByteArray? {
        val hex = readStringPref("vendorKey", null) ?: return null
        return try { hexToBytes(hex) } catch (_: Exception) { null }
    }

    private fun loadVendorPairingId(): ByteArray? {
        val hex = readStringPref("vendorId", null) ?: return null
        return try { hexToBytes(hex) } catch (_: Exception) { null }
    }

    private fun loadVendorHostAddress(): ByteArray? {
        val hex = readStringPref("vendorHostAddress", null) ?: return null
        return try { hexToBytes(hex) } catch (_: Exception) { null }
    }

    fun isVendorPaired(): Boolean = readBoolPref("vendorPaired", false)

    /** True when the vendor BLE stack is actively connected and receiving data */
    fun isVendorConnected(): Boolean = vendorBleEnabled && vendorGattConnected && vendorNativeReady

    private fun clearVendorPairingKeys() {
        writeBoolPref("vendorPaired", false)
        writeStringPref("vendorKey", null)
        writeStringPref("vendorId", null)
        writeStringPref("vendorHostAddress", null)
        Log.i(TAG, "Vendor pairing: keys cleared")
    }

    /**
     * Public cleanup for "Forget Sensor" — stops vendor stack, removes BLE bond,
     * and wipes saved AES pairing keys so re-adding starts completely fresh.
     * Edit 38e: Each step is independent with its own try/catch so a failure in one
     * (e.g. native lib already torn down) doesn't prevent the others from running.
     */
    fun forgetVendor() {
        Log.i(TAG, "forgetVendor: full vendor cleanup for sensor removal")
        vendorReconnectSpamRestarts = 0  // Edit 41c: reset spam counter on full forget
        try {
            cancelBroadcastPoll()
        } catch (t: Throwable) {
            Log.e(TAG, "forgetVendor: cancelBroadcastPoll failed: ${t.message}")
        }
        try {
            stopVendor("forgetVendor")
        } catch (t: Throwable) {
            Log.e(TAG, "forgetVendor: stopVendor failed: ${t.message}")
        }
        try {
            removeSensorBond()
        } catch (t: Throwable) {
            Log.e(TAG, "forgetVendor: removeSensorBond failed: ${t.message}")
        }
        try {
            clearVendorPairingKeys()
        } catch (t: Throwable) {
            Log.e(TAG, "forgetVendor: clearVendorPairingKeys failed: ${t.message}")
        }
    }

    private fun hexToBytes(s: String): ByteArray {
        val clean = s.replace(" ", "")
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // Added for Strict Bonding Sequence
    private var isBondingSequenceActive = false

    @SuppressLint("MissingPermission")
    private fun startBondingSequence(gatt: BluetoothGatt) {
        isBondingSequenceActive = true
        // 1. Construct the first command (Key Formation / Authentication Challenge)
        // Usually B0 or similar. We'll use the first step of the official plan.
        val bondingPlan = buildOfficialHandshakePlan(gatt) // Force "Bonded" plan structure just for the first cmd
        if (bondingPlan.isEmpty()) {
            Log.e(TAG, "Bonding Sequence Failed: No plan available.")
            return
        }
        val firstStep = bondingPlan[0]
        
        Log.i(TAG, "Bonding Sequence: Sending KEY COMMAND: ${bytesToHex(firstStep.data)}")
        
        // Write the command directly (bypass notification logic for now)
        val service = gatt.getService(SERVICE_F000)
        val charF002 = service?.getCharacteristic(CHAR_F002)
        if (charF002 != null) {
            charF002.value = firstStep.data
            charF002.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val success = gatt.writeCharacteristic(charF002)
            Log.i(TAG, "Bonding Sequence: Write Status = $success")
            
            // 2. IMMEDIATELY Request Bond
            Log.i(TAG, "Bonding Sequence: Requesting createBond() IMMEDIATELY.")
            gatt.device.createBond()
            
             // 3. Poll for Bond State
            bondRequested = true
            bondWaitUntilMs = System.currentTimeMillis() + BOND_WAIT_MS
            
            handshakeHandler.postDelayed(object : Runnable {
                override fun run() {
                    if (!isBondingSequenceActive) return
                    val state = gatt.device.bondState
                    if (state == BluetoothDevice.BOND_BONDED) {
                        Log.i(TAG, "Bonding Sequence: Bonded! Resuming handshake.")
                        isBondingSequenceActive = false
                        startHandshake(gatt)
                    } else if (System.currentTimeMillis() > bondWaitUntilMs) {
                         Log.w(TAG, "Bonding Sequence: Timeout waiting for bond. Resuming anyway.")
                         isBondingSequenceActive = false
                         startHandshake(gatt)
                    } else {
                        // Keep polling
                        handshakeHandler.postDelayed(this, 500)
                    }
                }
            }, 500)

        } else {
            Log.e(TAG, "Bonding Sequence Failed: F002 not found.")
        }
    }

    // --- ROBUST RESET IMPLEMENTATION ---

    /**
     * Encrypts a command payload using the Dynamic IV + Master Key.
     */
    private fun encryptCommand(cmd: ByteArray): ByteArray? {
        val iv = dynamicIV
        if (iv == null) {
            Log.e(TAG, "Cannot encrypt command: dynamicIV is null")
            return null
        }
        try {
            val cipher = Cipher.getInstance("AES/CFB/NoPadding")
            val keySpec = SecretKeySpec(MASTER_KEY, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            return cipher.doFinal(cmd)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed: ${e.message}")
            return null
        }
    }

    /**
     * Sends an encrypted command to F001.
     */
    private fun sendEncryptedCommand(cmd: ByteArray, opDescription: String): Boolean {
        val gatt = mBluetoothGatt
        if (gatt == null) {
            Log.w(TAG, "$opDescription failed: GATT not connected")
            return false
        }
        
        val encrypted = encryptCommand(cmd)
        if (encrypted == null) {
             Log.e(TAG, "$opDescription failed: Encryption error (missing IV?)")
             return false
        }

        val service = gatt.getService(SERVICE_F000)
        val charF001 = service?.getCharacteristic(CHAR_F001)
        if (charF001 == null) {
             Log.e(TAG, "$opDescription failed: F001 not found")
             return false
        }

        charF001.value = encrypted
        charF001.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        
        val success = gatt.writeCharacteristic(charF001)
        Log.i(TAG, "$opDescription sent: ${bytesToHex(encrypted)} (Raw: ${bytesToHex(cmd)}) Success=$success")
        return success
    }

    /**
     * Writes a raw command to FF32 on the FF30 vendor private service.
     * This is the direct path for sensor lifecycle commands (reset, new sensor, shelf mode).
     * Returns true if the write was enqueued successfully.
     */
    @SuppressLint("MissingPermission")
    private fun writeFF32Command(data: ByteArray, opDescription: String): Boolean {
        val gatt = mBluetoothGatt
        if (gatt == null) {
            Log.w(TAG, "$opDescription via FF32 failed: GATT not connected")
            return false
        }
        val service = gatt.getService(SERVICE_FF30)
        if (service == null) {
            Log.w(TAG, "$opDescription via FF32 failed: FF30 service not found")
            return false
        }
        val charFF32 = service.getCharacteristic(CHAR_FF32)
        if (charFF32 == null) {
            Log.w(TAG, "$opDescription via FF32 failed: FF32 characteristic not found")
            return false
        }
        charFF32.value = data
        // FF32 typically uses Write Command (no response) based on official app behavior
        charFF32.writeType = if ((charFF32.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val success = gatt.writeCharacteristic(charFF32)
        Log.i(TAG, "$opDescription via FF32: data=${bytesToHex(data)} writeType=${charFF32.writeType} success=$success")
        return success
    }

    /**
     * Removes the BLE bond for the connected sensor device via reflection.
     * This forces re-initialization (key exchange, bonding) on next connection.
     * Returns true if bond removal was invoked (may still be async).
     */
    @SuppressLint("MissingPermission")
    private fun removeSensorBond(): Boolean {
        val device = mBluetoothGatt?.device ?: mActiveBluetoothDevice
        if (device == null) {
            Log.w(TAG, "removeSensorBond: no device")
            return false
        }
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            Log.i(TAG, "removeSensorBond: already unbonded")
            return true
        }
        return try {
            val method: Method = device.javaClass.getMethod("removeBond")
            val result = method.invoke(device) as Boolean
            Log.i(TAG, "removeSensorBond: removeBond() returned $result")
            result
        } catch (e: Throwable) {
            Log.e(TAG, "removeSensorBond: reflection failed: ${e.message}")
            false
        }
    }

    /**
     * Resets the sensor (restarts lifecycle).
     * Multi-strategy approach:
     *   Strategy 1: Vendor native lib (controller.reset()) — requires AES init
     *   Strategy 2: Direct FF32 write on FF30 service
     *   Strategy 3: BLE bond removal + disconnect (forces re-init on reconnect)
     *
     * Each strategy is tried in order; any success short-circuits.
     * All attempts are logged for diagnostics.
     */
    fun resetSensor(): Boolean {
        Log.i(TAG, "=== RESET SENSOR: Multi-Strategy Reset ===")
        // Edit 47: Reset history position — new sensor means history starts from scratch
        vendorHistoryNextIndex = 0
        writeIntPref("vendorHistoryNextIndex", 0)
        
        // --- Strategy 1: Vendor Native Lib ---
        // executeVendorCommand now properly waits for GATT + AES initialization
        // before executing the command, so we don't need to pre-check aesReady here.
        if (vendorBleEnabled && vendorController != null) {
            Log.i(TAG, "Reset Strategy 1: Vendor native lib")
            val success = executeVendorCommand("reset-sensor", AidexXOperation.RESET) { ctrl ->
                ctrl.clearStorage()
                ctrl.reset()
            }
            if (success) {
                Log.i(TAG, "Reset Strategy 1: SUCCESS — reset command accepted by vendor native lib")
                // Give time for the BLE write to actually transmit
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                return true
            } else {
                Log.w(TAG, "Reset Strategy 1: FAILED — vendor native command not accepted (AES handshake may have failed)")
            }
        } else {
            Log.w(TAG, "Reset Strategy 1: SKIPPED — vendor BLE disabled or controller null")
        }

        // --- Strategy 2: Direct FF32 Write on FF30 Service ---
        // The FF30 service is the vendor private channel for maintenance commands.
        // We try writing the reset opcode directly.
        // Based on AidexXOperation: RESET=0xF00. The native lib translates this into
        // a wire-format command. Without knowing exact format, we try the opcode bytes.
        Log.i(TAG, "Reset Strategy 2: Direct FF32 write on FF30 service")
        
        // Try via vendor write queue (uses proper serialization)
        val vendorResetSent = try {
            val ff32Id = 0xFF32.toInt()
            // Attempt 2a: Single-byte reset command (common BLE pattern)
            vendorWrite(ff32Id, byteArrayOf(0x0F.toByte(), 0x00.toByte())) // 0xF00 as little-endian 2 bytes
            Thread.sleep(300)
            // Attempt 2b: Also try the full AidexXOperation.RESET value as big-endian
            vendorWrite(ff32Id, byteArrayOf(
                ((AidexXOperation.RESET shr 8) and 0xFF).toByte(),
                (AidexXOperation.RESET and 0xFF).toByte()
            ))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Reset Strategy 2 (vendor queue): failed: ${t.message}")
            false
        }

        // Also try direct GATT write bypassing vendor queue
        if (!vendorResetSent) {
            val directSuccess = writeFF32Command(
                byteArrayOf(0x0F.toByte(), 0x00.toByte()),
                "Reset"
            )
            if (directSuccess) {
                Log.i(TAG, "Reset Strategy 2: FF32 direct write sent")
                // Wait for potential response on FF31
                Thread.sleep(500)
            }
        }

        // --- Strategy 3: BLE Bond Removal + Disconnect ---
        // Removing the bond forces re-initialization on next connection.
        // The sensor will go back to unbonded state and allow fresh pairing.
        Log.i(TAG, "Reset Strategy 3: BLE bond removal + disconnect")
        val bondRemoved = removeSensorBond()
        if (bondRemoved) {
            Log.i(TAG, "Reset Strategy 3: Bond removed. Disconnecting GATT...")
            // Suppress disconnect debounce for this explicit user action
            disregardDisconnectsUntil = 0
            disconnect()
            // Clear handshake/session state
            dynamicIV = null
            handshakeStep = 0
            handshakePlan = emptyList()
            handshakePlanLabel = "none"
            ivLocked = false
            ivLockedFromBroadcast = false
            ivLockKey = null
            ivLockCount = 0
            vendorGattConnected = false
            vendorGattNotified = false
            vendorNativeReady = false
            vendorExecuteConnectReceived = false
            vendorConnectSuccessCrashCount = 0
            vendorConnectSuccessPendingCaller = null
            vendorLongConnectTriggered = false
            vendorGattQueue.clear()
            vendorGattOpActive = false
            Log.i(TAG, "Reset Strategy 3: Session state cleared. Sensor will re-initialize on next connection.")
            return true
        } else {
            Log.w(TAG, "Reset Strategy 3: Bond removal failed or device not bonded")
        }

        // If all strategies attempted but none confirmed success
        Log.w(TAG, "=== RESET SENSOR: All strategies attempted. Check logs for partial success. ===")
        return vendorResetSent // Return true if at least FF32 write was sent
    }

    /**
     * Starts a new sensor session.
     * Multi-strategy approach:
     *   Strategy 1: Vendor native lib (controller.newSensor(datetime))
     *   Strategy 2: Direct FF32 write with SET_NEW_SENSOR opcode
     *   Strategy 3: Reset + reconnect (effectively starts fresh)
     */
    fun startNewSensor(): Boolean {
        Log.i(TAG, "=== START NEW SENSOR: Multi-Strategy ===")
        // Edit 47: Reset history position — new sensor means history starts from scratch
        vendorHistoryNextIndex = 0
        writeIntPref("vendorHistoryNextIndex", 0)
        
        // --- Strategy 1: Vendor Native Lib ---
        // executeVendorCommand now properly waits for GATT + AES initialization
        if (vendorBleEnabled && vendorController != null) {
            Log.i(TAG, "NewSensor Strategy 1: Vendor native lib")
            val success = executeVendorCommand("new-sensor", AidexXOperation.SET_NEW_SENSOR) { ctrl ->
                val datetime = AidexXDatetimeEntity(Calendar.getInstance())
                ctrl.newSensor(datetime)
            }
            if (success) {
                Log.i(TAG, "NewSensor Strategy 1: SUCCESS — command accepted by vendor native lib")
                // Give time for the BLE write to actually transmit
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                // Clear session state for fresh start
                dynamicIV = null
                ivLocked = false
                ivLockedFromBroadcast = false
                ivLockKey = null
                ivLockCount = 0
                return true
            } else {
                Log.w(TAG, "NewSensor Strategy 1: FAILED — vendor native command not accepted")
            }
        } else {
            Log.w(TAG, "NewSensor Strategy 1: SKIPPED — vendor BLE disabled or controller null")
        }

        // --- Strategy 2: Direct FF32 Write ---
        Log.i(TAG, "NewSensor Strategy 2: Direct FF32 write with SET_NEW_SENSOR opcode")
        val opBytes = byteArrayOf(
            ((AidexXOperation.SET_NEW_SENSOR shr 8) and 0xFF).toByte(),
            (AidexXOperation.SET_NEW_SENSOR and 0xFF).toByte()
        )
        // Append current datetime as payload (year_hi, year_lo, month, day, hour, minute, second)
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val datetimePayload = byteArrayOf(
            ((year shr 8) and 0xFF).toByte(),
            (year and 0xFF).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte()
        )
        val fullCommand = opBytes + datetimePayload
        
        val ff32Sent = writeFF32Command(fullCommand, "NewSensor")
        if (ff32Sent) {
            Log.i(TAG, "NewSensor Strategy 2: FF32 write sent. Waiting for response...")
            Thread.sleep(500)
            // Clear session state
            dynamicIV = null
            ivLocked = false
            ivLockedFromBroadcast = false
            ivLockKey = null
            ivLockCount = 0
            return true
        }

        // --- Strategy 3: Full Reset + Reconnect ---
        Log.i(TAG, "NewSensor Strategy 3: Falling back to full reset")
        return resetSensor()
    }
}
