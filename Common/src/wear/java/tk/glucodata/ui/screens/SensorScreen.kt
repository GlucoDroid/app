package tk.glucodata.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import tk.glucodata.ManagedCurrentSensor
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SensorBluetooth

private class SensorRow(val serial: String, val isCurrent: Boolean)

private fun loadSensors(): List<SensorRow> = runCatching {
    val current = ManagedCurrentSensor.get() ?: Natives.lastsensorname()
    val active = Natives.activeSensors()?.toList().orEmpty()
    val connected = SensorBluetooth.mygatts()?.mapNotNull { it.SerialNumber }.orEmpty()
    (active + connected).distinct().map { SensorRow(it, it == current) }
}.getOrDefault(emptyList())

@Composable
fun SensorScreen(onCalibrate: () -> Unit) {
    val sensors = remember { loadSensors() }
    val canCalibrate = remember {
        findCalibratableDriver() != null ||
            (tk.glucodata.MessageSender.isWearTransportAvailable() &&
                tk.glucodata.MessageSender.getMessageSender() != null)
    }

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.sensor),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (sensors.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_sensors_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.wear_libre_nfc_caveat),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sensors) { row ->
                Text(
                    text = row.serial,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (canCalibrate) {
                item {
                    Button(
                        onClick = onCalibrate,
                        label = { Text(stringResource(R.string.calibrate_action)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
