package tk.glucodata.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import tk.glucodata.Applic
import tk.glucodata.R
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager

// Alert enable/disable + threshold display + snooze cancel. Threshold *editing*
// stays on the phone for now; configs are shared via AlertRepository.
@Composable
fun AlertsScreen() {
    val isMmol = remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    var revision by remember { mutableIntStateOf(0) }

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.alarms),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(AlertType.settingsEntries) { type ->
                val config = remember(type, revision) {
                    runCatching { AlertRepository.loadConfig(type) }.getOrNull()
                } ?: return@items
                val snoozed = remember(type, revision) {
                    runCatching { SnoozeManager.isSnoozed(type) }.getOrDefault(false)
                }
                var checked by remember(type, revision) { mutableStateOf(config.enabled) }
                SwitchButton(
                    checked = checked,
                    onCheckedChange = { on ->
                        checked = on
                        runCatching { AlertRepository.saveConfig(config.copy(enabled = on)) }
                    },
                    label = { Text(stringResource(type.nameResId)) },
                    secondaryLabel = config.threshold?.let { threshold ->
                        {
                            Text(
                                if (isMmol) {
                                    String.format(java.util.Locale.US, "%.1f", threshold)
                                } else {
                                    threshold.toInt().toString()
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (snoozed) {
                    Button(
                        onClick = {
                            runCatching { SnoozeManager.clearSnooze(type) }
                            revision++
                        },
                        label = { Text(stringResource(R.string.cancel)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
