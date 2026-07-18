package tk.glucodata.ui.screens

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.ManagedCurrentSensor
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.UiRefreshBus
import tk.glucodata.ui.WearNavigationRow
import tk.glucodata.ui.WearSectionTitle

private const val SENSOR_TICK_MS = 60_000L

private data class SensorRow(val serial: String, val isCurrent: Boolean, val isConnected: Boolean)

private fun loadSensors(): List<SensorRow> = runCatching {
    val current = ManagedCurrentSensor.get() ?: Natives.lastsensorname()
    val active = Natives.activeSensors()?.toList().orEmpty()
    val connected = SensorBluetooth.mygatts()?.mapNotNull { it.SerialNumber }.orEmpty()
    (active + connected).distinct().map { SensorRow(it, it == current, it in connected) }
}.getOrDefault(emptyList())

@Composable
fun SensorScreen(onCalibrate: () -> Unit) {
    var revision by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val sensors = remember(revision) { loadSensors() }
    val canCalibrate = remember(revision) {
        findCalibratableDriver() != null ||
            (tk.glucodata.MessageSender.isWearTransportAvailable() &&
                tk.glucodata.MessageSender.getMessageSender() != null)
    }
    val context = LocalContext.current
    val dateFormat = remember(context) { DateFormat.getMediumDateFormat(context) }

    LaunchedEffect(Unit) {
        launch { UiRefreshBus.revision.collect { revision = it; now = System.currentTimeMillis() } }
        while (true) { delay(SENSOR_TICK_MS); now = System.currentTimeMillis() }
    }

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 28.dp),
        ) {
            item { WearSectionTitle(stringResource(R.string.sensor)) }
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
            items(sensors, key = { it.serial }) { row ->
                val details = remember(row, revision, now / SENSOR_TICK_MS) {
                    loadWearSensorPresentation(row.serial, now)
                }
                Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    Text(
                        text = details.serial,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (row.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    val connection = details.connectionStatus.ifEmpty {
                        if (row.isConnected) stringResource(R.string.status_connected) else ""
                    }
                    if (connection.isNotEmpty()) {
                        SensorDetailRow(stringResource(R.string.connection_label), connection)
                    }
                    details.detailedStatus
                        .takeIf { it.isNotEmpty() && !it.equals(connection, ignoreCase = true) }
                        ?.let { SensorDetailRow(stringResource(R.string.status), it) }
                    details.dayValueText.takeIf { it.isNotEmpty() }?.let {
                        SensorDetailRow(
                            stringResource(R.string.wear_sensor_day_label),
                            it,
                        )
                    }
                    details.lifecycleProgress?.let { progress ->
                        val progressColor = when {
                            progress >= 0.95f -> MaterialTheme.colorScheme.error
                            progress >= 0.80f -> MaterialTheme.colorScheme.tertiary
                            else -> androidx.compose.ui.graphics.Color(0xFF66BB6A)
                        }
                        Box(
                            Modifier.fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(5.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                    RoundedCornerShape(3.dp),
                                ),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .background(progressColor, RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                    details.startTimeMs.takeIf { it > 0L }?.let {
                        SensorDetailRow(stringResource(R.string.sensor_started), dateFormat.format(Date(it)))
                    }
                    details.lastReadingMs.takeIf { it > 0L }?.let {
                        val age = DateUtils.getRelativeTimeSpanString(
                            it,
                            now,
                            DateUtils.MINUTE_IN_MILLIS,
                        ).toString()
                        SensorDetailRow(stringResource(R.string.readings), age)
                    }
                }
            }
            if (canCalibrate) {
                item { WearNavigationRow(stringResource(R.string.calibrate_action), onClick = onCalibrate) }
            }
        }
    }
}

@Composable
private fun SensorDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f),
        )
    }
}
