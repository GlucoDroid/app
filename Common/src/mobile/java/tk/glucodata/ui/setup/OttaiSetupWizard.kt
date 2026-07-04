// JugglucoNG — Ottai Setup Wizard
//
// Ottai setup keeps all protocol decisions in the driver/cloud helpers. The UI
// collects account/materials, scans QR/NFC when useful, and starts the managed
// sensor connection; first-use activation is handled during connect.

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Nfc
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
import androidx.navigation.NavController
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
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.rememberBleScanner
import java.util.UUID

// SENSOR is the main setup surface. ACCOUNT_SENSORS is pick-only: a row fills the
// cloud-id field instead of silently connecting.
private enum class OttaiSetupStep { SENSOR, ACCOUNT_SENSORS, REGISTER, CONNECTING, SUCCESS }

/**
 * Cloud account region. CN is the original phone+SMS app (api.ottai.com); Global (Ottai
 * com.ottai.seas, seas.ottai.com) and syai (ru.syai.com) are username/email + password and
 * share the same API. [usesSms] picks the login form.
 */
private enum class OttaiRegion(
    val labelRes: Int,
    val base: String,
    val usesSms: Boolean,
    val webBase: String?,  // website account API (email login + registration); null = no web account flow
) {
    CN(R.string.ottai_region_cn, OttaiConstants.API_BASE, true, null),
    GLOBAL(R.string.ottai_region_global, OttaiConstants.API_BASE_GLOBAL, false, OttaiConstants.WEB_BASE_OTTAI),
    SYAI(R.string.ottai_region_syai, OttaiConstants.API_BASE_SYAI, false, OttaiConstants.WEB_BASE_SYAI),
}
private const val OTTAI_OFFICIAL_SCAN_DURATION_MS = 10_000L
private const val OTTAI_OFFICIAL_RSSI_THRESHOLD = -70

/**
 * Fetch + decrypt the per-sensor materials for a MAC: prefer locally-saved ones, else
 * validate-by-mac against the cloud (requires being signed in) and persist. Returns
 * null if neither yields a usable auth-key set.
 */
private fun fetchOttaiMaterials(
    context: Context,
    mac: String,
    deviceVersion: String? = null,
): OttaiRegistry.DeviceMaterials? {
    val canonical = OttaiConstants.canonicalSensorId(mac).ifEmpty { return null }
    OttaiRegistry.loadMaterials(context, canonical).takeIf { it.authKeys != null }?.let { return it }
    // validate-by-mac works for an unbound sensor, but one we already activated returns
    // AppDevice_AlreadyUsed there. Fall back to getBindDevice — the currently-bound sensor's
    // materials (incl. the cgmDeviceMethodVO method) — without needing to re-bind. Previously-used
    // account sensors can still be recovered by a temporary bind/unbind when listDevices supplied
    // the deviceVersion required by the bind endpoint.
    fun viaValidate(): OttaiRegistry.DeviceMaterials? {
        val resp = OttaiCloudClient.validateByMac(context, canonical) ?: return null
        return OttaiCloudClient.toMaterials(context, canonical, resp)?.takeIf { it.authKeys != null }
    }
    fun viaBound(): OttaiRegistry.DeviceMaterials? {
        val resp = OttaiCloudClient.getBindDevice(context) ?: return null
        val boundId = OttaiConstants.canonicalSensorId(resp.mac)
        if (!OttaiConstants.matchesCanonicalOrKnownNativeAlias(boundId, canonical)) return null
        return OttaiCloudClient.toMaterials(context, boundId, resp)?.takeIf { it.authKeys != null }
    }
    fun viaTemporaryBind(): OttaiRegistry.DeviceMaterials? {
        val version = deviceVersion?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val resp = OttaiCloudClient.bindForMaterials(context, canonical, version) ?: return null
        val boundId = OttaiConstants.canonicalSensorId(resp.mac).ifBlank { canonical }
        if (!OttaiConstants.matchesCanonicalOrKnownNativeAlias(boundId, canonical)) return null
        return OttaiCloudClient.toMaterials(context, canonical, resp)?.takeIf { it.authKeys != null }
    }
    val m = viaValidate() ?: viaBound() ?: viaTemporaryBind() ?: return null
    OttaiRegistry.ensureSensorRecord(context, canonical, OttaiConstants.macWithColons(canonical), OttaiConstants.DEFAULT_DISPLAY_NAME)
    OttaiRegistry.saveMaterials(context, canonical, m)
    return m
}

