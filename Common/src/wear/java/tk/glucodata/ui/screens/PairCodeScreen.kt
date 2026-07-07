package tk.glucodata.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.PhotoScan
import tk.glucodata.R

@Composable
fun PairCodeScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var digit by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf("") }
    var rotaryAccum by remember { mutableStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    var resultOk by remember { mutableStateOf<Boolean?>(null) }
    val canConfirm = code.length >= 3 && !saving

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ScreenScaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 36.dp)
                .onRotaryScrollEvent { event ->
                    rotaryAccum += event.verticalScrollPixels
                    if (abs(rotaryAccum) > 20f) {
                        digit = (digit + if (rotaryAccum > 0) 1 else 9) % 10
                        rotaryAccum = 0f
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.enter_code_manually),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = code.ifEmpty { "---" },
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { digit = (digit + 9) % 10 },
                    label = { Text("-") },
                    modifier = Modifier.size(42.dp),
                )
                Button(
                    onClick = { appendDigit(code, digit)?.let { code = it; resultOk = null } },
                    label = { Text(digit.toString()) },
                    modifier = Modifier.size(52.dp),
                )
                Button(
                    onClick = { digit = (digit + 1) % 10 },
                    label = { Text("+") },
                    modifier = Modifier.size(42.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    enabled = code.isNotEmpty(),
                    onClick = { code = code.dropLast(1) },
                    label = { Text(stringResource(R.string.delete)) },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = code.isNotEmpty(),
                    onClick = { code = "" },
                    label = { Text(stringResource(R.string.clear)) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = canConfirm,
                onClick = {
                    saving = true
                    resultOk = null
                    val submitted = code
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                PhotoScan.applyManualPairingCode(context, submitted)
                            }.getOrDefault(false)
                        }
                        resultOk = ok
                        saving = false
                        if (ok) onDone()
                    }
                },
                label = { Text(stringResource(R.string.confirm)) },
                modifier = Modifier.fillMaxWidth(),
            )
            resultOk?.let { ok ->
                Text(
                    text = if (ok) stringResource(R.string.saved) else stringResource(R.string.invalid_code_format),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun appendDigit(code: String, digit: Int): String? =
    if (code.length < 20) code + digit.toString() else null
