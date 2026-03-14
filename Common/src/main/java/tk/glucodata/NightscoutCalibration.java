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

    @Keep
    public static int getNightscoutCalibrationOverride(
            String sensorId,
            int viewMode,
            int autoMgdl,
            int rawCurrent,
            long timestampMillis
    ) {
        try {
            final boolean rawPrimary = isRawPrimary(viewMode);
            if (!CalibrationManager.INSTANCE.hasActiveCalibration(rawPrimary, sensorId)) {
                return 0;
            }

            final float baseValue = rawPrimary ? rawCurrentToMgdl(rawCurrent) : autoMgdl;
            if (!Float.isFinite(baseValue) || baseValue <= 0f) {
                return 0;
            }

            final float calibrated = CalibrationManager.INSTANCE.getCalibratedValue(
                    baseValue,
                    timestampMillis,
                    rawPrimary,
                    false,
                    sensorId
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
