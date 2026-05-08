package tk.glucodata

import android.content.Context
import androidx.annotation.Keep
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Keep
object OutboundApi {
    const val TEST_QUEUED = 0
    const val TEST_NOT_CONFIGURED = 1
    const val TEST_NO_CURRENT_READING = 2

    private const val WORK_TAG = "outbound_api_glucose"
    private const val MGDL_PER_MMOLL = 18.0182f

    private const val IN_EVENT_ID = "event_id"
    private const val IN_SENSOR_ID = "sensor_id"
    private const val IN_PRIMARY_TEXT = "primary_text"
    private const val IN_DISPLAY_VALUE = "display_value"
    private const val IN_MGDL = "mgdl"
    private const val IN_RATE = "rate"
    private const val IN_TIME_MILLIS = "time_millis"
    private const val IN_SENSOR_GEN = "sensor_gen"
    private const val IN_ALARM = "alarm"
    private const val IN_TEST = "test"

    @JvmStatic
    fun enqueueGlucose(
        sensorId: String?,
        primaryText: String?,
        primaryDisplayValue: Double,
        primaryMgdl: Int,
        rate: Float,
        timeMillis: Long,
        sensorGen: Int,
        alarm: Int
    ) {
        enqueueGlucoseInternal(
            context = Applic.app,
            sensorId = sensorId,
            primaryText = primaryText,
            primaryDisplayValue = primaryDisplayValue,
            primaryMgdl = primaryMgdl,
            rate = rate,
            timeMillis = timeMillis,
            sensorGen = sensorGen,
            alarm = alarm,
            test = false
        )
    }

