package tk.glucodata

/**
 * Bridge from src/main to the phone's journal, which lives in the mobile source
 * set only. Same pattern as [CalibrationAccess]: resolved by name, absent on
 * wear and other variants without the journal.
 *
 * The facade on the mobile side does its own encoding, so the reflective surface
 * is two methods taking and returning byte arrays rather than journal types.
 */
object JournalAccess {
    private const val CLASS_NAME = "tk.glucodata.data.journal.WearJournalBridge"

    private val holder by lazy { runCatching { Class.forName(CLASS_NAME) }.getOrNull() }
    private val instance by lazy { runCatching { holder?.getField("INSTANCE")?.get(null) }.getOrNull() }

    private val serveEntriesMethod by lazy {
        runCatching { holder?.getMethod("serveEntries", Long::class.javaPrimitiveType) }.getOrNull()
    }
    private val applyCommandMethod by lazy {
        runCatching { holder?.getMethod("applyCommand", ByteArray::class.java) }.getOrNull()
    }
    private val isEnabledMethod by lazy {
        runCatching { holder?.getMethod("isJournalEnabled") }.getOrNull()
    }

    /** Encoded journal payload, or null when there is no journal to serve. */
    @JvmStatic
    fun serveEntries(fromMs: Long): ByteArray? = runCatching {
        serveEntriesMethod?.invoke(instance, fromMs) as? ByteArray
    }.getOrNull()

    @JvmStatic
    fun applyCommand(data: ByteArray): Boolean = runCatching {
        applyCommandMethod?.invoke(instance, data) as? Boolean
    }.getOrNull() ?: false

    @JvmStatic
    fun isJournalEnabled(): Boolean = runCatching {
        isEnabledMethod?.invoke(instance) as? Boolean
    }.getOrNull() ?: false
}
