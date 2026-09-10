package tk.glucodata

/**
 * Slot arithmetic for Talker.selspeak()'s routine announcements, kept free of Android
 * dependencies so it is unit-testable (Talker itself cannot be class-loaded on the JVM).
 */
object AnnounceSlot {
    /** How long selspeak() defers after an utterance the engine would not take. Short enough that
     *  the next reading retries, so a failed attempt costs one reading rather than one whole
     *  user-configured separation interval. */
    const val FAILED_SPEAK_RETRY_MS = 30_000L

    /** Earliest time the next announcement may go out after an attempt made at [now]:
     *  a full separation when the engine took the utterance, only a short retry when it refused. */
    @JvmStatic
    fun nextSlotAfterAttempt(now: Long, separationMs: Long, accepted: Boolean): Long =
        now + if (accepted) separationMs else minOf(separationMs, FAILED_SPEAK_RETRY_MS)

    /** Where a pending slot moves to when the separation becomes [separationMs]: never later
     *  than the new separation after the last claimed slot. Only ever shortens -- a longer
     *  separation takes effect from the next announcement, so a raise cannot push out a
     *  pending retry. */
    @JvmStatic
    fun nextSlotAfterSeparationChange(nexttime: Long, lastClaimAt: Long, separationMs: Long): Long =
        if (lastClaimAt <= 0L) nexttime else minOf(nexttime, lastClaimAt + separationMs)
}
