// JugglucoNG — Ottai Setup Wizard
//
// Two phases: (1) phone-number SMS sign-in to Ottai cloud (skipped if already
// signed in), (2) bind a sensor by cloud id and connect by Android BLE address.
// A read-only "Validate" button runs the no-risk validateDeviceByMacV2 probe
// (confirms materials decrypt) before the committing bind, which activates
// cloud-side and stores materials. BLE activation stays gated inside the driver.

package tk.glucodata.ui.setup

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.drivers.ottai.OttaiCloudClient
import tk.glucodata.drivers.ottai.OttaiConstants
import tk.glucodata.drivers.ottai.OttaiNfc
import tk.glucodata.drivers.ottai.OttaiRegistry
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.rememberBleScanner
import java.util.UUID

// ENTRY: choose sign-in vs import. LOGIN: phone/SMS. SENSOR: pick from the account's
// sensor list (or scan/enter a code), Advanced holds the RE/debug tools. Then connect.
private enum class OttaiSetupStep { ENTRY, LOGIN, SENSOR, CONNECTING, SUCCESS }
private const val OTTAI_OFFICIAL_SCAN_DURATION_MS = 10_000L
private const val OTTAI_OFFICIAL_RSSI_THRESHOLD = -70

/**
 * Fetch + decrypt the per-sensor materials for a MAC: prefer locally-saved ones, else
 * validate-by-mac against the cloud (requires being signed in) and persist. Returns
 * null if neither yields a usable auth-key set.
 */
private fun fetchOttaiMaterials(context: Context, mac: String): OttaiRegistry.DeviceMaterials? {
    val canonical = OttaiConstants.canonicalSensorId(mac).ifEmpty { return null }
    OttaiRegistry.loadMaterials(context, canonical).takeIf { it.authKeys != null }?.let { return it }
    val resp = OttaiCloudClient.validateByMac(context, canonical) ?: return null
    val m = OttaiCloudClient.toMaterials(context, canonical, resp)?.takeIf { it.authKeys != null } ?: return null
    OttaiRegistry.ensureSensorRecord(context, canonical, OttaiConstants.macWithColons(canonical), OttaiConstants.DEFAULT_DISPLAY_NAME)
    OttaiRegistry.saveMaterials(context, canonical, m)
    return m
}

/**
 * Add + connect a sensor whose materials are already saved. The Ottai BLE address is
 * the MAC itself, so no separate BLE step is needed; a never-activated sensor will
 * auto-activate on first connect inside the driver. Returns true if a connect started.
 */
private fun connectOttaiSensor(context: Context, mac: String): Boolean {
    val canonical = OttaiConstants.canonicalSensorId(mac).ifEmpty { return false }
    if (OttaiRegistry.loadMaterials(context, canonical).authKeys == null) return false
    val ble = OttaiConstants.normalizeBleAddress(
        OttaiRegistry.findRecord(context, canonical)?.address, allowPlain = false,
    ) ?: OttaiConstants.macWithColons(canonical)
    return OttaiRegistry.addSensor(context, canonical, ble, OttaiConstants.DEFAULT_DISPLAY_NAME, connectNow = true) != null
}

private data class OttaiWizardConnectResult(
    val connecting: Boolean,
    val usedSavedMaterials: Boolean = false,
)

private data class OttaiScanCandidate(
    val address: String,
    val displayName: String,
    val rssi: Int,
    val advertisesAuth: Boolean,
    val advertisesCgm: Boolean,
    val advertisesDeviceInfo: Boolean,
    val nameLooksOttai: Boolean,
    val nameLooksCgm: Boolean,
    val serviceSummary: String,
) {
    val isLikelyOttai: Boolean
        get() = advertisesAuth || nameLooksOttai || (advertisesCgm && advertisesDeviceInfo)

    val isPossibleCgm: Boolean
        get() = isLikelyOttai || advertisesCgm || nameLooksCgm
}

private data class OttaiScanStats(
    val candidate: OttaiScanCandidate,
    val totalHits: Int,
    val strongHits: Int,
    val bestRssi: Int,
) {
    val isStableOfficialCandidate: Boolean
        get() = strongHits > 0 && strongHits * 2 > totalHits
}

