package tk.glucodata;

/** Pure ordering policy for callbacks that are allowed to drive realtime surfaces. */
final class RealtimeReadingPolicy {
    private RealtimeReadingPolicy() {
    }

    static boolean shouldDispatch(long candidateTimeMs, long inMemoryHighWaterMs, long nativeHighWaterMs) {
        if (candidateTimeMs <= 0L) {
            return false;
        }
        final long highWaterMs = Math.max(inMemoryHighWaterMs, nativeHighWaterMs);
        return highWaterMs <= 0L || candidateTimeMs >= highWaterMs;
    }
}
