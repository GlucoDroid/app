package tk.glucodata;

/** Pure ordering policy for callbacks that are allowed to drive realtime surfaces. */
final class RealtimeReadingPolicy {
<<<<<<< HEAD
=======
    private static final long NATIVE_CURRENT_READING_SKEW_MS = 1_000L;

>>>>>>> rebase/test-1.0.4-merge
    private RealtimeReadingPolicy() {
    }

    static boolean shouldDispatch(long candidateTimeMs, long inMemoryHighWaterMs, long nativeHighWaterMs) {
        if (candidateTimeMs <= 0L) {
            return false;
        }
<<<<<<< HEAD
        final long highWaterMs = Math.max(inMemoryHighWaterMs, nativeHighWaterMs);
        return highWaterMs <= 0L || candidateTimeMs >= highWaterMs;
=======
        if (inMemoryHighWaterMs > 0L && candidateTimeMs < inMemoryHighWaterMs) {
            return false;
        }
        if (nativeHighWaterMs <= 0L || candidateTimeMs >= nativeHighWaterMs) {
            return true;
        }

        // Native storage can round the same live reading just ahead of the BLE callback timestamp.
        return nativeHighWaterMs - candidateTimeMs < NATIVE_CURRENT_READING_SKEW_MS;
>>>>>>> rebase/test-1.0.4-merge
    }
}
