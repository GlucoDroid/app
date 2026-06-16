// JugglucoNG — Ottai Setup Wizard
//
// Two phases: (1) phone-number SMS sign-in to Ottai cloud (skipped if already
// signed in), (2) bind a sensor by MAC. A read-only "Validate" button runs the
// no-risk validateDeviceByMacV2 probe (confirms materials decrypt) before the
// committing "Bind & connect" (which activates cloud-side and stores materials).
// BLE activation of the sensor stays gated inside the driver.

package tk.glucodata.ui.setup

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.drivers.ottai.OttaiCloudClient
import tk.glucodata.drivers.ottai.OttaiConstants
import tk.glucodata.drivers.ottai.OttaiRegistry

private enum class OttaiSetupStep { LOGIN, SENSOR, CONNECTING, SUCCESS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OttaiSetupWizard(
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val tag = "OttaiSetupWizard"
    val ui = rememberWizardUiMetrics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val alreadySignedIn = remember { OttaiRegistry.loadAccessToken(context).isNotBlank() }
    var step by remember { mutableStateOf(if (alreadySignedIn) OttaiSetupStep.SENSOR else OttaiSetupStep.LOGIN) }

    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var deviceVersion by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    BackHandler {
        when (step) {
            OttaiSetupStep.LOGIN -> onDismiss()
            OttaiSetupStep.SENSOR -> if (alreadySignedIn) onDismiss() else step = OttaiSetupStep.LOGIN
            else -> step = OttaiSetupStep.SENSOR
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ottai_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        AnimatedContent(targetState = step, modifier = Modifier.padding(padding), label = "OttaiWizard") { s ->
            when (s) {
                OttaiSetupStep.LOGIN -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(ui.horizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
                ) {
                    Text(stringResource(R.string.ottai_login_title), style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it.trim() },
                        label = { Text(stringResource(R.string.ottai_phone_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            busy = true; status = ""
                            scope.launch {
                                val rid = withContext(Dispatchers.IO) {
                                    runCatching { OttaiCloudClient.requestSmsCode(context, phone) }
                                        .onFailure { Log.w(tag, "smsCode: ${it.message}") }.getOrNull()
                                }
                                busy = false
                                if (rid.isNullOrBlank()) status = context.getString(R.string.ottai_login_fail) +
                                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                                else {
                                    requestId = rid
                                    code = "" // force entering the code tied to THIS request
                                    status = "Code sent — enter it below (ref …${rid.takeLast(6)})"
                                }
                            }
                        },
                        enabled = !busy && phone.length >= 6,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ottai_send_code)) }

                    OutlinedTextField(
                        value = code, onValueChange = { code = it.trim() },
                        label = { Text(stringResource(R.string.ottai_code_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            busy = true; status = ""
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { OttaiCloudClient.smsLogin(context, phone, code, requestId)?.ok == true }
                                        .onFailure { Log.w(tag, "smsLogin: ${it.message}") }.getOrDefault(false)
                                }
                                busy = false
                                if (ok) step = OttaiSetupStep.SENSOR
                                else status = context.getString(R.string.ottai_login_fail) +
                                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() +
                                    "\nreq=…${requestId.takeLast(6)}"
                            }
                        },
                        enabled = !busy && code.isNotBlank() && requestId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ottai_login_button)) }

                    if (busy) CircularProgressIndicator()
                    if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error)
                }

                OttaiSetupStep.SENSOR -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(ui.horizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
                ) {
                    if (alreadySignedIn) {
                        Text(stringResource(R.string.ottai_already_signed_in), color = MaterialTheme.colorScheme.primary)
                    }
                    OutlinedTextField(
                        value = mac, onValueChange = { mac = OttaiConstants.extractMacFromQr(it) ?: it.trim() },
                        label = { Text(stringResource(R.string.ottai_sensor_mac_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            InlineQrScannerCard(
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                onScanResult = { raw -> OttaiConstants.extractMacFromQr(raw)?.let { mac = it } },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = deviceVersion, onValueChange = { deviceVersion = it.trim() },
                        label = { Text(stringResource(R.string.ottai_device_version_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedButton(
                        onClick = {
                            busy = true; status = ""
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val resp = OttaiCloudClient.validateByMac(context, mac) ?: return@runCatching false
                                        OttaiCloudClient.toMaterials(context, mac, resp)?.authKeys != null
                                    }.onFailure { Log.w(tag, "validate: ${it.message}") }.getOrDefault(false)
                                }
                                busy = false
                                status = context.getString(
                                    if (ok) R.string.ottai_validate_ok else R.string.ottai_validate_fail,
                                )
                            }
                        },
                        enabled = !busy && OttaiConstants.looksLikeMac(mac),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ottai_validate_readonly)) }

                    Button(
                        onClick = {
                            busy = true; status = ""
                            scope.launch {
                                val sensorId = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val userId = OttaiRegistry.loadUserId(context)
                                        val resp = OttaiCloudClient.bind(context, mac, deviceVersion, userId)
                                            ?: return@runCatching null
                                        val materials = OttaiCloudClient.toMaterials(context, mac, resp)
                                            ?: return@runCatching null
                                        val canonical = OttaiConstants.canonicalSensorId(mac)
                                        OttaiRegistry.saveMaterials(context, canonical, materials)
                                        OttaiRegistry.addSensor(context, canonical, canonical, OttaiConstants.DEFAULT_DISPLAY_NAME, connectNow = true)
                                    }.onFailure { Log.w(tag, "bind: ${it.message}") }.getOrNull()
                                }
                                busy = false
                                if (sensorId == null) {
                                    status = context.getString(R.string.ottai_bind_fail)
                                } else {
                                    step = OttaiSetupStep.CONNECTING
                                }
                            }
                        },
                        enabled = !busy && OttaiConstants.looksLikeMac(mac) && deviceVersion.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ottai_bind_connect)) }

                    if (busy) CircularProgressIndicator()
                    if (status.isNotBlank()) Text(status)
                }

                OttaiSetupStep.CONNECTING -> {
                    LaunchedScreenAdvance { step = OttaiSetupStep.SUCCESS }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SensorSetupConnectingScreen(ui = ui, sensorLabel = mac.ifBlank { null })
                    }
                }

                OttaiSetupStep.SUCCESS -> {
                    LaunchedScreenComplete(onComplete)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SensorSetupSuccessScreen(ui = ui, sensorLabel = mac.ifBlank { null })
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchedScreenAdvance(onAdvance: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        delay(2000)
        onAdvance()
    }
}

@Composable
private fun LaunchedScreenComplete(onComplete: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        delay(SENSOR_SETUP_SUCCESS_AUTO_ADVANCE_MS)
        onComplete()
    }
}
