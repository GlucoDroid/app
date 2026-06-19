// OttaiCloudClient.kt — Ottai cloud bootstrap (login + validate + bind).
//
// Presents as the watch app (com.ottai.tag.watch) because that protocol is fully
// recovered: header set + MD5 signature seed from NetManager (1.1.0 decompile).
// The account/token is app-agnostic, so a phone-number SMS login works here.
//
// Geoblock: every request carries forwarded-IP headers set to a China IP so the
// server's IP geolocation returns chinaFlag=1 (no VPN, no official app).
//
// SECURITY: the signature SEED and all returned secrets (accessToken,
// glucoseSecretKey, keyA, method, coefficient) are credentials/IP. Never log them.
// All calls are blocking — invoke on a background thread.

package tk.glucodata.drivers.ottai

import android.content.Context
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import tk.glucodata.Log

object OttaiCloudClient {

    private const val TAG = OttaiConstants.TAG

    // Recovered MD5 signature seed (NetManager.f1540b). Reviewed driver code only.
    private const val SEED = "dy7234hbnrnfh7q89eru8ybfn899"
    private const val APP_NAME = "ottai-watch"
    private const val PKG = "com.ottai.tag.watch"
    private const val TIMEOUT_MS = 30_000

    /** Last non-secret failure reason (HTTP + business code/message), for the UI. */
    @Volatile
    var lastError: String = ""
        private set

    data class LoginResult(val userId: String, val accessToken: String, val glucoseSecretKey: String) {
        val ok: Boolean get() = accessToken.isNotBlank() && glucoseSecretKey.isNotBlank()
    }

