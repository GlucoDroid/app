package tk.glucodata.drivers.mq

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Log

enum class MQVerifyCodeAction(val wireValue: Int) {
    REGISTER(1),
    LOGIN(2),
    RESET_PASSWORD(3),
}

data class MQCloudActionResult(
    val success: Boolean = false,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

data class MQCloudAuthResult(
    val token: String? = null,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
) {
    val success: Boolean get() = !token.isNullOrBlank()
}

data class MQCloudTokenStatus(
    val isValid: Boolean = false,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

data class MQCloudAccountAvailability(
    val exists: Boolean = false,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

data class MQCloudSessionResult(
    val snapshotId: String? = null,
    val success: Boolean = false,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

internal data class MQCloudPostResult(
    val root: JSONObject? = null,
    val failure: MQBootstrapFailure = MQBootstrapFailure.NONE,
    val message: String? = null,
)

object MQCloudClient {
    private const val TAG = MQConstants.TAG
    private const val DEVICE_MODEL = "Android_Phone"
    private const val DEVICE_TYPE = "4"
    private const val DEFAULT_AVATAR = "Filepath"

    private data class AgentInfo(
        val agent: String,
        val imei: String,
    )

    fun signInWithPassword(
        context: Context,
        credentials: MQAuthCredentials,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudAuthResult {
        val agentInfo = buildAgentInfo(context)
        val form = buildString {
            append("account=").append(credentials.account.urlEncode())
            append("&password=").append(credentials.password.urlEncode())
            append("&agent=").append(agentInfo.agent.urlEncode())
            append("&imei=").append(agentInfo.imei.urlEncode())
            append("&model=").append(DEVICE_MODEL.urlEncode())
            append("&type=").append(DEVICE_TYPE.urlEncode())
        }
        val root = postForm(
            url = endpoints.loginWithPasswordUrl,
            form = form,
            authToken = null,
        )
        val token = root.root?.optJSONObject("result")?.optStringOrNull("token")
        if (!token.isNullOrBlank()) {
            Log.i(TAG, "MQ vendor login refreshed token")
            return MQCloudAuthResult(token = token, message = root.message)
        }
        return MQCloudAuthResult(failure = root.failure, message = root.message ?: "MQ login returned no token")
    }

    fun signInWithCode(
        context: Context,
        phone: String,
        captcha: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudAuthResult {
        val agentInfo = buildAgentInfo(context)
        val form = buildString {
            append("phone=").append(phone.urlEncode())
            append("&captcha=").append(captcha.urlEncode())
            append("&agent=").append(agentInfo.agent.urlEncode())
            append("&imei=").append(agentInfo.imei.urlEncode())
            append("&model=").append(DEVICE_MODEL.urlEncode())
            append("&type=").append(DEVICE_TYPE.urlEncode())
        }
        val root = postForm(
            url = endpoints.loginByCodeUrl,
            form = form,
            authToken = null,
        )
        val token = root.root?.optJSONObject("result")?.optStringOrNull("token")
        if (!token.isNullOrBlank()) {
            Log.i(TAG, "MQ vendor SMS login refreshed token")
            return MQCloudAuthResult(token = token, message = root.message)
        }
        return MQCloudAuthResult(failure = root.failure, message = root.message ?: "MQ SMS login returned no token")
    }

    fun requestVerifyCode(
        context: Context,
        phone: String,
        action: MQVerifyCodeAction,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult {
        val form = buildString {
            append("phone=").append(phone.urlEncode())
            append("&type=").append(action.wireValue.toString().urlEncode())
        }
        return postAction(
            url = endpoints.getVerifyCodeUrl,
            form = form,
            authToken = null,
            successMessage = "MQ verification code requested",
        )
    }

    fun registerByCode(
        context: Context,
        phone: String,
        captcha: String,
        password: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudAuthResult {
        val agentInfo = buildAgentInfo(context)
        val form = buildString {
            append("phone=").append(phone.urlEncode())
            append("&captcha=").append(captcha.urlEncode())
            append("&agent=").append(agentInfo.agent.urlEncode())
            append("&imei=").append(agentInfo.imei.urlEncode())
            append("&model=").append(DEVICE_MODEL.urlEncode())
            append("&password=").append(password.urlEncode())
            append("&type=").append(DEVICE_TYPE.urlEncode())
            append("&avatar=").append(DEFAULT_AVATAR.urlEncode())
        }
        val root = postForm(
            url = endpoints.registerByCodeUrl,
            form = form,
            authToken = null,
        )
        val token = root.root?.optJSONObject("result")?.optStringOrNull("token")
        if (!token.isNullOrBlank()) {
            Log.i(TAG, "MQ vendor SMS registration returned token")
            return MQCloudAuthResult(token = token, message = root.message)
        }
        return MQCloudAuthResult(failure = root.failure, message = root.message ?: "MQ registration returned no token")
    }

    fun resetPasswordByCode(
        context: Context,
        phone: String,
        captcha: String,
        newPassword: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult {
        val form = buildString {
            append("phone=").append(phone.urlEncode())
            append("&captcha=").append(captcha.urlEncode())
            append("&password=").append(newPassword.urlEncode())
        }
        return postAction(
            url = endpoints.resetPasswordByCodeUrl,
            form = form,
            authToken = null,
            successMessage = "MQ password reset submitted",
        )
    }

    fun isAccountAvailable(
        context: Context,
        phone: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudAccountAvailability {
        val root = postForm(
            url = endpoints.isAccountExistUrl,
            form = "phone=${phone.urlEncode()}",
            authToken = null,
        )
        if (root.failure != MQBootstrapFailure.NONE && root.root == null) {
            return MQCloudAccountAvailability(failure = root.failure, message = root.message)
        }
        val exists = root.root?.opt("result")?.let { value ->
            when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                else -> value.toString().equals("true", ignoreCase = true) ||
                    value.toString().equals("1")
            }
        } ?: false
        return MQCloudAccountAvailability(exists = exists, message = root.message)
    }

    fun checkToken(
        context: Context,
        authToken: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudTokenStatus {
        val root = postForm(
            url = endpoints.checkTokenUrl,
            form = "token=${authToken.urlEncode()}",
            authToken = null,
        )
        if (root.failure == MQBootstrapFailure.NONE) {
            return MQCloudTokenStatus(isValid = true, message = root.message)
        }
        return MQCloudTokenStatus(isValid = false, failure = root.failure, message = root.message)
    }

    fun logout(
        context: Context,
        phone: String,
        authToken: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult =
        postAction(
            url = endpoints.logoutUrl,
            form = "phone=${phone.urlEncode()}",
            authToken = authToken,
            successMessage = "MQ vendor logout submitted",
        )

    fun startWearSession(
        context: Context,
        authToken: String,
        bleId: String,
        mac: String,
        account: String,
        qrCode: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudSessionResult {
        val root = postForm(
            url = endpoints.dataRecordStartUrl,
            form = buildString {
                append("bleId=").append(bleId.urlEncode())
                append("&mac=").append(mac.urlEncode())
                append("&account=").append(account.urlEncode())
                append("&qrCode=").append(qrCode.urlEncode())
            },
            authToken = authToken,
        )
        val snapshotId = root.root?.optJSONObject("result")?.optStringOrNull("snapshotId")
        return MQCloudSessionResult(
            snapshotId = snapshotId,
            success = !snapshotId.isNullOrBlank(),
            failure = if (!snapshotId.isNullOrBlank()) MQBootstrapFailure.NONE else root.failure,
            message = root.message,
        )
    }

    fun continueWearSession(
        context: Context,
        authToken: String,
        snapshotId: String,
        bleId: String,
        qrCode: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult =
        postAction(
            url = endpoints.dataRecordGoOnUrl,
            form = buildString {
                append("snapshotId=").append(snapshotId.urlEncode())
                append("&bleId=").append(bleId.urlEncode())
                append("&qrCode=").append(qrCode.urlEncode())
            },
            authToken = authToken,
            successMessage = "MQ continue-wear session submitted",
        )

    fun endWearSession(
        context: Context,
        authToken: String,
        snapshotId: String,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult =
        postAction(
            url = endpoints.dataRecordEndUrl,
            form = "snapshotId=${snapshotId.urlEncode()}",
            authToken = authToken,
            successMessage = "MQ end-wear session submitted",
        )

    fun uploadCalibrationEvent(
        context: Context,
        authToken: String,
        snapshotId: String,
        packetIndex: Int,
        bagTimeMs: Long,
        bgValueMmol: Double,
        referenceK: Double,
        referenceB: Double,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult =
        postAction(
            url = endpoints.dataRecordEventUrl,
            form = buildString {
                append("bagIndex=").append(packetIndex.toString().urlEncode())
                append("&bagTime=").append(bagTimeMs.toString().urlEncode())
                append("&eventType=1")
                append("&snapshotId=").append(snapshotId.urlEncode())
                append("&bgValue=").append(bgValueMmol.toString().urlEncode())
                append("&referenceB=").append(referenceB.toString().urlEncode())
                append("&referenceK=").append(referenceK.toString().urlEncode())
            },
            authToken = authToken,
            successMessage = "MQ calibration event uploaded",
        )

    fun uploadCalibrationTime(
        context: Context,
        authToken: String,
        snapshotId: String,
        bagTimeMs: Long,
        packetIndex: Int,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult =
        postAction(
            url = endpoints.dataRecordCalibrationTimeUrl,
            form = buildString {
                append("snapshotId=").append(snapshotId.urlEncode())
                append("&time=").append(bagTimeMs.toString().urlEncode())
                append("&bagIndex=").append(packetIndex.toString().urlEncode())
            },
            authToken = authToken,
            successMessage = "MQ calibration time uploaded",
        )

    fun reportData(
        context: Context,
        authToken: String,
        snapshotId: String,
        calculateDataHex: List<String>,
        endpoints: MQVendorEndpoints = MQConstants.vendorEndpoints(MQRegistry.loadApiBaseUrl(context)),
    ): MQCloudActionResult {
        val payload = JSONObject().apply {
            put("snapshopId", snapshotId)
            put(
                "dataInfoList",
                JSONArray().apply {
                    calculateDataHex
                        .filter { it.isNotBlank() }
                        .forEach { hex ->
                            put(JSONObject().apply { put("calculateData", hex) })
                        }
                },
            )
        }
        val root = postJson(
            url = endpoints.dataRecordReportUrl,
            body = payload.toString(),
            authToken = authToken,
        )
        return MQCloudActionResult(
            success = root.failure == MQBootstrapFailure.NONE,
            failure = root.failure,
            message = root.message,
        )
    }

    internal fun postForm(
        url: String,
        form: String,
        authToken: String?,
    ): MQCloudPostResult {
        var connection: HttpURLConnection? = null
        return try {
            val bytes = form.toByteArray(Charsets.UTF_8)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = MQConstants.VENDOR_TIMEOUT_MS
                readTimeout = MQConstants.VENDOR_TIMEOUT_MS
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Content-Length", bytes.size.toString())
                authToken?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("X-Access-Token", it)
                }
            }
            connection.outputStream.use { it.write(bytes) }
            parseResponse(connection, url)
        } catch (t: Throwable) {
            Log.stack(TAG, "MQ cloud POST $url", t)
            MQCloudPostResult(
                failure = MQBootstrapFailure.NETWORK,
                message = t.message,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun postJson(
        url: String,
        body: String,
        authToken: String?,
    ): MQCloudPostResult {
        var connection: HttpURLConnection? = null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = MQConstants.VENDOR_TIMEOUT_MS
                readTimeout = MQConstants.VENDOR_TIMEOUT_MS
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                setRequestProperty("Content-Length", bytes.size.toString())
                authToken?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("X-Access-Token", it)
                }
            }
            connection.outputStream.use { it.write(bytes) }
            parseResponse(connection, url)
        } catch (t: Throwable) {
            Log.stack(TAG, "MQ cloud POST(JSON) $url", t)
            MQCloudPostResult(
                failure = MQBootstrapFailure.NETWORK,
                message = t.message,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun postAction(
        url: String,
        form: String,
        authToken: String?,
        successMessage: String,
    ): MQCloudActionResult {
        val root = postForm(url = url, form = form, authToken = authToken)
        if (root.failure == MQBootstrapFailure.NONE) {
            Log.i(TAG, successMessage)
        }
        return MQCloudActionResult(
            success = root.failure == MQBootstrapFailure.NONE,
            failure = root.failure,
            message = root.message,
        )
    }

    private fun parseResponse(
        connection: HttpURLConnection,
        url: String,
    ): MQCloudPostResult {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val body = stream?.readUtf8Body().orEmpty()
        if (body.isBlank()) {
            Log.w(TAG, "MQ cloud returned empty body from $url (http=${connection.responseCode})")
            return MQCloudPostResult(
                failure = MQBootstrapFailure.SERVER,
                message = "empty body",
            )
        }
        val root = JSONObject(body)
        val code = root.optString("code").toIntOrNull()
        val message = root.optStringOrNull("message")
        if (connection.responseCode in 200..299 && code == 200) {
            return MQCloudPostResult(root = root, message = message)
        }
        val failure = classifyFailure(connection.responseCode, message)
        Log.w(TAG, "MQ cloud rejected by $url: appCode=$code http=${connection.responseCode} body=$body")
        return MQCloudPostResult(
            root = root,
            failure = failure,
            message = message ?: "http=${connection.responseCode}",
        )
    }

    private fun buildAgentInfo(context: Context): AgentInfo {
        val versionCode = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        val agent = "${Build.BRAND}_${Build.MODEL}_${Build.VERSION.RELEASE}_$versionCode"
        val imei = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.take(15)
            ?.takeIf { it.isNotBlank() }
            ?: "899999999999999"
        return AgentInfo(agent = agent, imei = imei)
    }

    private fun classifyFailure(httpCode: Int, message: String?): MQBootstrapFailure {
        val normalized = message.orEmpty()
        if (httpCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
            httpCode == HttpURLConnection.HTTP_FORBIDDEN ||
            normalized.contains("Token", ignoreCase = true) ||
            normalized.contains("重新登录")
        ) {
            return MQBootstrapFailure.AUTH_EXPIRED
        }
        return MQBootstrapFailure.SERVER
    }

    private fun InputStream.readUtf8Body(): String =
        BufferedReader(InputStreamReader(this)).use { reader ->
            buildString {
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line)
                }
            }
        }

    private fun JSONObject.optStringOrNull(name: String): String? =
        optString(name).takeUnless { it.isBlank() || it == "null" }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "utf-8")
}