    @JvmStatic
    fun enqueueCurrentTest(context: Context = Applic.app): Int {
        val config = OutboundApiSettings.load(context)
        if (!config.isReady()) {
            return TEST_NOT_CONFIGURED
        }
        val current = CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout) ?: return TEST_NO_CURRENT_READING
        enqueueGlucoseInternal(
            context = context,
            sensorId = current.sensorId,
            primaryText = current.primaryStr,
            primaryDisplayValue = current.primaryValue.toDouble(),
            primaryMgdl = current.sharedMgdl,
            rate = current.rate,
            timeMillis = current.timeMillis,
            sensorGen = current.sensorGen,
            alarm = 0,
            test = true
        )
        return TEST_QUEUED
    }

    private fun enqueueGlucoseInternal(
        context: Context,
        sensorId: String?,
        primaryText: String?,
        primaryDisplayValue: Double,
        primaryMgdl: Int,
        rate: Float,
        timeMillis: Long,
        sensorGen: Int,
        alarm: Int,
        test: Boolean
    ) {
        val appContext = context.applicationContext
        val config = OutboundApiSettings.load(appContext)
        if (!config.isReady()) return
        if (timeMillis <= 0L || primaryMgdl <= 0) return

        val eventId = if (test) {
            "test-${System.currentTimeMillis()}"
        } else {
            "${sensorId.orEmpty()}:$timeMillis:$primaryMgdl"
        }
        val now = System.currentTimeMillis()
        if (!test && !OutboundApiSettings.shouldQueue(appContext, eventId, now)) {
            return
        }

        val input = Data.Builder()
            .putString(IN_EVENT_ID, eventId)
            .putString(IN_SENSOR_ID, sensorId.orEmpty())
            .putString(IN_PRIMARY_TEXT, primaryText.orEmpty())
            .putDouble(IN_DISPLAY_VALUE, primaryDisplayValue)
            .putInt(IN_MGDL, primaryMgdl)
            .putFloat(IN_RATE, rate)
            .putLong(IN_TIME_MILLIS, timeMillis)
            .putInt(IN_SENSOR_GEN, sensorGen)
            .putInt(IN_ALARM, alarm)
            .putBoolean(IN_TEST, test)
            .build()

        val request = OneTimeWorkRequestBuilder<OutboundApiWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(appContext).enqueue(request)
        if (!test) {
            OutboundApiSettings.recordQueued(appContext, eventId, now)
        }
    }

    internal fun inputToReading(input: Data): Reading? {
        val timeMillis = input.getLong(IN_TIME_MILLIS, 0L)
        val mgdl = input.getInt(IN_MGDL, 0)
        if (timeMillis <= 0L || mgdl <= 0) return null
        val displayValue = input.getDouble(IN_DISPLAY_VALUE, Double.NaN)
        return Reading(
            eventId = input.getString(IN_EVENT_ID).orEmpty(),
            sensorId = input.getString(IN_SENSOR_ID).orEmpty(),
            primaryText = input.getString(IN_PRIMARY_TEXT).orEmpty(),
            displayValue = displayValue,
            mgdl = mgdl,
            rateMgdlPerMinute = input.getFloat(IN_RATE, Float.NaN),
            timeMillis = timeMillis,
            sensorGen = input.getInt(IN_SENSOR_GEN, 0),
            alarm = input.getInt(IN_ALARM, 0),
            test = input.getBoolean(IN_TEST, false)
        )
    }

    internal data class Reading(
        val eventId: String,
        val sensorId: String,
        val primaryText: String,
        val displayValue: Double,
        val mgdl: Int,
        val rateMgdlPerMinute: Float,
        val timeMillis: Long,
        val sensorGen: Int,
        val alarm: Int,
        val test: Boolean
    ) {
        val unit: String get() = if (Applic.unit == 1) "mmol/L" else "mg/dL"
        val mmol: Float get() = mgdl / MGDL_PER_MMOLL
        val rateMmolPerMinute: Float get() = rateMgdlPerMinute / MGDL_PER_MMOLL
        val trendName: String get() = Natives.getxDripTrendName(rateMgdlPerMinute) ?: ""
        val trendArrow: String get() = trendArrow(trendName)
        val displayText: String
            get() = primaryText.ifBlank {
                if (Applic.unit == 1) formatNumber(mmol, 1) else mgdl.toString()
            }
    }

    internal fun renderMessage(template: String, reading: Reading): String {
        val time = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(reading.timeMillis))
        return template
            .replace("{event_id}", reading.eventId)
            .replace("{value}", reading.displayText)
            .replace("{unit}", reading.unit)
            .replace("{mgdl}", reading.mgdl.toString())
            .replace("{mmol}", formatNumber(reading.mmol, 2))
            .replace("{trend}", reading.trendName)
            .replace("{trend_arrow}", reading.trendArrow)
            .replace("{rate_mgdl}", formatNumber(reading.rateMgdlPerMinute, 1))
            .replace("{rate_mmol}", formatNumber(reading.rateMmolPerMinute, 3))
            .replace("{timestamp}", reading.timeMillis.toString())
            .replace("{time}", time)
            .replace("{sensor}", reading.sensorId)
            .replace("{sensor_gen}", reading.sensorGen.toString())
            .replace("{alarm}", reading.alarm.toString())
            .replace("{test}", reading.test.toString())
    }

    internal fun formatNumber(value: Float, decimals: Int): String {
        if (!value.isFinite()) return ""
        return "%.${decimals}f".format(Locale.US, value)
    }

    private fun trendArrow(trendName: String): String =
        when (trendName) {
            "DoubleUp" -> "\u2191\u2191"
            "SingleUp" -> "\u2191"
            "FortyFiveUp" -> "\u2197"
            "Flat" -> "\u2192"
            "FortyFiveDown" -> "\u2198"
            "SingleDown" -> "\u2193"
            "DoubleDown" -> "\u2193\u2193"
            else -> "\u2192"
        }
}

class OutboundApiWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val context = applicationContext
        val config = OutboundApiSettings.load(context)
        if (!config.isReady()) {
            return Result.success()
        }
        val reading = OutboundApi.inputToReading(inputData) ?: return Result.success()

        return try {
            val response = send(config, reading)
            if (response.ok) {
                OutboundApiSettings.recordSuccess(context, response.code)
                Result.success()
            } else {
                OutboundApiSettings.recordAttempt(context, response.code, response.error)
                if (response.retryable && runAttemptCount < 5) Result.retry() else Result.failure()
            }
        } catch (th: Throwable) {
            Log.e(TAG, "send failed: ${Log.stackline(th)}")
            OutboundApiSettings.recordAttempt(context, -1, th.message ?: th.javaClass.simpleName)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    private data class SendResponse(
        val code: Int,
        val ok: Boolean,
        val retryable: Boolean,
        val error: String?
    )

    private fun send(
        config: OutboundApiSettings.Config,
        reading: OutboundApi.Reading
    ): SendResponse {
        val message = OutboundApi.renderMessage(config.resolvedTemplate(), reading)
        val provider = config.normalizedProvider()
        val body = when (provider) {
            OutboundApiSettings.PROVIDER_VK -> buildVkBody(config, reading, message)
            else -> buildJsonBody(reading, message).toString().toByteArray(Charsets.UTF_8)
        }
        val contentType = when (provider) {
            OutboundApiSettings.PROVIDER_VK -> "application/x-www-form-urlencoded; charset=UTF-8"
            else -> "application/json; charset=UTF-8"
        }

        val url = URL(config.resolvedUrl())
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            if (provider != OutboundApiSettings.PROVIDER_VK) {
                applyHeaders(config.headers)
            }
        }

        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(String(body, Charsets.UTF_8))
            }

            val code = connection.responseCode
            val responseText = readResponse(connection)
            if (provider == OutboundApiSettings.PROVIDER_VK) {
                val vkError = parseVkError(responseText)
                if (vkError != null) {
                    return SendResponse(
                        code = code,
                        ok = false,
                        retryable = code >= 500 || code == 429,
                        error = vkError
                    )
                }
            }
            val ok = code in 200..299
            return SendResponse(
                code = code,
                ok = ok,
                retryable = code == 429 || code >= 500,
                error = if (ok) null else responseText.take(500).ifBlank { "HTTP $code" }
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun buildVkBody(
        config: OutboundApiSettings.Config,
        reading: OutboundApi.Reading,
        message: String
    ): ByteArray {
        val randomId = stableRandomId(reading)
        val fields = linkedMapOf(
            "access_token" to config.token.trim(),
            "v" to config.apiVersion.trim().ifBlank { OutboundApiSettings.DEFAULT_VK_API_VERSION },
            "peer_id" to config.chatId.trim(),
            "random_id" to randomId.toString(),
            "message" to message
        )
        return formEncode(fields).toByteArray(Charsets.UTF_8)
    }

    private fun buildJsonBody(
        reading: OutboundApi.Reading,
        message: String
    ): JSONObject {
        return JSONObject()
            .put("schema", "tk.glucodata.outbound.glucose.v1")
            .put("type", "glucose")
            .put("event_id", reading.eventId)
            .put("test", reading.test)
            .put("app", "JugglucoNG")
            .put("sensor_id", reading.sensorId)
            .put("sensor_gen", reading.sensorGen)
            .put("timestamp", reading.timeMillis)
            .put("glucose_mgdl", reading.mgdl)
            .put("glucose_mmol", OutboundApi.formatNumber(reading.mmol, 2).toDoubleOrNull())
            .put("display_value", reading.displayText)
            .put("display_unit", reading.unit)
            .put("rate_mgdl_per_min", reading.rateMgdlPerMinute.takeIf { it.isFinite() })
            .put("rate_mmol_per_min", reading.rateMmolPerMinute.takeIf { it.isFinite() })
            .put("trend", reading.trendName)
            .put("trend_arrow", reading.trendArrow)
            .put("alarm", reading.alarm)
            .put("message", message)
    }

    private fun HttpURLConnection.applyHeaders(rawHeaders: String) {
        rawHeaders.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (name.equals("Content-Type", ignoreCase = true)) return@forEach
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    setRequestProperty(name, value)
                }
            }
    }

    private fun formEncode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun stableRandomId(reading: OutboundApi.Reading): Int {
        var hash = reading.eventId.hashCode()
        hash = 31 * hash + reading.timeMillis.hashCode()
        hash = 31 * hash + reading.mgdl
        return hash and Int.MAX_VALUE
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: connection.inputStream ?: return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            buildString {
                var line = reader.readLine()
                while (line != null) {
                    append(line)
                    line = reader.readLine()
                }
            }
        }
    }

    private fun parseVkError(responseText: String): String? {
        if (responseText.isBlank()) return null
        return try {
            val root = JSONObject(responseText)
            val error = root.optJSONObject("error") ?: return null
            error.optString("error_msg", "VK API error").ifBlank { "VK API error" }
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        private const val TAG = "OutboundApiWorker"
    }
}
