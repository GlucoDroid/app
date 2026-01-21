package tk.glucodata.ui.alerts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.VolumeProfile
import tk.glucodata.ui.components.StyledSwitch
import tk.glucodata.ui.util.ConnectedButtonGroup

/**
 * A shared component that renders the standard list of alert configuration options.
 * Valid for Master, Regular, and Custom alerts.
 * 
 * Options included:
 * - Feedback Modes (Sound, Vibrate, Flash)
 * - Alert Style (Notification, Alarm, Both)
 * - Intensity (High, Medium, Ascending)
 * - Sound Picker (Conditional)
 * - Override Do Not Disturb
 * - Active Time Range
 * - Retry Settings
 * - Default Snooze
 */
@Composable
fun CommonAlertSettings(
    config: AlertConfig,
    onConfigChange: (AlertConfig) -> Unit,
    onPickSound: (AlertConfig) -> Unit,
    onTest: () -> Unit,
    // Optional Header Content (e.g., Thresholds)
    headerContent: (@Composable () -> Unit)? = null
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
         // === Test Button ===
         FilledTonalButton(
             onClick = onTest,
             modifier = Modifier.fillMaxWidth(),
             contentPadding = PaddingValues(8.dp)
         ) {
             Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
             Spacer(Modifier.width(8.dp))
             Text("Test Alert")
         }
         
         // === Header (Thresholds/Durations) ===
         headerContent?.invoke()

         // === Feedback Modes (Sound, Vibrate, Flash) ===
         // Source: GlobalAlertSettingsCard.kt
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Modes")
            val modes = listOf("Sound", "Vibrate", "Flash")
            val selectedModes = mutableListOf<String>().apply {
                if (config.soundEnabled) add("Sound")
                if (config.vibrationEnabled) add("Vibrate")
                if (config.flashEnabled) add("Flash")
            }

            ConnectedButtonGroup(
                options = modes,
                selectedOptions = selectedModes,
                multiSelect = true,
                onOptionSelected = { mode ->
                    val newConfig = when(mode) {
                        "Sound" -> config.copy(soundEnabled = !config.soundEnabled)
                        "Vibrate" -> config.copy(vibrationEnabled = !config.vibrationEnabled)
                        "Flash" -> config.copy(flashEnabled = !config.flashEnabled)
                        else -> config
                    }
                    onConfigChange(newConfig)
                },
                label = { Text(it) },
                icon = { mode ->
                    when(mode) {
                         "Sound" -> if(selectedModes.contains(mode)) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.VolumeOff
                         "Vibrate" -> if(selectedModes.contains(mode)) Icons.Default.Vibration else Icons.Default.Smartphone
                         "Flash" -> if(selectedModes.contains(mode)) Icons.Default.FlashOn else Icons.Default.FlashOff
                         else -> null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                unselectedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f), // Transparent-ish on PrimaryContainer
                unselectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        // === Alert Style ===
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Alert Style")
            ConnectedButtonGroup(
                options = AlertDeliveryMode.entries,
                selectedOption = config.deliveryMode,
                onOptionSelected = { onConfigChange(config.copy(deliveryMode = it)) },
                label = { Text(it.displayName, style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.fillMaxWidth(),
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                unselectedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                unselectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        
        // === Intensity ===
         Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Intensity")
            ConnectedButtonGroup(
                options = listOf(VolumeProfile.HIGH, VolumeProfile.MEDIUM, VolumeProfile.ASCENDING),
                selectedOption = if (config.volumeProfile in listOf(VolumeProfile.VIBRATE_ONLY, VolumeProfile.SILENT)) VolumeProfile.MEDIUM else config.volumeProfile,
                onOptionSelected = { onConfigChange(config.copy(volumeProfile = it)) },
                label = { Text(it.displayName, style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.fillMaxWidth(),
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                unselectedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                unselectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(Modifier.height(2.dp))

        // === Sound (Conditional) ===
        AnimatedVisibility(visible = config.soundEnabled) {
            OutlinedCard(
                onClick = { onPickSound(config) },
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f), // Slight background for contrast
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Alert Sound", style = MaterialTheme.typography.labelMedium)
                        Text(
                            if (config.customSoundUri == null) "Default System Sound" else "Custom Sound Selected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold 
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }

        // === Override DND ===
         Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.DoNotDisturb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Text(
                "Override Do Not Disturb",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            StyledSwitch(
                checked = config.overrideDND,
                onCheckedChange = { onConfigChange(config.copy(overrideDND = it)) }
            )
        }

        // === Time Range ===
        TimeRangeSettings(
            enabled = config.timeRangeEnabled,
            startHour = config.activeStartHour,
            endHour = config.activeEndHour,
            onEnabledChange = { onConfigChange(config.copy(timeRangeEnabled = it)) },
            onStartChange = { onConfigChange(config.copy(activeStartHour = it)) },
            onEndChange = { onConfigChange(config.copy(activeEndHour = it)) }
        )

        // === Retry ===
        RetrySettings(
            enabled = config.retryEnabled,
            intervalMinutes = config.retryIntervalMinutes,
            retryCount = config.retryCount,
            onEnabledChange = { onConfigChange(config.copy(retryEnabled = it)) },
            onIntervalChange = { onConfigChange(config.copy(retryIntervalMinutes = it)) },
            onCountChange = { onConfigChange(config.copy(retryCount = it)) }
        )
        
        // === Snooze ===
        DurationSlider(
            label = "Default Snooze",
            value = config.defaultSnoozeMinutes,
            range = 5..60,
            stepSize = 5,
            onValueChange = { onConfigChange(config.copy(defaultSnoozeMinutes = it)) }
        )
    }
}