    /** China mobile numbers are 11 digits; phoneCode "86" carries the country code. */
    private fun normalizePhone(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.length == 13 && d.startsWith("86")) d = d.substring(2)
        if (d.length == 14 && d.startsWith("0086")) d = d.substring(4)
        return d
    }

    data class DeviceResp(
        val mac: String,
        val keyA: String,
        val method: String,
        val coefficient: String,
        val produceTime: Long,
        val methodUpdateTime: Long,
        val coeffUpdateTime: Long,
        val activeTime: Long,
        val activeExpireTime: Long,  // maxActive duration (ms) -> BLE p.D
        val preheatPeriodTime: Long,
        val retainTime: Long,        // destruction value (ms) -> BLE p.E; 0 => server default
        val deviceVersion: String,
        val deviceId: Int,
    )

    // ---- signature / headers ----

    private fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /** MD5("ottai-watch" + "ottai-watch:a:"+deviceId + ts + args.join("") + SEED). */
    private fun sign(deviceId: String, ts: Long, vararg args: String): String =
        md5Hex(APP_NAME + "$APP_NAME:a:$deviceId" + ts + args.joinToString("") + SEED)

    private fun headers(ctx: Context, ts: Long): MutableMap<String, String> {
        val deviceId = OttaiRegistry.loadOrCreateDeviceId(ctx)
        val token = OttaiRegistry.loadAccessToken(ctx)
        val offsetSec = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
        val h = mutableMapOf(
            "appName" to APP_NAME,
            "versionName" to "1.1.0",
            "versionCode" to "244301",
            "packageName" to PKG,
            "ua" to "Android_Watch_Ottai_Arc",
            "timezone" to offsetSec.toString(),
            "timeZoneName" to TimeZone.getDefault().id,
            "language" to Locale.getDefault().language,
            "traceId" to "trace_testtest",
            "timestamp" to ts.toString(),
            "country" to "zh_CN",
            "deviceId" to "$APP_NAME:a:$deviceId",
            // geoblock bypass — CN source IP via forwarded-for headers
            "X-Forwarded-For" to OttaiConstants.CN_FORWARD_IP,
            "X-Real-IP" to OttaiConstants.CN_FORWARD_IP,
            "CF-Connecting-IP" to OttaiConstants.CN_FORWARD_IP,
            "True-Client-IP" to OttaiConstants.CN_FORWARD_IP,
        )
        if (token.isNotBlank()) h["Authorization"] = token
        return h
    }

    private fun now(): Long = System.currentTimeMillis()

    // ---- API ----

    /** Backend host for the signed-in account (CN api.ottai.com vs global seas.ottai.com). */
    private fun base(ctx: Context): String = OttaiRegistry.loadApiBase(ctx)

    /** GET /user/apiToken — sig over (ts). Returns the apiToken or null. */
    fun getApiToken(ctx: Context, apiBase: String = base(ctx)): String? {
        val ts = now()
        val deviceId = OttaiRegistry.loadOrCreateDeviceId(ctx)
        val sig = sign(deviceId, ts)
        val resp = httpGet(apiBase + OttaiConstants.EP_API_TOKEN, mapOf("signature" to sig), headers(ctx, ts))
        return resp?.optStringDeep("data")
    }

    /** POST /user/smsCode — needs apiToken; sig over (phone, apiToken). Returns requestId. */
    fun requestSmsCode(ctx: Context, phone: String): String? {
        val apiToken = getApiToken(ctx, OttaiConstants.API_BASE) ?: run { lastError = "apiToken failed"; Log.w(TAG, "apiToken failed"); return null }
        val ts = now()
        val deviceId = OttaiRegistry.loadOrCreateDeviceId(ctx)
        val ph = normalizePhone(phone)
        val body = JSONObject().apply {
            put("phoneCode", "86")
            put("phone", ph)
            put("apiToken", apiToken)
            // smsType=1 (login). REQUIRED: without it the server still sends the SMS and
            // returns a requestId, but provisions the code under the wrong type, so the
            // subsequent smsLogin always fails with Sms_CodeInvalid. Not part of the signature.
            put("smsType", 1)
            put("signature", sign(deviceId, ts, ph, apiToken))
        }
        val resp = httpPostJson(OttaiConstants.API_BASE + OttaiConstants.EP_SMS_CODE, body.toString(), headers(ctx, ts))
        return resp?.optStringDeep("data")
    }

    /** POST /user/smsLogin — sig over (requestId, phone, validCode). Persists creds. */
    fun smsLogin(ctx: Context, phone: String, code: String, requestId: String): LoginResult? {
        val ts = now()
        val deviceId = OttaiRegistry.loadOrCreateDeviceId(ctx)
        val ph = normalizePhone(phone)
        val body = JSONObject().apply {
            put("phoneCode", "86")
            put("phone", ph)
            put("validCode", code)
            put("requestId", requestId)
            put("signature", sign(deviceId, ts, requestId, ph, code))
        }
        val resp = httpPostJson(OttaiConstants.API_BASE + OttaiConstants.EP_SMS_LOGIN, body.toString(), headers(ctx, ts)) ?: return null
        val data = resp.optJSONObject("data") ?: resp.optJSONObject("result") ?: return null
        val result = LoginResult(
            userId = data.optString("userId").orEmptyIfNull(),
            accessToken = data.optString("accessToken").orEmptyIfNull(),
            glucoseSecretKey = data.optString("glucoseSecretKey").orEmptyIfNull(),
        )
        if (result.ok) {
            OttaiRegistry.saveApiBase(ctx, OttaiConstants.API_BASE)  // this account lives on the CN backend
            OttaiRegistry.saveAccessToken(ctx, result.accessToken)
            OttaiRegistry.saveGlucoseSecretKey(ctx, result.glucoseSecretKey)
            OttaiRegistry.saveUserId(ctx, result.userId)
        }
        return result
    }

    /** GET /device/validateDeviceByMacV2 — sig over (mac). Read-only, no sensor change. */
    /**
     * Global-app login: account (email or phone) + password, no SMS. POSTs the confirmed
     * `/user/accountLogin` (it needs an apiToken in the body, same as smsCode). The exact
     * signature arg-order isn't capturable from the Flutter global app, so a few orderings
     * are tried with a fresh apiToken each (a wrong signature is rejected pre-auth, so it
     * isn't a failed-login attempt). Persists creds on success; see [lastError] otherwise.
     */
    fun passwordLogin(
        ctx: Context,
        account: String,
        password: String,
        base: String = OttaiConstants.API_BASE_GLOBAL,
    ): LoginResult? {
        val acct = account.trim()
        if (acct.isBlank() || password.isBlank()) { lastError = "account/password required"; return null }
        // Username + password login on a GLOBAL backend (seas.ottai.com / ru.syai.com) — same
        // API + signature scheme, different host. The server keys on the account USERNAME, which
        // is a server-assigned RANDOM string (verified: NOT derived from the email), so the email
        // cannot be used here — email sign-in goes through the web API (see mailLogin). Password
        // is PLAINTEXT; sig arg-order = sign(apiToken, account, password). SINGLE attempt only —
        // a wrong password is a real failed-login attempt, so do NOT retry variants (lockout).
        val apiToken = getApiToken(ctx, base) ?: run { lastError = "apiToken failed"; return null }
        val ts = now()
        val deviceId = OttaiRegistry.loadOrCreateDeviceId(ctx)
        val body = JSONObject().apply {
            put("account", acct)
            put("username", acct)
            put("userName", acct)
            put("password", password)
            put("apiToken", apiToken)
            put("signature", sign(deviceId, ts, apiToken, acct, password))
        }
        val resp = httpPostJson(base + OttaiConstants.EP_ACCOUNT_LOGIN, body.toString(), headers(ctx, ts)) ?: return null
        val data = resp.optJSONObject("data") ?: resp.optJSONObject("result") ?: return null
        val result = LoginResult(
            userId = data.optString("userId").orEmptyIfNull(),
            accessToken = data.optString("accessToken").orEmptyIfNull(),
            glucoseSecretKey = data.optString("glucoseSecretKey").orEmptyIfNull(),
        )
        if (result.ok) {
            OttaiRegistry.saveApiBase(ctx, base)  // subsequent validate/list/bind use this backend
            OttaiRegistry.saveAccessToken(ctx, result.accessToken)
            OttaiRegistry.saveGlucoseSecretKey(ctx, result.glucoseSecretKey)
            OttaiRegistry.saveUserId(ctx, result.userId)
            Log.i(TAG, "passwordLogin ok")
            return result
        }
        return null
    }

    /** POST /user/logout (best-effort) and clear all locally-stored account credentials. */
    fun logout(ctx: Context) {
        runCatching {
            val ts = now()
            httpPostJson(base(ctx) + OttaiConstants.EP_LOGOUT, "{}", headers(ctx, ts))
        }
        OttaiRegistry.saveAccessToken(ctx, null)
        OttaiRegistry.saveGlucoseSecretKey(ctx, null)
        OttaiRegistry.saveUserId(ctx, null)
        OttaiRegistry.saveApiBase(ctx, OttaiConstants.API_BASE)  // reset to CN default
    }

    fun validateByMac(ctx: Context, mac: String): DeviceResp? {
        val canonical = OttaiConstants.canonicalSensorId(mac)
        val ts = now()
        val deviceId = OttaiRegistry.loadOrCreateDeviceId(ctx)
        val sig = sign(deviceId, ts, canonical)
        val resp = httpGet(
            base(ctx) + OttaiConstants.EP_VALIDATE_BY_MAC,
            mapOf("mac" to canonical, "signature" to sig),
            headers(ctx, ts),
        ) ?: return null
        return parseDeviceResp(resp)
    }

    /** POST /deviceBind/composite/bind — unsigned; activates cloud-side, returns keyA. */
    fun bind(ctx: Context, mac: String, deviceVersion: String, userId: String): DeviceResp? {
        val canonical = OttaiConstants.canonicalSensorId(mac)
        val ts = now()
        val body = JSONObject().apply {
            put("mac", canonical)
            put("deviceType", "cgm")
            put("deviceVersion", deviceVersion)
            put("activeTime", ts)
            put("userId", userId)
            put("newBindType", 2)
        }
        val resp = httpPostJson(base(ctx) + OttaiConstants.EP_BIND, body.toString(), headers(ctx, ts)) ?: return null
        return parseDeviceResp(resp)
    }

    /** GET /deviceBind/getBindDevice — current account-bound sensor, no signature. */
    fun getBindDevice(ctx: Context): DeviceResp? {
        val ts = now()
        val resp = httpGet(
            base(ctx) + OttaiConstants.EP_GET_BIND_DEVICE,
            emptyMap(),
            headers(ctx, ts),
        ) ?: return null
        return parseDeviceResp(resp)
    }

    /** One row of GET /deviceBind/list — a sensor the account has bound (now or before). */
    data class DeviceSummary(
        val mac: String,
        val serialNo: String,
        val deviceType: String,
        val bindTime: Long,
        val unbindTime: Long,
    ) {
        /** Still bound (vs. a previously-used sensor that was unbound). */
        val isActive: Boolean get() = unbindTime <= 0L
    }

    /**
     * GET /deviceBind/list — the account's bound + previously-bound sensors, newest
     * first. Lets the user pick a sensor instead of typing/scanning a MAC. Returns an
     * empty list on error (see [lastError]).
     */
    fun listDevices(ctx: Context, pageSize: Int = 80, pageNumber: Int = 1): List<DeviceSummary> {
        val ts = now()
        val resp = httpGet(
            base(ctx) + OttaiConstants.EP_DEVICE_LIST,
            mapOf("pageSize" to pageSize.toString(), "pageNumber" to pageNumber.toString()),
            headers(ctx, ts),
        ) ?: return emptyList()
        val data = resp.optJSONObject("data") ?: resp.optJSONObject("result") ?: return emptyList()
        val items = data.optJSONArray("items")
            ?: data.optJSONArray("list")
            ?: data.optJSONArray("records")
            ?: return emptyList()
        val out = ArrayList<DeviceSummary>(items.length())
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val mac = o.optString("mac").orEmptyIfNull()
            if (mac.isBlank()) continue
            out.add(
                DeviceSummary(
                    mac = mac,
                    serialNo = o.optString("serialNo").orEmptyIfNull(),
                    deviceType = o.optString("deviceType").orEmptyIfNull(),
                    bindTime = o.optLongLoose("bindTime"),
                    unbindTime = o.optLongLoose("unbindTime"),
                ),
            )
        }
        return out
    }

    private fun parseDeviceResp(resp: JSONObject): DeviceResp? {
        val data = resp.optJSONObject("data") ?: resp.optJSONObject("result") ?: return null
        val vo = data.optJSONObject("cgmDeviceRespVO") ?: data
        val keyA = vo.optString("keyA").orEmptyIfNull()
        if (keyA.isBlank()) return null
        return DeviceResp(
            mac = vo.optString("mac").orEmptyIfNull(),
            keyA = keyA,
            method = vo.optString("method").orEmptyIfNull(),
            coefficient = vo.optString("coefficient").orEmptyIfNull(),
            produceTime = vo.optLongLoose("produceTime"),
            methodUpdateTime = vo.optLongLoose("methodUpdateTime"),
            coeffUpdateTime = vo.optLongLoose("coeffUpdateTime"),
            activeTime = vo.optLongLoose("activeTime"),
            activeExpireTime = vo.optLongLoose("activeExpireTime"),
            preheatPeriodTime = vo.optLongLoose("preheatPeriodTime"),
            retainTime = vo.optLongLoose("retainTime"),
            deviceVersion = vo.optString("deviceVersion").orEmptyIfNull(),
            deviceId = vo.optInt("id", 0),
        )
    }

    /**
     * Decrypt the cloud-returned materials with the account glucoseSecretKey.
     * Returns null if keyA fails to decrypt to the expected 192-hex auth group.
     */
    fun toMaterials(ctx: Context, mac: String, resp: DeviceResp): OttaiRegistry.DeviceMaterials? {
        val secret = OttaiRegistry.loadGlucoseSecretKey(ctx)
        if (secret.isBlank()) return null
        val canonical = OttaiConstants.canonicalSensorId(mac)
        val keyAPlain = OttaiCrypto.decryptKeyA(resp.keyA, secret, resp.produceTime.toString(), canonical)
            ?: return null
        if (OttaiCrypto.parseAuthKeys(keyAPlain) == null) return null
        val methodPlain = if (resp.method.isNotBlank())
            OttaiCrypto.decryptMethod(resp.method, secret, resp.methodUpdateTime.toString(), canonical).orEmpty() else ""
        val coeffPlain = if (resp.coefficient.isNotBlank())
            OttaiCrypto.decryptCoefficient(resp.coefficient, secret, resp.coeffUpdateTime.toString(), canonical).orEmpty() else ""
        return OttaiRegistry.DeviceMaterials(
            keyAHex = keyAPlain,
            method = methodPlain,
            coefficient = coeffPlain,
            activeTimeMs = resp.activeTime,
            activeExpireTimeMs = resp.activeExpireTime,
            retainTimeMs = resp.retainTime,
            preheatPeriodMs = resp.preheatPeriodTime,
            deviceVersion = resp.deviceVersion,
            deviceId = resp.deviceId,
        )
    }

    // ---- HTTP ----

    // ---- WEB API (www.ottai.com/api/cgm/web) — true email login + in-app registration ----
    // The website's account API, fully recovered from its JS. Different profile from the watch
    // API: appName "ottai-seas", signature = md5(parts + SEED) (no deviceId-prefix), and the
    // sensitive fields are AES-256-ECB-encrypted into "encryptInfo". The GuestToken header
    // signature is NOT required (verified). We use this because the account's login username is
    // a server-assigned random string (e.g. "test234123154") — the EMAIL is the only identifier
    // a user knows — and so users can register without installing the vendor app.

    private const val WEB_BASE = "https://www.ottai.com/api/cgm/web"
    private const val WEB_APP = "ottai-seas"
    private const val WEB_DEVICE_ID = "8"  // sent as the deviceId header + used in the mail/login body sig (any stable value)
    private const val WEB_AES_KEY = "miH5ngQ7z4NZU3JgZFq87Gg6v1Y7YJm9"  // web __ENV NEXT_PUBLIC_AES_KEY (AES-256)
    private const val WEB_FINGERPRINT = "0123456789abcdef0123456789abcdef01234567"  // opaque client fp (not validated)

    private fun webSign(vararg parts: String): String = md5Hex(parts.joinToString("") + SEED)

    private fun webHeaders(ts: Long): Map<String, String> = mapOf(
        "appName" to WEB_APP,
        "timestamp" to ts.toString(),
        "deviceId" to WEB_DEVICE_ID,
        "deviceFingerprinting" to WEB_FINGERPRINT,
        "region" to "Europe",
        "ua" to "web",
        "versionCode" to "253201",
        "traceId" to "trace_$ts",
        "language" to "en",
        "timezone" to "0",
        "X-Canary-Mode" to "OFF",
        "country" to "RU",
    )

    /** AES-256-ECB/PKCS5 of the plaintext JSON, base64 — the web "encryptInfo" field. */
    private fun webEncrypt(plainJson: String): String {
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(WEB_AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
        )
        return android.util.Base64.encodeToString(
            cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP,
        )
    }

    private fun getWebApiToken(): String? {
        val ts = now()
        val resp = httpGet(
            "$WEB_BASE/user/apiToken",
            mapOf("signature" to webSign(WEB_APP, ts.toString())),
            webHeaders(ts),
        )
        return resp?.optStringDeep("data")
    }

    /** Email login via the website API. Body sig = md5(appName+ts+deviceId+apiToken+email+password+SEED). */
    fun mailLogin(ctx: Context, email: String, password: String): LoginResult? {
        val em = email.trim()
        if (em.isBlank() || password.isBlank()) { lastError = "email/password required"; return null }
        val apiToken = getWebApiToken() ?: run { lastError = "apiToken failed"; return null }
        val ts = now()
        val encInfo = webEncrypt(JSONObject().put("username", em).put("password", password).toString())
        val body = JSONObject().apply {
            put("uuid", java.util.UUID.randomUUID().toString().replace("-", ""))
            put("encryptInfo", encInfo)
            put("apiToken", apiToken)
            put("source", 5)
            put("signature", webSign(WEB_APP, ts.toString(), WEB_DEVICE_ID, apiToken, em, password))
        }
        val resp = httpPostJson("$WEB_BASE/user/mail/login", body.toString(), webHeaders(ts)) ?: return null
        return persistWebLogin(ctx, resp)
    }

    /** POST /user/mail/sendMail — emails a verification code, returns the requestId. type: SIGN_UP/LOGIN/RESET_PASSWORD. */
    fun sendMail(email: String, type: String = "SIGN_UP"): String? {
        val em = email.trim()
        val apiToken = getWebApiToken() ?: run { lastError = "apiToken failed"; return null }
        val ts = now()
        val body = JSONObject().apply {
            put("type", type)
            put("isSend", 1)
            put("email", em)
            put("apiToken", apiToken)
            put("signature", webSign(WEB_APP, ts.toString(), em, apiToken))
        }
        val resp = httpPostJson("$WEB_BASE/user/mail/sendMail", body.toString(), webHeaders(ts)) ?: return null
        // Success = code "OK"; the requestId for signUp is data.key. Anything else
        // (e.g. USER_REGISTERED for an existing email) -> null, with lastError already set.
        if (!resp.optString("code").equals("OK", ignoreCase = true)) return null
        return resp.optJSONObject("data")?.optString("key").orEmptyIfNull().takeIf { it.isNotBlank() }
    }

    /** POST /user/mail/signUp — register (email + emailed code + password + display name). Persists creds. */
    fun signUp(ctx: Context, email: String, password: String, profileName: String, requestId: String, validCode: String): LoginResult? {
        val em = email.trim()
        val apiToken = getWebApiToken() ?: run { lastError = "apiToken failed"; return null }
        val ts = now()
        val encInfo = webEncrypt(
            JSONObject().put("email", em).put("password", password).put("profileName", profileName).toString(),
        )
        val body = JSONObject().apply {
            put("apiToken", apiToken)
            put("encryptInfo", encInfo)
            put("requestId", requestId)
            put("validCode", validCode)
            put("recommendFlag", false)
            put("country", "RU")
            put("language", "en")
            put("signature", webSign(WEB_APP, ts.toString(), requestId, em, validCode))
        }
        val resp = httpPostJson("$WEB_BASE/user/mail/signUp", body.toString(), webHeaders(ts)) ?: return null
        return persistWebLogin(ctx, resp)
    }

    /** Store accessToken/userId/glucoseSecretKey from a web login/signup; CGM ops use the seas mobile API. */
    private fun persistWebLogin(ctx: Context, resp: JSONObject): LoginResult? {
        val data = resp.optJSONObject("data") ?: resp.optJSONObject("result") ?: return null
        val result = LoginResult(
            userId = data.optString("userId").orEmptyIfNull(),
            accessToken = data.optString("accessToken").orEmptyIfNull(),
            glucoseSecretKey = data.optString("glucoseSecretKey").orEmptyIfNull(),
        )
        if (result.accessToken.isNotBlank()) {
            OttaiRegistry.saveApiBase(ctx, OttaiConstants.API_BASE_GLOBAL)
            OttaiRegistry.saveAccessToken(ctx, result.accessToken)
            if (result.glucoseSecretKey.isNotBlank()) OttaiRegistry.saveGlucoseSecretKey(ctx, result.glucoseSecretKey)
            OttaiRegistry.saveUserId(ctx, result.userId)
            Log.i(TAG, "web login ok")
        }
        return result
    }

    private fun httpGet(base: String, query: Map<String, String>, headers: Map<String, String>): JSONObject? {
        val qs = query.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
        val url = if (qs.isEmpty()) base else "$base?$qs"
        return request("GET", url, null, headers)
    }

    private fun httpPostJson(url: String, body: String, headers: Map<String, String>): JSONObject? =
        request("POST", url, body, headers + ("Content-Type" to "application/json;charset=UTF-8"))

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    setRequestProperty("Content-Length", bytes.size.toString())
                    outputStream.use { it.write(bytes) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val path = url.substringBefore('?').substringAfterLast("/server")
            val json = if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
            // Capture non-secret business error (code/message) for the UI/logs.
            val bizCode = json?.opt("code")?.toString().orEmpty()
            val bizMsg = (json?.opt("message") ?: json?.opt("msg") ?: json?.opt("detailMessage"))
                ?.toString().orEmpty().takeIf { it != "null" }.orEmpty()
            val bizOk = bizCode.isBlank() || bizCode == "200" || bizCode.equals("OK", ignoreCase = true)
            if (code !in 200..299 || !bizOk) {
                lastError = "http=$code biz=$bizCode ${bizMsg.take(120)}".trim()
                Log.w(TAG, "$path -> $lastError")
            } else {
                lastError = ""
            }
            json
        } catch (t: Throwable) {
            lastError = "network: ${t.message}"
            Log.w(TAG, "request failed ${url.substringBefore('?')}: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun String?.orEmptyIfNull(): String = if (this == null || this == "null") "" else this

    private fun JSONObject.optStringDeep(key: String): String? {
        val direct = optString(key).orEmptyIfNull()
        if (direct.isNotBlank()) return direct
        return null
    }

    private fun JSONObject.optLongLoose(key: String): Long {
        if (!has(key) || isNull(key)) return 0L
        optLong(key, Long.MIN_VALUE).let { if (it != Long.MIN_VALUE) return it }
        return optString(key).orEmptyIfNull().toLongOrNull() ?: 0L
    }
}
