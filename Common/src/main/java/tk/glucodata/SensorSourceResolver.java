package tk.glucodata;

public final class SensorSourceResolver {
    public static final int SENSOR_KIND_UNKNOWN = -1;
    public static final int SENSOR_KIND_LIBRE2 = 2;
    public static final int SENSOR_KIND_LIBRE3 = 3;
    public static final int SENSOR_KIND_SIBIONICS = 0x10;
    public static final int SENSOR_KIND_ACCUCHEK = 0x20;
    public static final int SENSOR_KIND_AIDEX = 0x30;
    public static final int SENSOR_KIND_DEXCOM = 0x40;

    private SensorSourceResolver() {}

    public static String resolveSourceInfo(String sensorId, int fallbackSensorGen) {
        return sourceForKind(resolveSensorKind(sensorId, fallbackSensorGen));
    }

    public static int resolveSensorKind(String sensorId, int fallbackSensorGen) {
        final int snapshotKind = resolveSnapshotSensorKind(sensorId);
        if (snapshotKind != SENSOR_KIND_UNKNOWN) {
            return snapshotKind;
        }
        return fallbackKindFromSensorGen(fallbackSensorGen);
    }

    private static int resolveSnapshotSensorKind(String sensorId) {
        if (sensorId == null || sensorId.isEmpty()) {
            return SENSOR_KIND_UNKNOWN;
        }
        try {
            final long[] snapshot = Natives.getSensorUiSnapshot(sensorId);
            if (snapshot != null && snapshot.length >= 1) {
                return (int) snapshot[0];
            }
        } catch (Throwable th) {
            Log.stack("SensorSourceResolver", "resolveSnapshotSensorKind", th);
        }
        return SENSOR_KIND_UNKNOWN;
    }

    private static int fallbackKindFromSensorGen(int sensorGen) {
        return switch (sensorGen) {
            case SENSOR_KIND_LIBRE2 -> SENSOR_KIND_LIBRE2;
            case SENSOR_KIND_LIBRE3 -> SENSOR_KIND_LIBRE3;
            case SENSOR_KIND_SIBIONICS -> SENSOR_KIND_SIBIONICS;
            case SENSOR_KIND_ACCUCHEK -> SENSOR_KIND_ACCUCHEK;
            case SENSOR_KIND_DEXCOM -> SENSOR_KIND_DEXCOM;
            default -> SENSOR_KIND_UNKNOWN;
        };
    }

    private static String sourceForKind(int sensorKind) {
        return switch (sensorKind) {
            case SENSOR_KIND_LIBRE3 -> "Libre3";
            case SENSOR_KIND_SIBIONICS -> "GS1Sb";
            case SENSOR_KIND_ACCUCHEK -> "AccuChek";
            case SENSOR_KIND_AIDEX -> "AiDex";
            case SENSOR_KIND_DEXCOM -> "G7";
            case SENSOR_KIND_LIBRE2 -> "Libre2";
            default -> "Unknown";
        };
    }
}