/**
 * Add + connect a sensor whose materials are already saved. The Ottai BLE address is
 * the MAC itself, so no separate BLE step is needed; a never-activated sensor will
 * auto-activate on first connect inside the driver. Returns true if a connect started.
 */
private fun connectOttaiSensor(context: Context, mac: String, bleAddress: String? = null): Boolean {
    val canonical = OttaiConstants.canonicalSensorId(mac).ifEmpty { return false }
    if (OttaiRegistry.loadMaterials(context, canonical).authKeys == null) return false
    if (!bleAddress.isNullOrBlank()) {
        OttaiRegistry.ensureSensorRecord(
            context,
            canonical,
            bleAddress,
            OttaiConstants.DEFAULT_DISPLAY_NAME,
        )
    }
    val ble = OttaiConstants.normalizeBleAddress(
        OttaiRegistry.findRecord(context, canonical)?.address, allowPlain = false,
    ) ?: OttaiConstants.macWithColons(canonical)
    return OttaiRegistry.addSensor(context, canonical, ble, OttaiConstants.DEFAULT_DISPLAY_NAME, connectNow = true) != null
}

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

private enum class OttaiMaterialState {
    MISSING,
    READY_TO_ACTIVATE,
    WARMING_UP,
    ACTIVE,
    EXPIRED,
    PARTIAL,
}

