package tk.glucodata.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.Natives

enum class SibionicsType(val displayNameRes: Int, val subtype: Int) {
    EU(R.string.eusibionics, 0),
    HEMATONIX(R.string.hematonix, 1),
    CHINESE(R.string.chsibionics, 2),
    SIBIONICS2(R.string.sibionics2, 3)
}

enum class SibionicsSetupStep {
    SCAN_SENSOR,
    SELECT_TYPE,
    SCAN_TRANSMITTER,
    CONNECTING
}

/**
 * Modern Material 3 wizard for setting up Sibionics sensors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SibionicsSetupWizard(
    onDismiss: () -> Unit,
    onScanQr: (onResult: (String) -> Unit) -> Unit,
    onComplete: () -> Unit
) {
    var currentStep by remember { mutableStateOf(SibionicsSetupStep.SCAN_SENSOR) }
    var selectedType by remember { mutableStateOf(SibionicsType.EU) }
    var sensorPtr by remember { mutableStateOf(0L) }
    var sensorName by remember { mutableStateOf("") }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sibionics_setup_title)) },
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
            transitionSpec = {
                if (targetState > initialState) {
                    slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                } else {
                    slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "WizardStep"
        ) { step ->
            when (step) {
                SibionicsSetupStep.SCAN_SENSOR -> ScanSensorStep(
                    onScanClick = {
                        onScanQr { scannedTag ->
                            // Parse the QR code
                            val indexPtr = intArrayOf(-1)
                            val name = Natives.addSIscangetName(scannedTag, indexPtr)
                            if (name != null && name.isNotEmpty()) {
                                sensorName = name
                                sensorPtr = Natives.str2sensorptr(name)
                                val libreVersion = Natives.getSensorptrLibreVersion(sensorPtr)
                                if (libreVersion == 0x10) {
                                    // Sibionics sensor - proceed to type selection
                                    currentStep = SibionicsSetupStep.SELECT_TYPE
                                }
                            }
                        }
                    }
                )
                
                SibionicsSetupStep.SELECT_TYPE -> SelectTypeStep(
                    selectedType = selectedType,
                    onTypeSelected = { type ->
                        selectedType = type
                    },
                    onNext = {
                        Natives.setSensorptrSiSubtype(sensorPtr, selectedType.subtype)
                        if (selectedType == SibionicsType.SIBIONICS2) {
                            currentStep = SibionicsSetupStep.SCAN_TRANSMITTER
                        } else {
                            // Skip transmitter scan, go straight to connecting
                            currentStep = SibionicsSetupStep.CONNECTING
                            // Enable Bluetooth and complete
                            finishSetup(onComplete)
                        }
                    },
                    onBack = { currentStep = SibionicsSetupStep.SCAN_SENSOR }
                )
                
                SibionicsSetupStep.SCAN_TRANSMITTER -> ScanTransmitterStep(
                    onScanClick = {
                        onScanQr { scannedTag ->
                            val success = Natives.siSensorptrTransmitterScan(sensorPtr, scannedTag)
                            if (success) {
                                currentStep = SibionicsSetupStep.CONNECTING
                                finishSetup(onComplete)
                            }
                        }
                    },
                    onBack = { currentStep = SibionicsSetupStep.SELECT_TYPE }
                )
                
                SibionicsSetupStep.CONNECTING -> ConnectingStep()
            }
        }
    }
}

private fun finishSetup(onComplete: () -> Unit) {
    // Trigger Bluetooth connection
    if (Natives.getusebluetooth()) {
        tk.glucodata.SensorBluetooth.updateDevices()
        tk.glucodata.SuperGattCallback.glucosealarms.setLossAlarm()
    } else {
        Natives.updateUsedSensors()
    }
    onComplete()
}

@Composable
private fun ScanSensorStep(onScanClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(120.dp)
        ) {
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = stringResource(R.string.scan_sensor_qr),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.scan_sensor_qr_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Button(
            onClick = onScanClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.scan_sensor_qr))
        }
    }
}

@Composable
private fun SelectTypeStep(
    selectedType: SibionicsType,
    onTypeSelected: (SibionicsType) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.select_sibionics_type),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Column(Modifier.selectableGroup()) {
            SibionicsType.entries.forEach { type ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedType == type,
                            onClick = { onTypeSelected(type) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedType == type,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(type.displayNameRes),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.ok))
            }
        }
    }
}

@Composable
private fun ScanTransmitterStep(
    onScanClick: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(120.dp)
        ) {
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = stringResource(R.string.scan_transmitter),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.scan_transmitter_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Button(
            onClick = onScanClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.scan_transmitter))
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun ConnectingStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            strokeWidth = 6.dp
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.connecting),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}
