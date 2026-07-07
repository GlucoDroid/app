package tk.glucodata.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import tk.glucodata.Applic
import tk.glucodata.MessageSender
import tk.glucodata.R
import tk.glucodata.drivers.ManagedCalibration

// Single conversion point, same constant as CurrentDisplaySource.
private const val MGDL_PER_MMOLL = 18.0182f

internal fun findCalibratableDriver() = ManagedCalibration.findCalibratableDriver()

// Fingerstick calibration sent to the driver that owns the BLE connection:
// applied locally in standalone mode, relayed to the phone over /calibrate
// in companion mode.
@Composable
fun CalibrationScreen(onDone: () -> Unit) {
    val isMmol = remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    val hasLocalDriver = remember { findCalibratableDriver() != null }
    val canRelay = remember { MessageSender.isWearTransportAvailable() && MessageSender.getMessageSender() != null }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    val step = if (isMmol) 0.1f else 1f
    val min = if (isMmol) 2.2f else 40f
    val max = if (isMmol) 27.7f else 500f
    var value by remember { mutableFloatStateOf(if (isMmol) 5.5f else 100f) }
    var rotaryAccum by remember { mutableStateOf(0f) }
    var sending by remember { mutableStateOf(false) }
    var resultOk by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun format(v: Float) =
        if (isMmol) String.format(java.util.Locale.US, "%.1f", v) else v.roundToInt().toString()

    ScreenScaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .onRotaryScrollEvent { event ->
                    rotaryAccum += event.verticalScrollPixels
                    if (abs(rotaryAccum) > 20f) {
                        value = (value + if (rotaryAccum > 0) step else -step).coerceIn(min, max)
                        rotaryAccum = 0f
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!hasLocalDriver && !canRelay) {
                // No locally connected calibratable sensor and no phone link.
                Text(
                    text = stringResource(R.string.no_sensor_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }

            Text(
                text = stringResource(R.string.calibrate_action),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                Button(
                    onClick = { value = (value - step).coerceIn(min, max) },
                    label = { Text("−") },
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = format(value),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(
                    onClick = { value = (value + step).coerceIn(min, max) },
                    label = { Text("+") },
                    modifier = Modifier.size(40.dp),
                )
            }
            Button(
                enabled = !sending,
                onClick = {
                    sending = true
                    resultOk = null
                    val mgdl = if (isMmol) (value * MGDL_PER_MMOLL).roundToInt() else value.roundToInt()
                    scope.launch(Dispatchers.IO) {
                        val ok = if (hasLocalDriver) {
                            ManagedCalibration.applyFingerstickCalibration(mgdl)
                        } else {
                            // Companion mode: the phone owns the connection.
                            runCatching { MessageSender.sendcalibrate(mgdl) }.isSuccess
                        }
                        resultOk = ok
                        sending = false
                        if (ok) onDone()
                    }
                },
                label = { Text(stringResource(R.string.calibrate_action)) },
            )
            resultOk?.let { ok ->
                Text(
                    text = if (ok) stringResource(R.string.calibrated) else stringResource(R.string.error),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
