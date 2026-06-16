// JugglucoNG — Ottai (Chinese-market CGM) driver
// OttaiConstants.kt — BLE UUIDs, cloud endpoints/headers, prefs keys, identity.
//
// See AGENTS/ottai-protocol.md + AGENTS/ottai-phase0-confirmed.md. Ottai connects
// directly to the bound MAC (no name scan needed); the CGM service 0x181F is also
// advertised so we can surface it in a scan list.

package tk.glucodata.drivers.ottai

import java.util.Locale
import java.util.UUID

object OttaiConstants {

    const val TAG = "Ottai"
    const val DEFAULT_DISPLAY_NAME = "Ottai CGM"
    const val PROVISIONAL_SENSOR_PREFIX = "OTTAI-"
    const val MAX_NATIVE_SENSOR_ID_CHARS = 16

    // ---- BLE services / characteristics ----

    val SERVICE_DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val CHAR_SOFTWARE_VERSION: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
    val CHAR_CURRENT_TIME: UUID = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
    val CHAR_MAX_ACTIVE_TIME: UUID = UUID.fromString("b8fd9848-0ccd-423f-bd34-2419aa7ea004")
    val CHAR_CGM_INFO_NOTIFY: UUID = UUID.fromString("cb627922-4e79-42e3-b107-a10e816f6caa")

    val SERVICE_CGM: UUID = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
    val CHAR_GLUCOSE_LIVE: UUID = UUID.fromString("00002aa7-0000-1000-8000-00805f9b34fb")
    val CHAR_GLUCOSE_HISTORY: UUID = UUID.fromString("69e4f45f-a180-422c-83c0-324146402112")
    val CHAR_COMMAND: UUID = UUID.fromString("d78d0706-c775-448d-8a78-01215e7c2e11")

    val SERVICE_AUTH: UUID = UUID.fromString("e06e1d43-1319-4ebf-94b0-5b0e5313b1f4")
    val CHAR_AUTH_DEVICE_PARAM: UUID = UUID.fromString("86805092-92b5-4d8c-9d73-0785ff6f9147")
    val CHAR_AUTH_APP_PARAM: UUID = UUID.fromString("1756ef6e-884b-4eb0-b646-f04ab18408f9")
    val CHAR_AUTH_SIGN: UUID = UUID.fromString("785022c6-08c0-48af-ad17-684bb889aa83")

    /** DESTRUCTIVE — never touched by this driver. */
    val SERVICE_DESTRUCTIVE: UUID = UUID.fromString("84c5b711-655a-460d-89ca-337dbc981857")
    val CHAR_DESTRUCTIVE: UUID = UUID.fromString("6aa799b6-b374-4148-8f36-6d440c0ec203")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Activate command base byte (gets zero-padded + AES-encrypted with session key). */
    const val ACTIVATE_CMD: Byte = 0x03

    // ---- Cloud ----

    const val API_BASE = "https://api.ottai.com"
    const val PREFIX = "/cgm/app/server"

    const val EP_API_TOKEN = "$PREFIX/user/apiToken"
    const val EP_SMS_CODE = "$PREFIX/user/smsCode"
    const val EP_SMS_LOGIN = "$PREFIX/user/smsLogin"
    const val EP_GET_USER = "$PREFIX/user/getUser"
    const val EP_VALIDATE_BY_MAC = "$PREFIX/device/validateDeviceByMacV2"
    const val EP_BIND = "$PREFIX/deviceBind/composite/bind"
    const val EP_UNBIND = "$PREFIX/deviceBind/unBindDevice"
    const val EP_GET_BIND_DEVICE = "$PREFIX/deviceBind/getBindDevice"
    const val EP_DOWNLOAD_GLUCOSE = "$PREFIX/search/downloadGlucose"