private fun ottaiMaterialState(
    materials: OttaiRegistry.DeviceMaterials?,
    nowMs: Long = System.currentTimeMillis(),
): OttaiMaterialState {
    if (materials?.authKeys == null) return OttaiMaterialState.MISSING
    if (materials.method.isBlank() || materials.coefficient.isBlank()) return OttaiMaterialState.PARTIAL
    val start = materials.activeTimeMs.takeIf { it > 0L } ?: return OttaiMaterialState.READY_TO_ACTIVATE
    val expires = materials.activeExpireTimeMs.takeIf { it > 0L } ?: OttaiConstants.DEFAULT_ACTIVE_EXPIRE_MS
    if (expires > 0L && nowMs >= start + expires) return OttaiMaterialState.EXPIRED
    val preheat = materials.preheatPeriodMs.takeIf { it > 0L } ?: OttaiConstants.DEFAULT_PREHEAT_PERIOD_MS
    if (preheat > 0L && nowMs < start + preheat) return OttaiMaterialState.WARMING_UP
    return OttaiMaterialState.ACTIVE
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
    var step by remember { mutableStateOf(OttaiSetupStep.SENSOR) }

    var phone by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(OttaiRegion.GLOBAL) }  // default to the non-CN app flow
    var code by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var cloudId by remember { mutableStateOf("") }
    var selectedDeviceVersion by remember { mutableStateOf("") }
    var bleAddress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var currentMaterials by remember { mutableStateOf<OttaiRegistry.DeviceMaterials?>(null) }
    var materialLoading by remember { mutableStateOf(false) }
    var lastAutoFetchId by remember { mutableStateOf("") }
    var materialRefresh by remember { mutableStateOf(0) }
    // The account's sensors (current + past); null = not loaded yet, empty = none.
    var devices by remember { mutableStateOf<List<OttaiCloudClient.DeviceSummary>?>(null) }
    var devicesLoading by remember { mutableStateOf(false) }
    // Locally-saved sensors (imported or fetched) that can connect with no network.
    var savedSensors by remember { mutableStateOf<List<OttaiRegistry.SensorRecord>>(emptyList()) }
    var savedRefresh by remember { mutableStateOf(0) }

    // When signed in and the account picker is relevant, pull the account's sensor list once.
    LaunchedEffect(signedIn, step) {
        if (signedIn && (step == OttaiSetupStep.SENSOR || step == OttaiSetupStep.ACCOUNT_SENSORS) &&
            devices == null && !devicesLoading) {
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
        if (step == OttaiSetupStep.SENSOR || step == OttaiSetupStep.ACCOUNT_SENSORS) {
            savedSensors = withContext(Dispatchers.IO) {
                OttaiRegistry.persistedRecords(context)
                    .filter { OttaiRegistry.loadMaterials(context, it.sensorId).authKeys != null }
            }
        }
    }

    LaunchedEffect(signedIn) {
        if (!signedIn) {
            lastAutoFetchId = ""
            materialLoading = false
        }
    }

    LaunchedEffect(cloudId, signedIn, savedRefresh, materialRefresh) {
        val canonical = OttaiConstants.canonicalSensorId(cloudId)
        if (!OttaiConstants.looksLikeMac(canonical)) {
            currentMaterials = null
            materialLoading = false
            return@LaunchedEffect
        }

        val local = withContext(Dispatchers.IO) {
            OttaiRegistry.loadMaterials(context, canonical).takeIf { it.authKeys != null }
        }
        if (local != null) {
            currentMaterials = local
            if (local.deviceVersion.isNotBlank()) selectedDeviceVersion = local.deviceVersion
            materialLoading = false
            return@LaunchedEffect
        }

        currentMaterials = null
        if (signedIn && lastAutoFetchId != canonical) {
            lastAutoFetchId = canonical
            materialLoading = true
            status = ""
            val fetched = withContext(Dispatchers.IO) {
                runCatching { fetchOttaiMaterials(context, canonical, selectedDeviceVersion) }
                    .onFailure { Log.w(tag, "auto-fetch materials: ${it.message}") }
                    .getOrNull()
            }
            if (OttaiConstants.canonicalSensorId(cloudId) == canonical) {
                currentMaterials = fetched
                if (!fetched?.deviceVersion.isNullOrBlank()) selectedDeviceVersion = fetched?.deviceVersion.orEmpty()
                materialLoading = false
                status = if (fetched != null) "" else context.getString(R.string.ottai_connect_saved_fail) +
                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            }
        } else {
            materialLoading = false
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
                val imported = withContext(Dispatchers.IO) { OttaiRegistry.loadMaterials(context, id) }
                currentMaterials = imported
                selectedDeviceVersion = imported.deviceVersion
                lastAutoFetchId = ""
                savedRefresh += 1
                step = OttaiSetupStep.SENSOR
                status = ""
            } else {
                status = context.getString(R.string.ottai_import_failed)
            }
        }
    }

    BackHandler {
        when (step) {
            OttaiSetupStep.SENSOR -> onDismiss()
            OttaiSetupStep.ACCOUNT_SENSORS -> step = OttaiSetupStep.SENSOR
            OttaiSetupStep.REGISTER -> step = OttaiSetupStep.SENSOR
            else -> step = OttaiSetupStep.SENSOR
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ottai_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        AnimatedContent(targetState = step, modifier = Modifier.padding(padding), label = "OttaiWizard") { s ->
            when (s) {
                OttaiSetupStep.REGISTER -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(ui.horizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
                ) {
                    Text(stringResource(R.string.ottai_register_title), style = MaterialTheme.typography.titleLarge)
                    var email by remember { mutableStateOf("") }
                    var regCode by remember { mutableStateOf("") }
                    var regPassword by remember { mutableStateOf("") }
                    var profileName by remember { mutableStateOf("") }
                    var regRequestId by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = email, onValueChange = { email = it.trim() },
                        label = { Text(stringResource(R.string.ottai_email_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            busy = true; status = ""
                            scope.launch {
                                val rid = withContext(Dispatchers.IO) {
                                    runCatching {
                                        OttaiCloudClient.sendMail(
                                            email.trim(), "SIGN_UP",
                                            region.webBase ?: OttaiConstants.WEB_BASE_OTTAI,
                                        )
                                    }.onFailure { Log.w(tag, "sendMail: ${it.message}") }.getOrNull()
                                }
                                busy = false
                                if (rid.isNullOrBlank()) status = context.getString(R.string.ottai_register_fail) +
                                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                                else { regRequestId = rid; status = context.getString(R.string.ottai_code_sent_email, email) }
                            }
                        },
                        enabled = !busy && email.contains('@'),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ottai_send_code)) }
                    OutlinedTextField(
                        value = regCode, onValueChange = { regCode = it.trim() },
                        label = { Text(stringResource(R.string.ottai_verify_code_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = regPassword, onValueChange = { regPassword = it },
                        label = { Text(stringResource(R.string.ottai_password_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.ottai_password_rule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = profileName, onValueChange = { profileName = it },
                        label = { Text(stringResource(R.string.ottai_profile_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            busy = true; status = ""
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        OttaiCloudClient.signUp(
                                            context, email.trim(), regPassword, profileName.trim(),
                                            regRequestId, regCode.trim(),
                                            region.webBase ?: OttaiConstants.WEB_BASE_OTTAI,
                                        )?.accessToken?.isNotBlank() == true
                                    }.onFailure { Log.w(tag, "signUp: ${it.message}") }.getOrDefault(false)
                                }
                                busy = false
                                if (ok) { signedIn = true; step = OttaiSetupStep.SENSOR }
                                else status = context.getString(R.string.ottai_register_fail) +
                                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                            }
                        },
                        enabled = !busy && regRequestId.isNotBlank() && regCode.isNotBlank() &&
                            regPassword.isNotBlank() && profileName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.ottai_register_button)) }
                    if (busy) CircularProgressIndicator()
                    if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error)
                }

                OttaiSetupStep.SENSOR -> {
                    val materialState = ottaiMaterialState(currentMaterials)
                    val hasSensorCode = OttaiConstants.looksLikeMac(cloudId)
                    val hasMaterials = currentMaterials?.authKeys != null
                    val canConnect = hasSensorCode && (signedIn || hasMaterials)
                    val connectTitleRes = when (materialState) {
                        OttaiMaterialState.READY_TO_ACTIVATE -> R.string.ottai_connect_activate
                        OttaiMaterialState.EXPIRED -> R.string.ottai_connect_expired
                        else -> R.string.ottai_connect_saved
                    }

                    val startConnect: (String) -> Unit = startConnect@{ mac ->
                        if (busy || materialLoading) return@startConnect
                        val canonical = OttaiConstants.canonicalSensorId(mac)
                        cloudId = canonical
                        busy = true; status = ""
                        materialLoading = false
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val materials = fetchOttaiMaterials(context, canonical, selectedDeviceVersion)
                                        ?: return@runCatching null
                                    val explicitBle = OttaiConstants.normalizeBleAddress(bleAddress, allowPlain = false)
                                    val connected = connectOttaiSensor(context, canonical, explicitBle)
                                    materials to connected
                                }.onFailure { Log.w(tag, "connect: ${it.message}") }.getOrNull()
                            }
                            busy = false
                            if (result?.second == true) {
                                currentMaterials = result.first
                                if (result.first.deviceVersion.isNotBlank()) selectedDeviceVersion = result.first.deviceVersion
                                savedRefresh += 1
                                step = OttaiSetupStep.CONNECTING
                            } else {
                                status = context.getString(R.string.ottai_connect_saved_fail) +
                                    OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                            }
                        }
                    }

                    val armNfcRead: () -> Unit = {
                        status = context.getString(R.string.ottai_nfc_dump_armed)
                        OttaiNfc.onResult = { _ ->
                            OttaiNfc.dumpMode = false
                            OttaiNfc.onResult = null
                            scope.launch {
                                savedRefresh += 1
                                status = context.getString(R.string.ottai_nfc_read_ok)
                            }
                        }
                        OttaiNfc.dumpMode = true
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(ui.horizontalPadding),
                        verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
                    ) {
                        if (signedIn) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.ottai_already_signed_in),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { OttaiCloudClient.logout(context) }
                                        signedIn = false
                                        devices = null
                                        status = ""
                                    }
                                }) { Text(stringResource(R.string.ottai_sign_out)) }
                            }

                            OttaiBleScanPanel(
                                ui = ui,
                                selectedAddress = bleAddress,
                                onAddressSelected = { address ->
                                    bleAddress = address
                                    val id = OttaiConstants.canonicalSensorId(address)
                                    if (OttaiConstants.looksLikeMac(id)) {
                                        val shouldRefresh = id != cloudId || currentMaterials?.authKeys == null
                                        cloudId = id
                                        selectedDeviceVersion = ""
                                        lastAutoFetchId = ""
                                        if (shouldRefresh) materialRefresh += 1
                                    }
                                    status = context.getString(R.string.ottai_ble_scan_selected, address)
                                },
                            )
                        } else {
                            Text(stringResource(R.string.ottai_login_title), style = MaterialTheme.typography.titleMedium)
                            ConnectedButtonGroup(
                                options = OttaiRegion.entries.toList(),
                                selectedOption = region,
                                onOptionSelected = { region = it; status = "" },
                                label = { r -> Text(stringResource(r.labelRes)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val useSms = region.usesSms
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it.trim() },
                                label = { Text(stringResource(if (useSms) R.string.ottai_phone_hint else R.string.ottai_account_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (useSms) KeyboardType.Phone else KeyboardType.Email,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (!useSms) Text(
                                stringResource(R.string.ottai_account_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (useSms) {
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
                                                code = ""
                                                status = context.getString(R.string.ottai_code_sent, rid.takeLast(6))
                                            }
                                        }
                                    },
                                    enabled = !busy && phone.length >= 6,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.ottai_send_code)) }
                                OutlinedTextField(
                                    value = code,
                                    onValueChange = { code = it.trim() },
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
                                            if (ok) signedIn = true
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
                                    value = password,
                                    onValueChange = { password = it },
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
                                                runCatching {
                                                    val id = phone.trim()
                                                    val wb = region.webBase
                                                    val r = if (wb != null && id.contains('@'))
                                                        OttaiCloudClient.mailLogin(context, id, password, wb)
                                                    else
                                                        OttaiCloudClient.passwordLogin(context, id, password, region.base)
                                                    r?.accessToken?.isNotBlank() == true
                                                }.onFailure { Log.w(tag, "passwordLogin: ${it.message}") }.getOrDefault(false)
                                            }
                                            busy = false
                                            if (ok) signedIn = true
                                            else status = context.getString(R.string.ottai_login_fail) +
                                                OttaiCloudClient.lastError.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                                        }
                                    },
                                    enabled = !busy && phone.isNotBlank() && password.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.ottai_login_button)) }
                            }

                            if (region.webBase != null && !region.usesSms) {
                                TextButton(
                                    onClick = { status = ""; step = OttaiSetupStep.REGISTER },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.ottai_register_cta)) }
                            }
                        }

                        OttaiSensorMaterialCard(
                            cloudId = cloudId,
                            onCloudIdChange = {
                                val next = OttaiConstants.extractMacFromQr(it) ?: it.trim()
                                if (next != cloudId) {
                                    cloudId = next
                                    selectedDeviceVersion = ""
                                    lastAutoFetchId = ""
                                }
                            },
                            materials = currentMaterials,
                            state = materialState,
                            loading = materialLoading,
                            enabled = !busy && !materialLoading,
                            onImport = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                            onExport = { exportLauncher.launch("ottai_${OttaiConstants.canonicalSensorId(cloudId)}.json") },
                        )



                        InlineQrScannerCard(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            onScanResult = { raw ->
                                OttaiConstants.extractMacFromQr(raw)?.let {
                                    val shouldRefresh = it != cloudId || currentMaterials?.authKeys == null
                                    cloudId = it
                                    selectedDeviceVersion = ""
                                    lastAutoFetchId = ""
                                    if (shouldRefresh) materialRefresh += 1
                                }
                            },
                        )
                        Button(
                            onClick = { startConnect(cloudId) },
                            enabled = !busy && !materialLoading && canConnect,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(connectTitleRes))
                        }
                        HorizontalDivider()
                        if (busy) CircularProgressIndicator()
                        if (status.isNotBlank()) Text(status)

                        if (signedIn) {
                            SettingsItem(
                                title = stringResource(R.string.ottai_account_sensors_title),
                                subtitle = null,
                                showArrow = true,
                                icon = Icons.Default.Cloud,
                                iconTint = MaterialTheme.colorScheme.primary,
                                onClick = if (!busy) ({ step = OttaiSetupStep.ACCOUNT_SENSORS }) else null,
                            )
                        }

                        OutlinedButton(
                            onClick = armNfcRead,
                            enabled = !busy && !materialLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Nfc, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.ottai_nfc_dump))
                        }
                    }
                }

                OttaiSetupStep.ACCOUNT_SENSORS -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(ui.horizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
                ) {
                    Text(stringResource(R.string.ottai_account_sensors_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.ottai_account_sensors_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val savedIds = savedSensors.map { it.sensorId }.toSet()
                    if (savedSensors.isNotEmpty()) {
                        Text(stringResource(R.string.ottai_saved_credentials_title), style = MaterialTheme.typography.titleMedium)
                        savedSensors.forEach { rec ->
                            OttaiSensorRow(
                                title = rec.displayName.ifBlank { OttaiConstants.macWithColons(rec.sensorId) },
                                subtitle = stringResource(R.string.ottai_sensor_saved) + " · " + rec.sensorId,
                                active = true,
                                enabled = !busy,
                                onClick = {
                                    cloudId = rec.sensorId
                                    lastAutoFetchId = ""
                                    materialRefresh += 1
                                    bleAddress = rec.address
                                    currentMaterials = OttaiRegistry.loadMaterials(context, rec.sensorId)
                                    selectedDeviceVersion = currentMaterials?.deviceVersion.orEmpty()
                                    status = context.getString(R.string.ottai_selected_sensor, rec.sensorId)
                                    step = OttaiSetupStep.SENSOR
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (signedIn) {
                        if (devicesLoading) Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.ottai_sensors_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                        (devices ?: emptyList())
                            .filter { OttaiConstants.canonicalSensorId(it.mac) !in savedIds }
                            .forEach { d ->
                                val cid = OttaiConstants.canonicalSensorId(d.mac)
                                OttaiSensorRow(
                                    title = d.serialNo.ifBlank { OttaiConstants.macWithColons(cid) },
                                    subtitle = stringResource(
                                        if (d.isActive) R.string.ottai_sensor_active else R.string.ottai_sensor_past,
                                    ) + " · " + cid,
                                    active = d.isActive,
                                    enabled = !busy,
                                    onClick = {
                                        cloudId = cid
                                        selectedDeviceVersion = d.deviceVersion
                                        lastAutoFetchId = ""
                                        materialRefresh += 1
                                        bleAddress = ""
                                        currentMaterials = null
                                        status = context.getString(R.string.ottai_selected_sensor, cid)
                                        step = OttaiSetupStep.SENSOR
                                    },
                                )
                                HorizontalDivider()
                            }
                        if (!devicesLoading && (devices ?: emptyList()).isEmpty()) {
                            Text(
                                stringResource(R.string.ottai_no_account_sensors),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.ottai_account_sensors_need_login),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OttaiSettingsScreen(navController: NavController) {
    OttaiSetupWizard(
        onDismiss = { navController.popBackStack() },
        onComplete = { navController.popBackStack() },
    )
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
private fun OttaiSensorMaterialCard(
    cloudId: String,
    onCloudIdChange: (String) -> Unit,
    materials: OttaiRegistry.DeviceMaterials?,
    state: OttaiMaterialState,
    loading: Boolean,
    enabled: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    val titleRes = when (state) {
        OttaiMaterialState.MISSING -> R.string.ottai_materials_missing_title
        OttaiMaterialState.PARTIAL -> R.string.ottai_materials_partial_title
        OttaiMaterialState.READY_TO_ACTIVATE -> R.string.ottai_state_not_activated
        OttaiMaterialState.WARMING_UP -> R.string.ottai_state_warmup
        OttaiMaterialState.ACTIVE -> R.string.ottai_state_active
        OttaiMaterialState.EXPIRED -> R.string.ottai_state_expired
    }
    val ready = materials?.authKeys != null
    val canonical = OttaiConstants.canonicalSensorId(cloudId)
    val canExport = ready && OttaiConstants.looksLikeMac(canonical)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = cloudId,
                onValueChange = onCloudIdChange,
                label = { Text(stringResource(R.string.ottai_sensor_mac_hint)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.ottai_materials_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            if (canonical.isNotBlank() || materials != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(titleRes),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!materials?.deviceVersion.isNullOrBlank()) {
                        Text(
                            stringResource(R.string.ottai_device_version_label, materials?.deviceVersion.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

//
            if (ready) {
                Text(stringResource(R.string.ottai_import_export_title), style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onExport,
                        enabled = enabled && canExport,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.export))
                    }
                    OutlinedButton(
                        onClick = onImport,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.ottai_credentials_replace))
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onImport,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ottai_credentials_import))
                }
            }
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

    Column(
        modifier = Modifier.fillMaxWidth(),
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
                            Text(
                                "${stringResource(labelRes)} · ${device.address}",
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