private fun loadSavedOrBoundOttaiMaterials(
    context: Context,
    canonical: String,
): OttaiRegistry.DeviceMaterials? {
    val saved = OttaiRegistry.loadMaterials(context, canonical)
    if (saved.authKeys != null) return saved

    val resp = OttaiCloudClient.getBindDevice(context) ?: return null
    val boundId = OttaiConstants.canonicalSensorId(resp.mac)
    if (!OttaiConstants.matchesCanonicalOrKnownNativeAlias(boundId, canonical)) return null
    val materials = OttaiCloudClient.toMaterials(context, boundId, resp) ?: return null
    OttaiRegistry.saveMaterials(context, boundId, materials)
    return materials
}

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
    var signedIn by remember { mutableStateOf(alreadySignedIn) }
    var step by remember { mutableStateOf(if (alreadySignedIn) OttaiSetupStep.SENSOR else OttaiSetupStep.ENTRY) }

    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf("") }
    var cloudId by remember { mutableStateOf("") }
    var bleAddress by remember { mutableStateOf("") }
    var deviceVersion by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    // The account's sensors (current + past); null = not loaded yet, empty = none.
    var devices by remember { mutableStateOf<List<OttaiCloudClient.DeviceSummary>?>(null) }
    var devicesLoading by remember { mutableStateOf(false) }
    // Locally-saved sensors (imported or fetched) that can connect with no network.
    var savedSensors by remember { mutableStateOf<List<OttaiRegistry.SensorRecord>>(emptyList()) }
    var savedRefresh by remember { mutableStateOf(0) }

    // When signed in and on the sensor step, pull the account's sensor list once.
    LaunchedEffect(signedIn, step) {
        if (signedIn && step == OttaiSetupStep.SENSOR && devices == null && !devicesLoading) {
            devicesLoading = true
            val list = withContext(Dispatchers.IO) {
                runCatching { OttaiCloudClient.listDevices(context) }
                    .onFailure { Log.w(tag, "listDevices: ${it.message}") }.getOrDefault(emptyList())
            }
            devices = list
            devicesLoading = false
        }
    }
    // Refresh the locally-saved list whenever we land on the sensor step (e.g. after import).
    LaunchedEffect(step, savedRefresh) {
        if (step == OttaiSetupStep.SENSOR) {
            savedSensors = withContext(Dispatchers.IO) {
                OttaiRegistry.persistedRecords(context)
                    .filter { OttaiRegistry.loadMaterials(context, it.sensorId).authKeys != null }
            }
        }
    }

    // Never leave debug NFC-dump mode armed (it would swallow normal/Libre taps).
    DisposableEffect(Unit) {
        onDispose { OttaiNfc.dumpMode = false; OttaiNfc.onResult = null }
    }

    // Save the decrypted per-sensor materials to a file (portable to any device).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                val id = OttaiConstants.canonicalSensorId(cloudId)
                val json = OttaiRegistry.exportJson(context, id)
                    ?: return@withContext context.getString(R.string.ottai_save_nothing)
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
                    .fold({ context.getString(R.string.ottai_save_ok) }, { "Save failed: ${it.message}" })
            }
            status = msg
        }
    }
    // Load materials from a file and connect — no server/login needed on this device.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val id = withContext(Dispatchers.IO) {
                val json = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull() ?: return@withContext null
                // Register the sensor but DON'T auto-connect — land on the sensor list so
                // the user picks when to connect (no surprise fake "connecting" screen).
                OttaiRegistry.importJson(context, json)?.also { sid ->
                    OttaiRegistry.addSensor(
                        context, sid,
                        OttaiRegistry.findRecord(context, sid)?.address.orEmpty(),
                        connectNow = false,
                    )
                }
            }
            if (id != null) {
                cloudId = id
                savedRefresh += 1
                step = OttaiSetupStep.SENSOR
                status = context.getString(R.string.ottai_creds_loaded)
            } else {
                status = context.getString(R.string.ottai_import_failed)
            }
        }
    }

    BackHandler {
        when (step) {
            OttaiSetupStep.ENTRY -> onDismiss()
            OttaiSetupStep.LOGIN -> step = OttaiSetupStep.ENTRY
            OttaiSetupStep.SENSOR -> if (alreadySignedIn) onDismiss() else step = OttaiSetupStep.ENTRY
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
                OttaiSetupStep.ENTRY -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(ui.horizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
                ) {
                    Text(stringResource(R.string.ottai_setup_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.ottai_entry_blurb),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OttaiEntryCard(
                        title = stringResource(R.string.ottai_entry_signin_title),
                        subtitle = stringResource(R.string.ottai_entry_signin_desc),
                        onClick = { status = ""; step = OttaiSetupStep.LOGIN },
                    )
                    OttaiEntryCard(
                        title = stringResource(R.string.ottai_entry_import_title),
                        subtitle = stringResource(R.string.ottai_entry_import_desc),
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                    )
                    // Skip sign-in: go straight to the sensor screen (manual entry + Advanced).
                    TextButton(
                        onClick = { status = ""; step = OttaiSetupStep.SENSOR },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ottai_entry_manual)) }
                    if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error)
                }

                OttaiSetupStep.LOGIN -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(ui.horizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
                ) {
                    Text(stringResource(R.string.ottai_login_title), style = MaterialTheme.typography.titleLarge)
                    var usePassword by remember { mutableStateOf(false) }
                    var password by remember { mutableStateOf("") }
                    // SMS code (CN app) vs account + password (global app).
                    ConnectedButtonGroup(
                        options = listOf(false, true),
                        selectedOption = usePassword,
                        onOptionSelected = { usePassword = it; status = "" },
                        label = { pw -> Text(stringResource(if (pw) R.string.ottai_login_password else R.string.ottai_login_sms)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it.trim() },
                        label = { Text(stringResource(if (usePassword) R.string.ottai_account_hint else R.string.ottai_phone_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (usePassword) KeyboardType.Email else KeyboardType.Phone,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (!usePassword) {
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
                                        status = context.getString(R.string.ottai_code_sent, rid.takeLast(6))
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
                                    if (ok) { signedIn = true; step = OttaiSetupStep.SENSOR }
                                    else status = context.getString(R.string.ottai_login_fail) +
                                        OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() +
                                        context.getString(R.string.ottai_login_request_suffix, requestId.takeLast(6))
                                }
                            },
                            enabled = !busy && code.isNotBlank() && requestId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.ottai_login_button)) }
                    } else {
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            label = { Text(stringResource(R.string.ottai_password_hint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                busy = true; status = ""
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching { OttaiCloudClient.passwordLogin(context, phone, password)?.ok == true }
                                            .onFailure { Log.w(tag, "passwordLogin: ${it.message}") }.getOrDefault(false)
                                    }
                                    busy = false
                                    if (ok) { signedIn = true; step = OttaiSetupStep.SENSOR }
                                    else status = context.getString(R.string.ottai_login_fail) +
                                        OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                                }
                            },
                            enabled = !busy && phone.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.ottai_login_button)) }
                    }

                    if (busy) CircularProgressIndicator()
                    if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error)
                }

                OttaiSetupStep.SENSOR -> {
                    // Fetch+decrypt materials for a MAC, then add+connect. The Ottai BLE
                    // address is the MAC, and a virgin sensor auto-activates on first
                    // connect — so this single action covers the whole happy path.
                    val startConnect: (String) -> Unit = startConnect@{ mac ->
                        if (busy) return@startConnect
                        val canonical = OttaiConstants.canonicalSensorId(mac)
                        cloudId = canonical
                        busy = true; status = ""
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    fetchOttaiMaterials(context, canonical) != null &&
                                        connectOttaiSensor(context, canonical)
                                }.onFailure { Log.w(tag, "connect: ${it.message}") }.getOrDefault(false)
                            }
                            busy = false
                            if (ok) step = OttaiSetupStep.CONNECTING
                            else status = context.getString(R.string.ottai_connect_saved_fail) +
                                OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(ui.horizontalPadding),
                        verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
                    ) {
                        Text(stringResource(R.string.ottai_choose_sensor), style = MaterialTheme.typography.titleLarge)

                        if (signedIn) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.ottai_already_signed_in),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { OttaiCloudClient.logout(context) }
                                        signedIn = false
                                        devices = null
                                        status = ""
                                        step = OttaiSetupStep.ENTRY
                                    }
                                }) { Text(stringResource(R.string.ottai_sign_out)) }
                            }
                        }

                        // Pickable sensors: locally-saved (offline-ready) first, then the
                        // account list. Tap a row to fetch materials + connect.
                        val savedIds = savedSensors.map { it.sensorId }.toSet()
                        val accountOnly = (devices ?: emptyList())
                            .filter { OttaiConstants.canonicalSensorId(it.mac) !in savedIds }
                        if (savedSensors.isNotEmpty() || accountOnly.isNotEmpty() || devicesLoading) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                Column {
                                    savedSensors.forEach { rec ->
                                        OttaiSensorRow(
                                            title = rec.displayName.ifBlank { OttaiConstants.macWithColons(rec.sensorId) },
                                            subtitle = stringResource(R.string.ottai_sensor_saved) + " · " + rec.sensorId,
                                            active = true,
                                            enabled = !busy,
                                            onClick = { startConnect(rec.sensorId) },
                                        )
                                        HorizontalDivider()
                                    }
                                    accountOnly.forEach { d ->
                                        val cid = OttaiConstants.canonicalSensorId(d.mac)
                                        OttaiSensorRow(
                                            title = d.serialNo.ifBlank { OttaiConstants.macWithColons(cid) },
                                            subtitle = stringResource(
                                                if (d.isActive) R.string.ottai_sensor_active else R.string.ottai_sensor_past,
                                            ) + " · " + cid,
                                            active = d.isActive,
                                            enabled = !busy,
                                            onClick = { startConnect(d.mac) },
                                        )
                                        HorizontalDivider()
                                    }
                                    if (devicesLoading) Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator()
                                        Text(stringResource(R.string.ottai_sensors_loading), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            Text(stringResource(R.string.ottai_add_by_code), style = MaterialTheme.typography.titleMedium)
                        } else if (signedIn) {
                            Text(
                                stringResource(R.string.ottai_no_account_sensors),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        OutlinedTextField(
                            value = cloudId, onValueChange = { cloudId = OttaiConstants.extractMacFromQr(it) ?: it.trim() },
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
                                    onScanResult = { raw -> OttaiConstants.extractMacFromQr(raw)?.let { cloudId = it } },
                                )
                            }
                        }
                        Button(
                            onClick = { startConnect(cloudId) },
                            enabled = !busy && OttaiConstants.looksLikeMac(cloudId),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.ottai_connect_saved)) }

                        if (busy) CircularProgressIndicator()
                        if (status.isNotBlank()) Text(status)

                        // ---- Advanced: RE / debug tools (off the main path) ----
                        HorizontalDivider()
                        OutlinedButton(
                            onClick = { showAdvanced = !showAdvanced },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(if (showAdvanced) R.string.ottai_advanced_hide else R.string.ottai_advanced)) }

                        if (showAdvanced) {
                            // BLE address override (defaults to the MAC, so this is rarely
                            // needed). The full discovery scan is behind a further toggle.
                            OutlinedTextField(
                                value = bleAddress, onValueChange = { bleAddress = it.trim() },
                                label = { Text(stringResource(R.string.ottai_ble_address_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            var showBleScan by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = { showBleScan = !showBleScan },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (showBleScan) stringResource(R.string.ottai_hide_ble_scan) else stringResource(R.string.ottai_search_ble)) }
                            if (showBleScan) {
                                OttaiBleScanPanel(
                                    ui = ui,
                                    selectedAddress = bleAddress,
                                    onAddressSelected = { address ->
                                        bleAddress = address
                                        status = context.getString(R.string.ottai_ble_scan_selected, address)
                                    },
                                )
                            }
                            if (deviceVersion.isNotBlank()) {
                                Text(
                                    stringResource(R.string.ottai_device_version_label, deviceVersion),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Read-only validate: fetch + decrypt + persist materials (and version).
                            OutlinedButton(
                                onClick = {
                                    busy = true; status = ""
                                    scope.launch {
                                        val fetchedVersion = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val id = OttaiConstants.canonicalSensorId(cloudId)
                                                val resp = OttaiCloudClient.validateByMac(context, id) ?: return@runCatching null
                                                val m = OttaiCloudClient.toMaterials(context, id, resp) ?: return@runCatching null
                                                if (m.authKeys == null) return@runCatching null
                                                OttaiRegistry.ensureSensorRecord(context, id, bleAddress, OttaiConstants.DEFAULT_DISPLAY_NAME)
                                                OttaiRegistry.saveMaterials(context, id, m)
                                                m.deviceVersion
                                            }.onFailure { Log.w(tag, "validate: ${it.message}") }.getOrNull()
                                        }
                                        busy = false
                                        status = if (fetchedVersion != null) {
                                            deviceVersion = fetchedVersion
                                            context.getString(R.string.ottai_validate_ok)
                                        } else {
                                            context.getString(R.string.ottai_validate_fail) +
                                                OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                                        }
                                    }
                                },
                                enabled = !busy && OttaiConstants.looksLikeMac(cloudId),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.ottai_validate_readonly)) }

                            // Read-only ISO15693/MifareUltralight memory dump (no writes).
                            OutlinedButton(
                                onClick = {
                                    status = context.getString(R.string.ottai_nfc_dump_armed)
                                    OttaiNfc.onResult = { dump ->
                                        OttaiNfc.dumpMode = false
                                        OttaiNfc.onResult = null
                                        scope.launch { status = dump.take(1800) }
                                    }
                                    OttaiNfc.dumpMode = true
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.ottai_nfc_dump)) }

                            OutlinedButton(
                                onClick = { exportLauncher.launch("ottai_${OttaiConstants.canonicalSensorId(cloudId)}.json") },
                                enabled = !busy && OttaiConstants.looksLikeMac(cloudId),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.ottai_save_credentials)) }

                            // Import a saved credentials file (also on the entry screen, but
                            // that's unreachable once signed in — keep it available here too).
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.ottai_load_credentials)) }

                            // Explicit activation (the irreversible CgmActivate write). The
                            // main path activates automatically on a virgin sensor's first
                            // connect; this re-fires it manually if needed.
                            OutlinedButton(
                                onClick = {
                                    val canonical = OttaiConstants.canonicalSensorId(cloudId)
                                    scope.launch {
                                        val fired = withContext(Dispatchers.IO) {
                                            OttaiRegistry.requestActivation(context, canonical)
                                        }
                                        status = context.getString(
                                            if (fired) R.string.ottai_activating else R.string.ottai_activate_armed,
                                        )
                                    }
                                },
                                enabled = !busy && OttaiConstants.looksLikeMac(cloudId),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.ottai_activate)) }

                            // Cloud bind (registers the sensor to the account, then connects).
                            OutlinedButton(
                                onClick = {
                                    busy = true; status = ""
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val userId = OttaiRegistry.loadUserId(context)
                                                val canonical = OttaiConstants.canonicalSensorId(cloudId)
                                                val ble = OttaiConstants.normalizeBleAddress(bleAddress, allowPlain = true)
                                                    ?: OttaiConstants.normalizeBleAddress(
                                                        OttaiRegistry.findRecord(context, canonical)?.address,
                                                        allowPlain = false,
                                                    ) ?: OttaiConstants.macWithColons(canonical)
                                                val resp = OttaiCloudClient.bind(context, canonical, deviceVersion, userId)
                                                val materials = if (resp != null) {
                                                    OttaiCloudClient.toMaterials(context, canonical, resp)
                                                } else {
                                                    loadSavedOrBoundOttaiMaterials(context, canonical)
                                                } ?: return@runCatching null
                                                OttaiRegistry.saveMaterials(context, canonical, materials)
                                                OttaiRegistry.addSensor(
                                                    context = context,
                                                    sensorId = canonical,
                                                    address = ble,
                                                    displayName = OttaiConstants.DEFAULT_DISPLAY_NAME,
                                                    connectNow = ble.isNotBlank(),
                                                ) ?: return@runCatching null
                                                OttaiWizardConnectResult(
                                                    connecting = ble.isNotBlank(),
                                                    usedSavedMaterials = resp == null,
                                                )
                                            }.onFailure { Log.w(tag, "bind: ${it.message}") }.getOrNull()
                                        }
                                        busy = false
                                        if (result == null) {
                                            status = context.getString(R.string.ottai_bind_fail)
                                            OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { status += "\n$it" }
                                        } else if (result.connecting) {
                                            step = OttaiSetupStep.CONNECTING
                                        } else {
                                            status = context.getString(
                                                if (result.usedSavedMaterials) R.string.ottai_saved_needs_ble else R.string.ottai_bound_needs_ble,
                                            )
                                        }
                                    }
                                },
                                enabled = !busy && OttaiConstants.looksLikeMac(cloudId) && deviceVersion.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(
                                    if (OttaiConstants.looksLikeBleAddress(bleAddress)) R.string.ottai_bind_connect
                                    else R.string.ottai_bind_save,
                                ))
                            }
                        }
                    }
                }

                OttaiSetupStep.CONNECTING -> {
                    LaunchedScreenAdvance { step = OttaiSetupStep.SUCCESS }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SensorSetupConnectingScreen(ui = ui, sensorLabel = cloudId.ifBlank { null })
                    }
                }

                OttaiSetupStep.SUCCESS -> {
                    LaunchedScreenComplete(onComplete)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SensorSetupSuccessScreen(ui = ui, sensorLabel = cloudId.ifBlank { null })
                    }
                }
            }
        }
    }
}

