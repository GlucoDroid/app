package tk.glucodata

import android.content.Context
import androidx.annotation.Keep

@Keep
object OutboundApiSettings {
    const val PROVIDER_WEBHOOK_JSON = "webhook_json"
    const val PROVIDER_VK = "vk"

    private const val PREFS = "outbound_api"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_API_VERSION = "api_version"
    private const val KEY_HEADERS = "headers"
    private const val KEY_MESSAGE_TEMPLATE = "message_template"
    private const val KEY_MIN_INTERVAL_MINUTES = "min_interval_minutes"
    private const val KEY_LAST_QUEUED_EVENT_ID = "last_queued_event_id"
    private const val KEY_LAST_QUEUED_AT_MS = "last_queued_at_ms"
    private const val KEY_LAST_ATTEMPT_AT_MS = "last_attempt_at_ms"
    private const val KEY_LAST_SUCCESS_AT_MS = "last_success_at_ms"
    private const val KEY_LAST_RESPONSE_CODE = "last_response_code"
    private const val KEY_LAST_ERROR = "last_error"

    const val DEFAULT_VK_API_VERSION = "5.199"
    const val DEFAULT_MIN_INTERVAL_MINUTES = 5
    const val DEFAULT_WEBHOOK_URL = ""
    const val DEFAULT_VK_URL = "https://api.vk.com/method/messages.send"
    const val DEFAULT_WEBHOOK_TEMPLATE =
        "{value} {unit} {trend_arrow} ({rate_mgdl} mg/dL/min) {time}"
    const val DEFAULT_VK_TEMPLATE =
        "GV:{mmol}|TR:{trend_arrow}|AL:{alarm}|RT:{rate_mmol}|TS:{timestamp}"

    data class Config(
        val enabled: Boolean,
        val provider: String,
        val url: String,
        val token: String,
        val chatId: String,
        val apiVersion: String,
        val headers: String,
        val messageTemplate: String,
        val minIntervalMinutes: Int
    ) {
        fun normalizedProvider(): String =
            if (provider == PROVIDER_VK) PROVIDER_VK else PROVIDER_WEBHOOK_JSON

        fun resolvedUrl(): String {
            val trimmed = url.trim()
            if (trimmed.isNotEmpty()) return trimmed
            return defaultUrl(normalizedProvider())
        }

        fun resolvedTemplate(): String {
            val trimmed = messageTemplate.trim()
            if (trimmed.isNotEmpty()) return trimmed
            return defaultTemplate(normalizedProvider())
        }

        fun isReady(): Boolean {
            if (!enabled) return false
            if (resolvedUrl().isBlank()) return false
            return when (normalizedProvider()) {
                PROVIDER_VK -> token.isNotBlank() && chatId.isNotBlank()
                else -> true
            }
        }
    }

    data class Status(
        val lastAttemptAtMs: Long,
        val lastSuccessAtMs: Long,
        val lastResponseCode: Int,
        val lastError: String?
    )

    @JvmStatic
    fun defaultUrl(provider: String): String =
        if (provider == PROVIDER_VK) DEFAULT_VK_URL else DEFAULT_WEBHOOK_URL

    @JvmStatic
    fun defaultTemplate(provider: String): String =
        if (provider == PROVIDER_VK) DEFAULT_VK_TEMPLATE else DEFAULT_WEBHOOK_TEMPLATE

    @JvmStatic
    fun load(context: Context = Applic.app): Config {
        val prefs = prefs(context)
        val provider = prefs.getString(KEY_PROVIDER, PROVIDER_WEBHOOK_JSON) ?: PROVIDER_WEBHOOK_JSON
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            provider = provider,
            url = prefs.getString(KEY_URL, defaultUrl(provider)).orEmpty(),
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            chatId = prefs.getString(KEY_CHAT_ID, "").orEmpty(),
            apiVersion = prefs.getString(KEY_API_VERSION, DEFAULT_VK_API_VERSION).orEmpty()
                .ifBlank { DEFAULT_VK_API_VERSION },
            headers = prefs.getString(KEY_HEADERS, "").orEmpty(),
            messageTemplate = prefs.getString(KEY_MESSAGE_TEMPLATE, defaultTemplate(provider)).orEmpty(),
            minIntervalMinutes = prefs.getInt(
                KEY_MIN_INTERVAL_MINUTES,
                DEFAULT_MIN_INTERVAL_MINUTES
            ).coerceAtLeast(0)
        )
    }

    @JvmStatic
    fun save(context: Context = Applic.app, config: Config) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_PROVIDER, config.normalizedProvider())
            .putString(KEY_URL, config.url.trim())
            .putString(KEY_TOKEN, config.token.trim())
            .putString(KEY_CHAT_ID, config.chatId.trim())
            .putString(KEY_API_VERSION, config.apiVersion.trim().ifBlank { DEFAULT_VK_API_VERSION })
            .putString(KEY_HEADERS, config.headers)
            .putString(KEY_MESSAGE_TEMPLATE, config.messageTemplate)
            .putInt(KEY_MIN_INTERVAL_MINUTES, config.minIntervalMinutes.coerceAtLeast(0))
            .apply()
    }

    @JvmStatic
    fun isEnabled(context: Context = Applic.app): Boolean = load(context).enabled

    @JvmStatic
    fun status(context: Context = Applic.app): Status {
        val prefs = prefs(context)
        return Status(
            lastAttemptAtMs = prefs.getLong(KEY_LAST_ATTEMPT_AT_MS, 0L),
            lastSuccessAtMs = prefs.getLong(KEY_LAST_SUCCESS_AT_MS, 0L),
            lastResponseCode = prefs.getInt(KEY_LAST_RESPONSE_CODE, 0),
            lastError = prefs.getString(KEY_LAST_ERROR, null)
        )
    }

    fun shouldQueue(context: Context, eventId: String, nowMs: Long): Boolean {
        val prefs = prefs(context)
        if (eventId == prefs.getString(KEY_LAST_QUEUED_EVENT_ID, null)) {
            return false
        }
        val minIntervalMs = load(context).minIntervalMinutes * 60_000L
        val lastQueuedAt = prefs.getLong(KEY_LAST_QUEUED_AT_MS, 0L)
        return minIntervalMs <= 0L || lastQueuedAt <= 0L || nowMs - lastQueuedAt >= minIntervalMs
    }

    fun recordQueued(context: Context, eventId: String, nowMs: Long) {
        prefs(context).edit()
            .putString(KEY_LAST_QUEUED_EVENT_ID, eventId)
            .putLong(KEY_LAST_QUEUED_AT_MS, nowMs)
            .apply()
    }

    fun recordAttempt(context: Context, responseCode: Int, error: String?) {
        prefs(context).edit()
            .putLong(KEY_LAST_ATTEMPT_AT_MS, System.currentTimeMillis())
            .putInt(KEY_LAST_RESPONSE_CODE, responseCode)
            .putString(KEY_LAST_ERROR, error?.take(500))
            .apply()
    }

    fun recordSuccess(context: Context, responseCode: Int) {
        prefs(context).edit()
            .putLong(KEY_LAST_ATTEMPT_AT_MS, System.currentTimeMillis())
            .putLong(KEY_LAST_SUCCESS_AT_MS, System.currentTimeMillis())
            .putInt(KEY_LAST_RESPONSE_CODE, responseCode)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
