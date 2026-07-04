package tk.glucodata;

import java.util.Locale;

/**
 * Pure alert delivery rules shared by the Java notification bridge and tests.
 *
 * Alarm mode is a full-screen alarm-window contract. A notification is only a
 * fallback when neither the direct launch nor queued backup can be established.
 * Both mode always keeps the user's explicitly selected second surface.
 */
public final class AlertDeliveryPolicy {
    public static final String NOTIFICATION_ONLY = "NOTIFICATION_ONLY";
    public static final String SYSTEM_ALARM = "SYSTEM_ALARM";
    public static final String BOTH = "BOTH";

    private AlertDeliveryPolicy() {
    }

    public static String normalize(String deliveryMode) {
        if (deliveryMode == null) {
            return NOTIFICATION_ONLY;
        }
        final String mode = deliveryMode.toUpperCase(Locale.ROOT);
        if ("ALARM".equals(mode) || SYSTEM_ALARM.equals(mode)) {
            return SYSTEM_ALARM;
        }
        if (BOTH.equals(mode)) {
            return BOTH;
        }
        return NOTIFICATION_ONLY;
    }

    public static boolean shouldAttemptAlarmWindow(String normalizedMode) {
        return SYSTEM_ALARM.equals(normalizedMode) || BOTH.equals(normalizedMode);
    }

    public static boolean shouldAttachFullScreenIntent(String normalizedMode) {
        return shouldAttemptAlarmWindow(normalizedMode);
    }

    public static boolean shouldPostAlertNotification(String normalizedMode, boolean alarmWindowQueued) {
        if (BOTH.equals(normalizedMode) || NOTIFICATION_ONLY.equals(normalizedMode)) {
            return true;
        }
        return !alarmWindowQueued;
    }

    public static boolean shouldUseAlarmAudioStream(String normalizedMode, boolean disturb) {
        return disturb || shouldAttemptAlarmWindow(normalizedMode);
    }
}
