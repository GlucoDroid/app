package tk.glucodata.ui.screens

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import tk.glucodata.NotificationHistorySource
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorStatusPolicy
import tk.glucodata.ui.WearNavigationRow
import tk.glucodata.ui.WearSectionTitle

private const val SENSOR_TICK_MS = 60_000L

private data class SensorRow(val serial: String, val isCurrent: Boolean, val isConnected: Boolean)

private data class SensorDetails(
    val connectionStatus: String,
    val detailedStatus: String,
    val dayText: String,
    val startTimeMs: Long,
    val lastReadingMs: Long,
)

private fun loadSensors(): List<SensorRow> = runCatching {
    val current = ManagedCurrentSensor.get() ?: Natives.lastsensorname()
    val active = Natives.activeSensors()?.toList().orEmpty()
    val connected = SensorBluetooth.mygatts()?.mapNotNull { it.SerialNumber }.orEmpty()
    (active + connected).distinct().map { SensorRow(it, it == current, it in connected) }
}.getOrDefault(emptyList())

private fun loadSensorDetails(row: SensorRow, now: Long): SensorDetails {
    val managed = runCatching { ManagedSensorRuntime.resolveUiSnapshot(row.serial, row.serial) }.getOrNull()
    val nativeStart = if (managed == null) {
        runCatching { Natives.getSensorUiSnapshot(row.serial) }
            .getOrNull()
            ?.takeIf { it.size >= 3 }
            ?.get(2)
            ?.takeIf { it > 0L }
            ?: 0L
    } else {
        0L
    }
    val startTime = managed?.startTimeMs?.takeIf { it > 0L } ?: nativeStart
    val lifecycle = ManagedSensorStatusPolicy.resolveLifecycleSummary(
        startTimeMs = startTime,
        officialEndMs = managed?.officialEndMs ?: 0L,
        expectedEndMs = managed?.expectedEndMs ?: 0L,
        sensorRemainingHours = managed?.sensorRemainingHours ?: -1,
        sensorAgeHours = managed?.sensorAgeHours ?: -1,
        nowMs = now,
    )
    val hasKnownTotal = managed != null && (
        managed.expectedEndMs > startTime ||
            managed.officialEndMs > startTime ||
            (managed.sensorAgeHours >= 0 && managed.sensorRemainingHours >= 0)
        )
    val dayText = when {
        lifecycle.daysText.isEmpty() -> ""
        hasKnownTotal -> lifecycle.daysText
        lifecycle.currentDay > 0 -> lifecycle.currentDay.toString()
        else -> ""
    }
    val managedReading = runCatching {
        ManagedSensorRuntime.resolveCurrentSnapshot(row.serial, Long.MAX_VALUE)?.timeMillis
    }.getOrNull()?.takeIf { it > 0L }
    val lastReading = managedReading ?: runCatching {
        NotificationHistorySource.getDisplayHistory(now - 30L * DateUtils.DAY_IN_MILLIS, false, row.serial)
            .lastOrNull()
            ?.timestamp
    }.getOrNull()?.takeIf { it > 0L } ?: 0L
    return SensorDetails(
        connectionStatus = managed?.connectionStatus?.trim().orEmpty(),
        detailedStatus = managed?.detailedStatus?.trim().orEmpty(),
        dayText = dayText,
        startTimeMs = startTime,
        lastReadingMs = lastReading,
    )
}

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
                val details = remember(row, revision, now / SENSOR_TICK_MS) { loadSensorDetails(row, now) }
                Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    Text(
                        text = row.serial,
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
                    details.dayText.takeIf { it.isNotEmpty() }?.let {
                        SensorDetailRow(stringResource(R.string.days), it)
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
