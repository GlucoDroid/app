// JugglucoNG — MQ/Glutec CGM Driver
// MQConstants.kt — Nordic UART Service UUIDs, packet header, command bytes
//
// Protocol: Nordic UART Service (NUS) frames in the form
//   {0x5A, 0xA5, CMD, LEN, ...payload..., CRC_HI, CRC_LO}
// No encryption. CRC16-Modbus (poly 0xA001).
// Separate transmitter + disposable sensor patch.
//
// Reverse-engineered from MQ/Glutec app (com.ruapp.glutec v1.0.1.6).

package tk.glucodata.drivers.mq

import java.util.Locale
import java.util.UUID

object MQConstants {

    const val TAG = "MQ"
    const val DEFAULT_DISPLAY_NAME = "Glutec CGM"
    const val PROVISIONAL_SENSOR_PREFIX = "MQ-"
    const val MAX_NATIVE_SENSOR_ID_CHARS = 16

    // ---- BLE UUIDs (Nordic UART Service) ----

    /** Nordic UART Service */
    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** TX characteristic (transmitter → phone, NOTIFY). */
    val NUS_TX_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** RX characteristic (phone → transmitter, WRITE / WRITE_NO_RESPONSE). */
    val NUS_RX_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** Client Characteristic Configuration Descriptor (standard). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---- Packet framing ----

    const val HEADER0: Byte = 0x5A
    const val HEADER1: Byte = -0x5B // 0xA5
    val HEADER: ByteArray = byteArrayOf(HEADER0, HEADER1)

    /** Fixed overhead bytes per framed packet: 2 header + 1 cmd + 1 len + 2 CRC. */
    const val FRAME_OVERHEAD = 6

    /** Offsets within a framed packet. */
    const val OFFSET_CMD = 2
    const val OFFSET_LEN = 3
    const val OFFSET_PAYLOAD = 4

    // ---- Commands (byte[2] in every framed packet) ----

    /** TX → RX heartbeat while the transmitter has no new data. */
    const val CMD_NOTIFY_WORKING: Byte = 0x01

    /** RX → TX acknowledgement of a NOTIFY_BG_DATA frame. */
    const val CMD_WRITE_BG_DATA_CONFIRM: Byte = 0x02

    /** RX → TX continue without re-initializing the transmitter. */
    const val CMD_WRITE_CONFIRM_WITHOUT_INIT: Byte = 0x03

    /** TX → RX blood-glucose data (one or more 6-byte records). */
    const val CMD_NOTIFY_BG_DATA: Byte = 0x04

    /** TX → RX "BG data acknowledged, session continuing" marker. Observed
     *  after every successful 0x04 ack round-trip (21 occurrences in the
     *  official HCI log). Silent — no response required from us. */
    const val CMD_NOTIFY_BG_COMPLETE: Byte = 0x05

    /** TX → RX transmitter just started work (reset / fresh session). */
    const val CMD_NOTIFY_BEGIN_WORK: Byte = 0x06

    /** RX → TX acknowledge initialization phase. */
    const val CMD_WRITE_CONFIRM_WITH_INIT: Byte = 0x08

    /** RX → TX transmitter reset. */
    const val CMD_WRITE_CONFIRM_RESET: Byte = 0x11

    // ---- BG record layout (inside payload of 0x04 packets) ----
    //
    // Each BG record is 6 bytes:
    //   byte 0  : raw current low  byte (u16 LE with byte 1)
    //   byte 1  : raw current high byte
    //   byte 2  : processed/temperature low  byte (u16 LE with byte 3)
    //   byte 3  : processed/temperature high byte
    //   byte 4  : battery (unit: %)
    //   byte 5  : reserved / padding
    //
    // Record count = LEN / 6.

    const val BG_RECORD_SIZE = 6
    const val BG_OFFSET_CURRENT_LO = 0
    const val BG_OFFSET_CURRENT_HI = 1
    const val BG_OFFSET_PROC_LO = 2
    const val BG_OFFSET_PROC_HI = 3
    const val BG_OFFSET_BATTERY = 4

    /** Low-battery warning threshold (percent). */
    const val BATTERY_WARN_PERCENT = 30

    // ---- Algorithm defaults / limits ----

    /** Lowest glucose (mmol/L) kept after offset subtraction; matches app. */
    const val ALGO_MMOL_FLOOR = 3.0

    /** Final output clamp (mg/dL × 10 units). App limits to [17, 400] mg/dL. */
    const val ALGO_MGDL_MIN_TIMES10 = 17.0
    const val ALGO_MGDL_MAX_TIMES10 = 400.0

    /** Warmup guard trigger: current >= 19 uA raw counts. */
    const val ALGO_WARMUP_CURRENT_THRESHOLD = 19.0

    /** Default multiplier from SystemInformation.GuardianFriendsID. */
    const val ALGO_DEFAULT_MULTIPLIER = 1.1

