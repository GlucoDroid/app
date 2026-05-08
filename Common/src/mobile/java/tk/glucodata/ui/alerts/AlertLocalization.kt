package tk.glucodata.ui.alerts

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tk.glucodata.R
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.VolumeProfile

@Composable
internal fun AlertDeliveryMode.localizedName(): String = stringResource(
    when (this) {
        AlertDeliveryMode.NOTIFICATION_ONLY -> R.string.alert_delivery_notification
        AlertDeliveryMode.SYSTEM_ALARM -> R.string.alert_delivery_alarm
        AlertDeliveryMode.BOTH -> R.string.alert_delivery_both
    }
)

@Composable
internal fun VolumeProfile.localizedName(): String = stringResource(
    when (this) {
        VolumeProfile.HIGH -> R.string.volume_profile_high
        VolumeProfile.MEDIUM -> R.string.volume_profile_medium
        VolumeProfile.ASCENDING -> R.string.volume_profile_ascending
        VolumeProfile.VIBRATE_ONLY -> R.string.volume_profile_vibrate_only
        VolumeProfile.SILENT -> R.string.volume_profile_silent
    }
)