@Composable
private fun OttaiSensorRow(
    title: String,
    subtitle: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun OttaiEntryCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OttaiBleScanPanel(
    ui: WizardUiMetrics,
    selectedAddress: String,
    onAddressSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val scanner = rememberBleScanner()
    var scanStats by remember { mutableStateOf<Map<String, OttaiScanStats>>(emptyMap()) }
    var scanActive by remember { mutableStateOf(false) }
    var scanPermissionGranted by remember { mutableStateOf(hasBleScanPermissions(context)) }
    var bluetoothEnabled by remember { mutableStateOf(scanner.isBluetoothEnabled()) }
    var scanRetryKey by remember { mutableStateOf(0) }
    var scanError by remember { mutableStateOf<BleDeviceScanner.ScanStartError?>(null) }
    var requestedPermissionOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        scanPermissionGranted = hasBleScanPermissions(context)
        bluetoothEnabled = scanner.isBluetoothEnabled()
        scanError = null
        scanRetryKey += 1
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        bluetoothEnabled = scanner.isBluetoothEnabled()
        scanError = null
        scanRetryKey += 1
    }

    val requestScanPermission = {
        val required = requiredBleScanPermissions()
        if (required.isEmpty()) {
            scanPermissionGranted = true
            scanRetryKey += 1
        } else {
            permissionLauncher.launch(required)
        }
    }

    LaunchedEffect(Unit) {
        if (!scanPermissionGranted && !requestedPermissionOnce) {
            requestedPermissionOnce = true
            requestScanPermission()
        }
    }

    DisposableEffect(scanPermissionGranted, bluetoothEnabled, scanRetryKey) {
        if (!scanPermissionGranted || !bluetoothEnabled) {
            scanner.stopScan()
            scanStats = emptyMap()
            scanActive = false
            return@DisposableEffect onDispose { scanner.stopScan() }
        }

        scanStats = emptyMap()
        scanError = null
        scanActive = true
        scanner.startScan(
            serviceUuids = listOf(OttaiConstants.SERVICE_CGM),
            onResult = { result ->
                val candidate = ottaiCandidateFromScan(result, assumeCgmService = true) ?: return@startScan
                val previous = scanStats[candidate.address]
                val totalHits = (previous?.totalHits ?: 0) + 1
                val strongHits = (previous?.strongHits ?: 0) +
                    if (candidate.rssi >= OTTAI_OFFICIAL_RSSI_THRESHOLD) 1 else 0
                val bestRssi = maxOf(previous?.bestRssi ?: candidate.rssi, candidate.rssi)
                val previousCandidate = previous?.candidate
                val mergedCandidate = candidate.copy(
                    displayName = candidate.displayName.ifBlank { previousCandidate?.displayName.orEmpty() },
                    rssi = bestRssi,
                    advertisesAuth = candidate.advertisesAuth || (previousCandidate?.advertisesAuth == true),
                    advertisesCgm = candidate.advertisesCgm || (previousCandidate?.advertisesCgm == true),
                    advertisesDeviceInfo = candidate.advertisesDeviceInfo ||
                        (previousCandidate?.advertisesDeviceInfo == true),
                    nameLooksOttai = candidate.nameLooksOttai || (previousCandidate?.nameLooksOttai == true),
                    nameLooksCgm = candidate.nameLooksCgm || (previousCandidate?.nameLooksCgm == true),
                    serviceSummary = mergeOttaiServiceSummary(previousCandidate?.serviceSummary, candidate.serviceSummary),
                )
                scanStats = scanStats + (
                    candidate.address to OttaiScanStats(
                        candidate = mergedCandidate,
                        totalHits = totalHits,
                        strongHits = strongHits,
                        bestRssi = bestRssi,
                    )
                )
            },
            onError = { error ->
                scanError = error
                scanActive = false
                when (error) {
                    BleDeviceScanner.ScanStartError.NoPermission -> scanPermissionGranted = false
                    BleDeviceScanner.ScanStartError.BluetoothDisabled -> bluetoothEnabled = false
                    else -> Unit
                }
            },
        )
        onDispose { scanner.stopScan() }
    }

    LaunchedEffect(scanPermissionGranted, bluetoothEnabled, scanRetryKey) {
        if (scanPermissionGranted && bluetoothEnabled) {
            delay(OTTAI_OFFICIAL_SCAN_DURATION_MS)
            scanActive = false
            scanner.stopScan()
        }
    }

    val stableDevices = scanStats.values
        .filter { it.isStableOfficialCandidate }
        .sortedWith(
            compareByDescending<OttaiScanStats> { if (it.candidate.isLikelyOttai) 1 else 0 }
                .thenByDescending { if (it.candidate.isPossibleCgm) 1 else 0 }
                .thenByDescending { it.bestRssi },
        )
        .take(8)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (scanActive) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.ottai_ble_scan_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(
                    onClick = {
                        scanError = null
                        scanPermissionGranted = hasBleScanPermissions(context)
                        bluetoothEnabled = scanner.isBluetoothEnabled()
                        scanRetryKey += 1
                    },
                    enabled = !scanActive,
                ) {
                    Text(stringResource(R.string.search_bluetooth))
                }
            }
            Text(
                stringResource(R.string.ottai_ble_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!scanPermissionGranted || !bluetoothEnabled || scanError != null) {
                val messageRes = when {
                    !scanPermissionGranted && Build.VERSION.SDK_INT >= 31 -> R.string.turn_on_nearby_devices_permission
                    !scanPermissionGranted -> R.string.turn_on_location_permission
                    !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> R.string.bluetooth_is_turned_off
                    else -> R.string.nobluetooth
                }
                Text(
                    text = stringResource(messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        when {
                            !scanPermissionGranted -> requestScanPermission()
                            !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> {
                                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            }
                            else -> {
                                scanError = null
                                scanPermissionGranted = hasBleScanPermissions(context)
                                bluetoothEnabled = scanner.isBluetoothEnabled()
                                scanRetryKey += 1
                            }
                        }
                    },
                    modifier = Modifier.height(ui.buttonHeight),
                ) {
                    val buttonRes = when {
                        !scanPermissionGranted -> R.string.permission
                        !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> R.string.enable_bluetooth
                        else -> R.string.search_bluetooth
                    }
                    Text(stringResource(buttonRes))
                }
            } else if (scanActive) {
                Text(
                    stringResource(R.string.looking_for_transmitters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (stableDevices.isEmpty()) {
                Text(
                    stringResource(R.string.ottai_ble_scan_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    stableDevices.forEach { stats ->
                        val device = stats.candidate
                        val selected = selectedAddress.equals(device.address, ignoreCase = true)
                        val labelRes = when {
                            device.isLikelyOttai -> R.string.ottai_ble_scan_likely
                            device.isPossibleCgm -> R.string.ottai_ble_scan_possible
                            else -> R.string.ottai_ble_scan_unknown
                        }
                        ListItem(
                            headlineContent = {
                                Text(device.displayName.ifBlank { stringResource(R.string.unknown) })
                            },
                            supportingContent = {
                                val serviceText = device.serviceSummary.ifBlank { stringResource(R.string.unknown) }
                                Text(
                                    "${stringResource(labelRes)} · ${device.address} · " +
                                        "${stats.strongHits}/${stats.totalHits} >= $OTTAI_OFFICIAL_RSSI_THRESHOLD · " +
                                        "$serviceText",
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                Text(
                                    "${device.rssi} dBm",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            modifier = Modifier.clickable { onAddressSelected(device.address) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun mergeOttaiServiceSummary(previous: String?, current: String): String =
    listOf(previous.orEmpty(), current)
        .flatMap { it.split(' ') }
        .mapNotNull { it.trim().takeIf(String::isNotBlank) }
        .distinct()
        .joinToString(" ")

private fun ottaiCandidateFromScan(result: ScanResult, assumeCgmService: Boolean = false): OttaiScanCandidate? {
    val address = try {
        result.device.address
    } catch (_: SecurityException) {
        null
    } ?: return null

    val record = result.scanRecord
    val recordBytes = record?.bytes
    val services = record?.serviceUuids?.map { it.uuid }.orEmpty()
    val names = linkedSetOf<String>()
    listOf(
        try {
            result.device.name
        } catch (_: SecurityException) {
            null
        },
        record?.deviceName,
        scanRecordLocalName(recordBytes),
    ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .forEach { names.add(it) }

    val advertisesAuth = services.contains(OttaiConstants.SERVICE_AUTH) ||
        scanRecordAdvertises128BitService(recordBytes, OttaiConstants.SERVICE_AUTH)
    val advertisesCgm = assumeCgmService ||
        services.contains(OttaiConstants.SERVICE_CGM) ||
        scanRecordAdvertises16BitService(recordBytes, 0x181F)
    val advertisesDeviceInfo = services.contains(OttaiConstants.SERVICE_DEVICE_INFO) ||
        scanRecordAdvertises16BitService(recordBytes, 0x180A)
    val nameLooksOttai = names.any { it.contains("ottai", ignoreCase = true) }
    val nameLooksCgm = names.any { it.contains("cgm", ignoreCase = true) }

    val serviceTags = mutableListOf<String>()
    if (advertisesAuth) serviceTags += "AUTH"
    if (advertisesCgm) serviceTags += "CGM"
    if (advertisesDeviceInfo) serviceTags += "DIS"
    val serviceDataCount = record?.serviceData?.size ?: 0
    if (serviceDataCount > 0) serviceTags += "sd=$serviceDataCount"
    val manufacturerCount = record?.manufacturerSpecificData?.size() ?: 0
    if (manufacturerCount > 0) serviceTags += "mfg=$manufacturerCount"
    if (serviceTags.isEmpty() && services.isNotEmpty()) serviceTags += "services=${services.size}"

    return OttaiScanCandidate(
        address = address,
        displayName = names.firstOrNull().orEmpty(),
        rssi = result.rssi,
        advertisesAuth = advertisesAuth,
        advertisesCgm = advertisesCgm,
        advertisesDeviceInfo = advertisesDeviceInfo,
        nameLooksOttai = nameLooksOttai,
        nameLooksCgm = nameLooksCgm,
        serviceSummary = serviceTags.joinToString(" "),
    )
}

private fun scanRecordLocalName(scanRecord: ByteArray?): String? {
    if (scanRecord == null) return null
    var offset = 0
    while (offset < scanRecord.size - 1) {
        val len = scanRecord[offset].toInt() and 0xFF
        if (len == 0) break
        val next = offset + len + 1
        if (next > scanRecord.size) break
        val type = scanRecord[offset + 1].toInt() and 0xFF
        if (type == 0x08 || type == 0x09) {
            val start = offset + 2
            if (next > start) {
                return runCatching { String(scanRecord, start, next - start, Charsets.UTF_8) }.getOrNull()
            }
        }
        offset = next
    }
    return null
}

private fun scanRecordAdvertises16BitService(scanRecord: ByteArray?, serviceShortUuid: Int): Boolean {
    if (scanRecord == null) return false
    var offset = 0
    while (offset < scanRecord.size - 1) {
        val len = scanRecord[offset].toInt() and 0xFF
        if (len == 0) break
        val next = offset + len + 1
        if (next > scanRecord.size) break
        val type = scanRecord[offset + 1].toInt() and 0xFF
        if (type == 0x02 || type == 0x03) {
            var uuidOffset = offset + 2
            while (uuidOffset + 1 < next) {
                val uuid = (scanRecord[uuidOffset].toInt() and 0xFF) or
                    ((scanRecord[uuidOffset + 1].toInt() and 0xFF) shl 8)
                if (uuid == serviceShortUuid) return true
                uuidOffset += 2
            }
        }
        offset = next
    }
    return false
}

private fun scanRecordAdvertises128BitService(scanRecord: ByteArray?, serviceUuid: UUID): Boolean {
    if (scanRecord == null) return false
    val littleEndian = serviceUuid.toLittleEndianBytes()
    var offset = 0
    while (offset < scanRecord.size - 1) {
        val len = scanRecord[offset].toInt() and 0xFF
        if (len == 0) break
        val next = offset + len + 1
        if (next > scanRecord.size) break
        val type = scanRecord[offset + 1].toInt() and 0xFF
        if (type == 0x06 || type == 0x07) {
            var uuidOffset = offset + 2
            while (uuidOffset + 15 < next) {
                var same = true
                for (i in littleEndian.indices) {
                    if (scanRecord[uuidOffset + i] != littleEndian[i]) {
                        same = false
                        break
                    }
                }
                if (same) return true
                uuidOffset += 16
            }
        }
        offset = next
    }
    return false
}

private fun UUID.toLittleEndianBytes(): ByteArray {
    val text = toString().replace("-", "")
    val bigEndian = text.chunked(2).map { it.toInt(16).toByte() }
    return bigEndian.asReversed().toByteArray()
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
