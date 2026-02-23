package tk.glucodata.ui.setup

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.microtechmd.blecomm.BlecommLoader
import kotlinx.coroutines.launch
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.rememberBleScanner

enum class AiDexSetupStep {
    SCAN,
    CONNECTING,
    SUCCESS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDexSetupWizard(
    onDismiss: () -> Unit,
    onComplete: () -> Unit
) {
    val tag = "AiDexSetupWizard"
    val ui = rememberWizardUiMetrics()
    var currentStep by remember { mutableStateOf(AiDexSetupStep.SCAN) }
    var selectedDeviceName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var vendorLibAvailable by remember { mutableStateOf(BlecommLoader.isLibraryPresent(context)) }
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val installed = BlecommLoader.installFromDocument(context, uri)
        vendorLibAvailable = if (installed) true else BlecommLoader.isLibraryPresent(context)
        val message = if (installed) {
            context.getString(R.string.installedlibrary)
        } else {
            context.getString(R.string.cantextract, BlecommLoader.requiredLibraryFileName())
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    val launchUploadPickerSafely = {
        try {
            uploadLauncher.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Log.e(tag, "No document picker activity available: ${e.message}")
            Toast.makeText(context, context.getString(R.string.unable_to_open_source_file), Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Log.e(tag, "Failed to launch document picker: ${t.message}")
            Toast.makeText(context, context.getString(R.string.unable_to_open_source_file), Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aidex_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentStep,
            modifier = Modifier.padding(padding),
            label = "AiDexWizard"
        ) { step ->
            when (step) {
                AiDexSetupStep.SCAN -> AiDexScanStep(
                    ui = ui,
                    vendorLibAvailable = vendorLibAvailable,
                    onUploadProprietary = launchUploadPickerSafely,
                    onDeviceSelected = { rawName, address ->
                        try {
                            if (!vendorLibAvailable) {
                                Toast.makeText(context, context.getString(R.string.wronglibrary), Toast.LENGTH_LONG).show()
                                return@AiDexScanStep
                            }
                            val libReady = BlecommLoader.ensureLoaded(context)
                            vendorLibAvailable = libReady
                            if (!libReady) {
                                Toast.makeText(context, context.getString(R.string.wronglibrary), Toast.LENGTH_LONG).show()
                                return@AiDexScanStep
                            }
                            val name = normalizeAiDexSerial(rawName)
                            if (name == null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.aidex_parse_error, rawName),
                                    Toast.LENGTH_LONG
                                ).show()
                                return@AiDexScanStep
                            }

                            selectedDeviceName = name
                            currentStep = AiDexSetupStep.CONNECTING

                            // Initiate Connection Logic
                            scope.launch {
                                try {
                                    // 1. Add to Persistence & SensorBluetooth
                                    SensorBluetooth.addAiDexSensor(context, name, address)

                                    // 2. Wait a bit then show success
                                    kotlinx.coroutines.delay(2000)
                                    currentStep = AiDexSetupStep.SUCCESS
                                } catch (t: Throwable) {
                                    Log.e(tag, "Failed to add/select AiDex sensor: ${t.message}")
                                    Toast.makeText(context, context.getString(R.string.nobluetooth), Toast.LENGTH_LONG).show()
                                    currentStep = AiDexSetupStep.SCAN
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e(tag, "onDeviceSelected failed: ${t.message}")
                            Toast.makeText(context, context.getString(R.string.nobluetooth), Toast.LENGTH_LONG).show()
                            currentStep = AiDexSetupStep.SCAN
                        }
                    }
                )
                AiDexSetupStep.CONNECTING -> Box(
                     modifier = Modifier.fillMaxSize(),
                     contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(ui.spacerMedium))
                        Text(stringResource(R.string.aidex_connecting_to, selectedDeviceName))
                    }
                }
                AiDexSetupStep.SUCCESS -> Box(
                     modifier = Modifier.fillMaxSize(),
                     contentAlignment = Alignment.Center
                ) {
                     Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CheckCircle,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(if (ui.compact) 56.dp else 64.dp)
                        )
                        Spacer(Modifier.height(ui.spacerMedium))
                        Text(stringResource(R.string.aidex_connected), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(ui.spacerLarge))
                        Button(
                            onClick = onComplete,
                            modifier = Modifier.height(ui.buttonHeight)
                        ) {
                            Text(stringResource(R.string.finish_setup))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AiDexScanStep(
    ui: WizardUiMetrics,
    vendorLibAvailable: Boolean,
    onUploadProprietary: () -> Unit,
    onDeviceSelected: (String, String) -> Unit
) {
    data class ScanCandidate(
        val address: String,
        val rawName: String
    )

    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<ScanCandidate>>(emptyList()) }
    val scanner = rememberBleScanner()
    var scanPermissionGranted by remember { mutableStateOf(hasBleScanPermissions(context)) }
    var bluetoothEnabled by remember { mutableStateOf(scanner.isBluetoothEnabled()) }
    var scanRetryKey by remember { mutableStateOf(0) }
    var scanError by remember { mutableStateOf<BleDeviceScanner.ScanStartError?>(null) }
    var requestedPermissionOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        scanPermissionGranted = hasBleScanPermissions(context)
        bluetoothEnabled = scanner.isBluetoothEnabled()
        scanError = null
        scanRetryKey += 1
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
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

    // Start Scanning Effect
    DisposableEffect(scanPermissionGranted, bluetoothEnabled, scanRetryKey) {
        if (!scanPermissionGranted || !bluetoothEnabled) {
            scanner.stopScan()
            return@DisposableEffect onDispose { scanner.stopScan() }
        }

        scanner.startScan(
            onResult = { result ->
                val device = result.device
                val name = try {
                    device.name ?: result.scanRecord?.deviceName
                } catch (_: SecurityException) {
                    null
                } ?: return@startScan
                val address = try {
                    device.address
                } catch (_: SecurityException) {
                    null
                } ?: return@startScan

                normalizeAiDexSerial(name) ?: return@startScan
                // val mfg = record?.getManufacturerSpecificData(0x59)
                // Relaxed filter: Don't check for 0x59 manufacturer ID to support variants (Linx, Lumiflex)
                // if (record != null && mfg == null) return@startScan
                if (devices.none { it.address == address }) {
                    devices = devices + ScanCandidate(
                        address = address,
                        rawName = name
                    )
                }
            },
            onError = { error ->
                scanError = error
                when (error) {
                    BleDeviceScanner.ScanStartError.NoPermission -> scanPermissionGranted = false
                    BleDeviceScanner.ScanStartError.BluetoothDisabled -> bluetoothEnabled = false
                    else -> Unit
                }
            }
        )
        onDispose { scanner.stopScan() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(ui.spacerMedium))
        Text(
            stringResource(R.string.aidex_searching_sensors),
            modifier = Modifier.padding(ui.horizontalPadding),
            style = MaterialTheme.typography.titleMedium
        )
        if (!scanPermissionGranted || !bluetoothEnabled || scanError != null) {
            Spacer(Modifier.height(ui.spacerMedium))
            Card(
                modifier = Modifier
                    .padding(horizontal = ui.horizontalPadding)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val messageRes = when {
                        !scanPermissionGranted && Build.VERSION.SDK_INT >= 31 -> R.string.turn_on_nearby_devices_permission
                        !scanPermissionGranted -> R.string.turn_on_location_permission
                        !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> R.string.bluetooth_is_turned_off
                        else -> R.string.nobluetooth
                    }
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(ui.spacerMedium))
                    val buttonRes = when {
                        !scanPermissionGranted -> R.string.permission
                        !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> R.string.enable_bluetooth
                        else -> R.string.search_bluetooth
                    }
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
                        modifier = Modifier.height(ui.buttonHeight)
                    ) {
                        Text(stringResource(buttonRes))
                    }
                }
            }
        }
        if (!vendorLibAvailable) {
            Spacer(Modifier.height(ui.spacerMedium))
            Card(
                modifier = Modifier
                    .padding(horizontal = ui.horizontalPadding)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.wronglibrary),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(ui.spacerMedium))
                    Button(
                        onClick = onUploadProprietary,
                        modifier = Modifier.height(ui.buttonHeight)
                    ) {
                        Text(stringResource(R.string.upload))
                    }
                }
            }
        }
        
        LazyColumn {
            items(devices) { device ->
                val name = device.rawName.ifBlank { stringResource(R.string.unknown) }
                val serial = normalizeAiDexSerial(name)
                if (serial == null) return@items
                ListItem(
                    headlineContent = { Text("$name ($serial)") },
                    supportingContent = { Text(device.address) },
                    leadingContent = { Icon(Icons.Default.Bluetooth, null) },
                    modifier = Modifier.clickable(enabled = vendorLibAvailable) {
                        onDeviceSelected(name, device.address) 
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

private fun normalizeAiDexSerial(rawName: String): String? {
    val xPrefixed = Regex("X\\s*-?\\s*([A-Z0-9]{8,})", RegexOption.IGNORE_CASE)
    val xMatch = xPrefixed.find(rawName)
    if (xMatch != null) {
        val body = xMatch.groupValues[1].uppercase()
        return "X-$body"
    }

    // Some AiDex family sensors advertise with a product prefix (for example "Vista-...")
    // instead of the canonical "X-..." serial format used internally by the app.
    val familyPrefixed = Regex("(?:AIDEX|LINX|LUMIFLEX|VISTA)\\s*[-_]?\\s*([A-Z0-9]{8,})", RegexOption.IGNORE_CASE)
    val familyMatch = familyPrefixed.find(rawName)
    if (familyMatch != null) {
        val body = familyMatch.groupValues[1].uppercase()
        return "X-$body"
    }

    val cleaned = rawName.trim().replace(" ", "")
    if (cleaned.length == 11 && cleaned.all { it.isLetterOrDigit() }) {
        return "X-${cleaned.uppercase()}"
    }
    return null
}

private fun requiredBleScanPermissions(): Array<String> {
    return when {
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }
}

private fun hasBleScanPermissions(context: Context): Boolean {
    return requiredBleScanPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
