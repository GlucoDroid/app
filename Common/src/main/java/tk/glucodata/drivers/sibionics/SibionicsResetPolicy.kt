package tk.glucodata.drivers.sibionics

import kotlin.math.abs

internal object SibionicsResetPolicy {
    const val ENABLED_DAYS = 22
    const val DISABLED_DAYS = 300

    const val REMINDER_LEAD_MS = 4L * 60L * 60L * 1000L
    const val POSTPONE_MS = 2L * 60L * 60L * 1000L
    const val EXPECTED_END_GUARD_MS = 4L * 60L * 60L * 1000L

    private const val COMFORTABLE_GLUCOSE_MIN_MGDL = 80f
    private const val COMFORTABLE_GLUCOSE_MAX_MGDL = 180f
    private const val COMFORTABLE_RATE_MAX_MGDL_PER_MIN = 2f

    data class Decision(
        val showReminder: Boolean = false,
        val resetNow: Boolean = false,
        val forced: Boolean = false,
    )

    fun normalizedDays(
        variant: SibionicsConstants.Variant,
        persistedDays: Int,
        hasPersistedSetting: Boolean,
    ): Int {
        if (variant != SibionicsConstants.Variant.SIBIONICS2) return DISABLED_DAYS
        if (!hasPersistedSetting) return ENABLED_DAYS
        return if (persistedDays in 1 until DISABLED_DAYS) ENABLED_DAYS else DISABLED_DAYS
    }

    fun dueAtMs(startTimeMs: Long): Long =
        startTimeMs + ENABLED_DAYS * SibionicsConstants.DAY_MS

    fun hardDeadlineMs(startTimeMs: Long): Long =
        startTimeMs + SibionicsConstants.SIBIONICS2_EXPECTED_LIFETIME_MS - EXPECTED_END_GUARD_MS

    fun evaluate(
        nowMs: Long,
        startTimeMs: Long,
        autoResetEnabled: Boolean,
        postponedUntilMs: Long,
        lastReminderAtMs: Long,
        glucoseMgdl: Float,
        rateMgdlPerMin: Float,
    ): Decision {
        if (startTimeMs <= 0L || nowMs < startTimeMs) return Decision()

        val dueAt = dueAtMs(startTimeMs)
        val resetWindowAt = dueAt - REMINDER_LEAD_MS
        val hardDeadline = hardDeadlineMs(startTimeMs)
        if (nowMs >= hardDeadline) {
            return Decision(resetNow = true, forced = true)
        }

        val postponeActive = postponedUntilMs > nowMs
        val reminderDue = nowMs >= resetWindowAt &&
            !postponeActive &&
            (lastReminderAtMs <= 0L || nowMs - lastReminderAtMs >= POSTPONE_MS)

        if (!autoResetEnabled || nowMs < resetWindowAt || postponeActive) {
            return Decision(showReminder = reminderDue)
        }

        val comfortable = glucoseMgdl.isFinite() &&
            glucoseMgdl in COMFORTABLE_GLUCOSE_MIN_MGDL..COMFORTABLE_GLUCOSE_MAX_MGDL &&
            rateMgdlPerMin.isFinite() &&
            abs(rateMgdlPerMin) <= COMFORTABLE_RATE_MAX_MGDL_PER_MIN
        return Decision(
            showReminder = reminderDue,
            resetNow = comfortable,
        )
    }
}
