package tk.glucodata;

import androidx.annotation.Keep;

import tk.glucodata.data.calibration.CalibrationManager;

public final class NightscoutCalibration {
    private static final float MGDL_PER_MMOLL = 18.0182f;

    private NightscoutCalibration() {}

    private static boolean isRawPrimary(int viewMode) {
        return viewMode == 1 || viewMode == 3;
    }

    private static float rawCurrentToMgdl(int rawCurrent) {
        if (rawCurrent <= 0) {
            return 0f;
        }
        return (rawCurrent * MGDL_PER_MMOLL) / 10.0f;
    }

    private static String resolveSensorId(String sensorId) {
        if (sensorId != null && !sensorId.trim().isEmpty()) {
            return sensorId;
        }
        final String current = Natives.lastsensorname();
        return current != null ? current : "";
    }

    @Keep
    public static boolean hasCalibrationForViewMode(String sensorId, int viewMode) {
        try {
            final boolean rawPrimary = isRawPrimary(viewMode);
            return CalibrationManager.INSTANCE.hasActiveCalibration(rawPrimary, resolveSensorId(sensorId));
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Keep
    public static float getCalibratedValueForViewMode(
            String sensorId,
            int viewMode,
            float autoMgdl,
            float rawMgdl,
            long timestampMillis
    ) {
        try {
            final boolean rawPrimary = isRawPrimary(viewMode);
            final String resolvedSensorId = resolveSensorId(sensorId);
            if (!CalibrationManager.INSTANCE.hasActiveCalibration(rawPrimary, resolvedSensorId)) {
                return 0f;
            }

            final float baseValue = rawPrimary ? rawMgdl : autoMgdl;
            if (!Float.isFinite(baseValue) || baseValue <= 0f) {
                return 0f;
            }

            final float calibrated = CalibrationManager.INSTANCE.getCalibratedValue(
                    baseValue,
                    timestampMillis,
                    rawPrimary,
                    false,
                    resolvedSensorId
            );
            if (!Float.isFinite(calibrated) || calibrated <= 0f) {
                return 0f;
            }
            return calibrated;
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    @Keep
    public static int getNightscoutCalibrationOverride(
            String sensorId,
            int viewMode,
            int autoMgdl,
            int rawCurrent,
            long timestampMillis
    ) {
        try {
            final float calibrated = getCalibratedValueForViewMode(
                    sensorId,
                    viewMode,
                    autoMgdl,
                    rawCurrentToMgdl(rawCurrent),
                    timestampMillis
            );
            if (!Float.isFinite(calibrated) || calibrated <= 0f) {
                return 0;
            }
            return Math.round(calibrated);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