    /** Default packages-per-session from SystemInformation.AndroidVersion. */
    const val ALGO_DEFAULT_PACKAGES = 720

    /** Default server-derived deviation if no server response is cached. */
    const val SERVER_DEFAULT_DEVIATION = 0

    /** Default server-derived transmitter10 flag. */
    const val SERVER_DEFAULT_TRANSMITTER10 = 0

    /** Default server-derived protocol type (1 = Protocol01, 2 = Protocol02). */
    const val SERVER_DEFAULT_PROTOCOL_TYPE = 1

    // ---- Lifetime / cadence ----

    /** Sensor rated lifetime in days. */
    const val DEFAULT_RATED_LIFETIME_DAYS = 14

    /** Reading cadence — the transmitter emits ~1 record per minute. */
    const val DEFAULT_READING_INTERVAL_MINUTES = 1

    /** Warmup window before reliable values. */
    const val DEFAULT_WARMUP_MINUTES = 60

    // ---- Device name patterns ----

    /**
     * Advertised device names we treat as MQ/Glutec.
     *
     * The app scans with the generic BLE scanner then disambiguates by NUS service
     * UUID. We keep a small list of known prefixes to support the sensor-picker
     * UI before the GATT service discovery finishes.
     */
    val KNOWN_PREFIXES = arrayOf("Glutec", "GLUTEC", "MQ", "RU-")

    @JvmStatic
    fun isMqDevice(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (KNOWN_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }) return true
        return isLikelyPersistedSensorName(trimmed)
    }

    @JvmStatic
    fun isLikelyPersistedSensorName(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (isProvisionalSensorId(trimmed)) return true
        return Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE).matches(trimmed) ||
            Regex("^[0-9A-F]{12,16}$", RegexOption.IGNORE_CASE).matches(trimmed)
    }

    @JvmStatic
    fun isProvisionalSensorId(name: String?): Boolean =
        name?.trim()?.startsWith(PROVISIONAL_SENSOR_PREFIX, ignoreCase = true) == true

    @JvmStatic
    fun canonicalSensorId(sensorId: String?): String {
        val trimmed = sensorId?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        // BLE MAC address — collapse separators and uppercase.
        if (Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE).matches(trimmed)) {
            return trimmed.uppercase(Locale.US).replace(":", "")
        }
        // Already a canonical hex id.
        if (Regex("^[0-9A-F]{12,16}$", RegexOption.IGNORE_CASE).matches(trimmed)) {
            return trimmed.uppercase(Locale.US).take(MAX_NATIVE_SENSOR_ID_CHARS)
        }
        return trimmed
    }

    @JvmStatic
    fun deriveInitialSensorId(
        deviceName: String?,
        address: String?,
        qrCodeContent: String? = null,
    ): String {
        qrCodeContent?.trim().orEmpty().takeIf { it.isNotEmpty() }?.let { qr ->
            val hex = Regex("[0-9A-F]+", RegexOption.IGNORE_CASE)
                .findAll(qr.uppercase(Locale.US))
                .joinToString("") { it.value }
            if (hex.length in 12..MAX_NATIVE_SENSOR_ID_CHARS) return hex
        }
        val addressCanonical = canonicalSensorId(address)
        if (addressCanonical.isNotEmpty() &&
            Regex("^[0-9A-F]{12,16}$").matches(addressCanonical)
        ) {
            return addressCanonical
        }
        val fallback = deviceName?.trim().orEmpty()
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() }
            .take(MAX_NATIVE_SENSOR_ID_CHARS - PROVISIONAL_SENSOR_PREFIX.length)
            .ifEmpty { "PENDING" }
        return PROVISIONAL_SENSOR_PREFIX + fallback
    }

    @JvmStatic
    fun matchesCanonicalOrKnownNativeAlias(a: String?, b: String?): Boolean {
        val ca = canonicalSensorId(a)
        val cb = canonicalSensorId(b)
        if (ca.isEmpty() || cb.isEmpty()) return false
        return ca.equals(cb, ignoreCase = true)
    }

    // ---- SharedPreferences keys ----

    const val PREF_SENSORS_KEY = "mq_sensors"
    const val PREF_PROTOCOL_TYPE_PREFIX = "mq_protocol_type_"
    const val PREF_DEVIATION_PREFIX = "mq_deviation_"
    const val PREF_TRANSMITTER10_PREFIX = "mq_transmitter10_"
    const val PREF_WARMUP_STARTED_AT_PREFIX = "mq_warmup_started_at_"
    const val PREF_SENSOR_START_AT_PREFIX = "mq_sensor_start_at_"
    const val PREF_K_VALUE_PREFIX = "mq_k_value_"
    const val PREF_B_VALUE_PREFIX = "mq_b_value_"
    const val PREF_MULTIPLIER_PREFIX = "mq_multiplier_"
    const val PREF_PACKAGES_PREFIX = "mq_packages_"
    const val PREF_QR_CONTENT_PREFIX = "mq_qr_content_"
}