    /**
     * Default phone-app headers (matches the working bind helper). The four
     * forwarded-IP headers spoof a China source IP so the server's geolocation
     * returns chinaFlag=1 and the smuggling notice never triggers — no VPN.
     */
    const val HEADER_APP_NAME = "ottai"
    const val HEADER_PACKAGE = "com.ottai.tag"
    const val HEADER_APP_TYPE = "ottai_main"
    const val CN_FORWARD_IP = "114.114.114.114"

    // ---- Identity / matching ----

    private val MAC_COLON = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$", RegexOption.IGNORE_CASE)
    private val MAC_PLAIN = Regex("^[0-9A-F]{12}$", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun isProvisionalSensorId(name: String?): Boolean =
        name?.trim()?.startsWith(PROVISIONAL_SENSOR_PREFIX, ignoreCase = true) == true

    /** Canonical MAC: 12 uppercase hex, no separators. */
    @JvmStatic
    fun canonicalSensorId(sensorId: String?): String {
        val trimmed = sensorId?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        if (MAC_COLON.matches(trimmed)) return trimmed.uppercase(Locale.US).replace(":", "")
        if (MAC_PLAIN.matches(trimmed)) return trimmed.uppercase(Locale.US)
        return trimmed
    }

    /** Add colons to a 12-hex MAC for BluetoothAdapter.getRemoteDevice(). */
    @JvmStatic
    fun macWithColons(canonical: String): String {
        val c = canonicalSensorId(canonical)
        if (!MAC_PLAIN.matches(c)) return c
        return c.chunked(2).joinToString(":")
    }

    @JvmStatic
    fun looksLikeMac(s: String?): Boolean {
        val t = s?.trim().orEmpty()
        return MAC_COLON.matches(t) || MAC_PLAIN.matches(t)
    }

    @JvmStatic
    fun matchesCanonicalOrKnownNativeAlias(a: String?, b: String?): Boolean {
        val ca = canonicalSensorId(a)
        val cb = canonicalSensorId(b)
        if (ca.isEmpty() || cb.isEmpty()) return false
        return ca.equals(cb, ignoreCase = true)
    }

    /** Extract a 12-hex MAC from arbitrary QR text, if present. */
    @JvmStatic
    fun extractMacFromQr(qr: String?): String? {
        val raw = qr?.uppercase(Locale.US) ?: return null
        Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}").find(raw)?.let { return canonicalSensorId(it.value) }
        Regex("[0-9A-F]{12}").find(raw)?.let { return it.value }
        return null
    }

    // ---- Lifetime / cadence ----

    /** Sensor rated lifetime (typical Ottai). */
    const val DEFAULT_RATED_LIFETIME_DAYS = 15

    /** Reading cadence (minutes). */
    const val DEFAULT_READING_INTERVAL_MINUTES = 1

    /** Default warmup seconds (device_manage_warmup_duration). */
    const val DEFAULT_WARMUP_SECONDS = 3200

    // ---- SharedPreferences keys ----

    const val PREF_SENSORS_KEY = "ottai_sensors"
    const val PREF_ACCESS_TOKEN = "ottai_access_token"
    const val PREF_GLUCOSE_SECRET = "ottai_glucose_secret_key"
    const val PREF_USER_ID = "ottai_user_id"
    const val PREF_KEYA_PREFIX = "ottai_keya_"            // decrypted 6x16 hex (192)
    const val PREF_METHOD_PREFIX = "ottai_method_"        // decrypted method text
    const val PREF_COEFF_PREFIX = "ottai_coeff_"          // decrypted coefficient CSV
    const val PREF_ACTIVE_TIME_PREFIX = "ottai_active_time_"
    const val PREF_DEVICE_VERSION_PREFIX = "ottai_device_version_"
    const val PREF_LAST_DATA_NO_PREFIX = "ottai_last_datano_"
    const val PREF_DEVICE_ID_PREFIX = "ottai_device_id_"
    const val PREF_SELF_DEVICE_ID = "ottai_self_device_id"
}
