package tk.glucodata;

import static tk.glucodata.alerts.AlertConfigKt.sanitizeAlertDurationSeconds;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

final class AlertVibrationPattern {
    private static final int MAX_SEGMENTS = 512;

    final long[] timings;
    final int[] amplitudes;

    private AlertVibrationPattern(long[] timings, int[] amplitudes) {
        this.timings = timings;
        this.amplitudes = amplitudes;
    }

    static AlertVibrationPattern buildFinite(long[] baseTimings, int[] baseAmplitudes, int durationSeconds) {
        // Never hand Android an infinite alert vibration; scheduled stop is only an early cleanup path.
        final long maxDurationMs = TimeUnit.SECONDS.toMillis(sanitizeAlertDurationSeconds(durationSeconds));
        final ArrayList<Long> timingList = new ArrayList<>();
        final ArrayList<Integer> amplitudeList = new ArrayList<>();
        long elapsedMs = 0L;
        boolean emittedAny = false;

        while (elapsedMs < maxDurationMs && timingList.size() < MAX_SEGMENTS) {
            long loopDurationMs = 0L;
            for (int i = 0; i < baseTimings.length && elapsedMs < maxDurationMs; i++) {
                final long baseDuration = Math.max(0L, baseTimings[i]);
                loopDurationMs += baseDuration;
                if (emittedAny && baseDuration == 0L) {
                    continue;
                }
                final long clippedDuration = Math.min(baseDuration, maxDurationMs - elapsedMs);
                timingList.add(clippedDuration);
                amplitudeList.add(baseAmplitudes.length == 0
                        ? 0
                        : baseAmplitudes[Math.min(i, baseAmplitudes.length - 1)]);
                elapsedMs += clippedDuration;
                emittedAny = true;
            }
            if (loopDurationMs <= 0L) {
                break;
            }
        }

        if (elapsedMs <= 0L) {
            timingList.clear();
            amplitudeList.clear();
            timingList.add(maxDurationMs);
            amplitudeList.add(0);
        } else if (timingList.isEmpty()) {
            timingList.add(maxDurationMs);
            amplitudeList.add(0);
        }

        final long[] timings = new long[timingList.size()];
        final int[] amplitudes = new int[amplitudeList.size()];
        for (int i = 0; i < timingList.size(); i++) {
            timings[i] = timingList.get(i);
            amplitudes[i] = amplitudeList.get(i);
        }
        return new AlertVibrationPattern(timings, amplitudes);
    }

    long totalDurationMs() {
        long total = 0L;
        for (long timing : timings) {
            total += timing;
        }
        return total;
    }
}
