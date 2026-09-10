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

    /** The slot after the utterance claimed at [claimAt] was refused. Only that claim's own slot
     *  is handed back: if a later reading has claimed since (a slow speak() outliving a short
     *  separation), the refusal is stale and must not shorten the newer slot. Otherwise min, not
     *  assign, since a separation change after the claim may already have pulled the slot in. */
    @JvmStatic
    fun slotAfterRefusal(nexttime: Long, lastClaimAt: Long, claimAt: Long, retryAt: Long): Long =
        if (lastClaimAt != claimAt) nexttime else minOf(nexttime, retryAt)

    /** Where a pending slot moves to when the separation becomes [separationMs]: never later
     *  than the new separation after the last claimed slot. Only ever shortens -- a longer
     *  separation takes effect from the next announcement, so a raise cannot push out a
     *  pending retry. */
    @JvmStatic
    fun nextSlotAfterSeparationChange(nexttime: Long, lastClaimAt: Long, separationMs: Long): Long =
        if (lastClaimAt <= 0L) nexttime else minOf(nexttime, lastClaimAt + separationMs)
}
