@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.alerts

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tk.glucodata.Applic
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.alerts.*
import tk.glucodata.ui.components.StyledSwitch
import tk.glucodata.ui.theme.displayLargeExpressive
import tk.glucodata.ui.theme.labelLargeExpressive
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.logic.CustomAlertManager
import java.util.UUID

/**
 * State-of-the-art Alert Settings Screen with Material 3 Expressive design.
 * Inspired by xDrip+ best practices.
 */
@Composable
fun AlertSettingsScreen(
    navController: NavController
) {
    val isMmol = Applic.unit == 1
    
    // Load all alert configs
    val configs = remember {
        mutableStateMapOf<AlertType, AlertConfig>().apply {
            AlertType.entries.forEach { put(it, AlertRepository.loadConfig(it)) }
        }
    }

    // Custom Alerts State
    var customAlerts by remember { mutableStateOf(CustomAlertRepository.getAll()) }
    fun refreshCustomAlerts() {
        customAlerts = CustomAlertRepository.getAll()
    }

    // Dialog States
    // var showAddDialogType by remember { mutableStateOf<CustomAlertType?>(null) } // Removed, using instant add
    var alertToEdit by remember { mutableStateOf<CustomAlertConfig?>(null) }
    
    fun createDefaultCustomAlert(type: CustomAlertType) {
        val existingCount = customAlerts.count { it.type == type }
        val baseName = if (type == CustomAlertType.HIGH) "Custom High" else "Custom Low"
        val newName = "$baseName ${existingCount + 1}"
        
        // Default values respecting isMmol
        val threshold = if (type == CustomAlertType.HIGH) {
            if (isMmol) 10.0f else 180f
        } else {
            if (isMmol) 3.9f else 70f
        }
        
        val newAlert = CustomAlertConfig(
            id = UUID.randomUUID().toString(),
            name = newName,
            type = type,
            threshold = threshold,
            startTimeMinutes = 0,
            endTimeMinutes = 1440, // All day by default
            enabled = true
        )
        CustomAlertRepository.add(newAlert)
        refreshCustomAlerts()
    }

    // Group alerts by category
    val highAlerts = remember {
        listOf(AlertType.HIGH, AlertType.VERY_HIGH)
    }

    val lowAlerts = remember {
        listOf(AlertType.LOW, AlertType.VERY_LOW)
    }

    val predictiveAlerts = remember {
        listOf(
            AlertType.PRE_LOW,
            AlertType.PRE_HIGH,
            AlertType.PERSISTENT_HIGH
        )
    }

    val otherAlerts = remember {
        listOf(
            AlertType.MISSED_READING,
            AlertType.LOSS,
            AlertType.SENSOR_EXPIRY
        )
    }

    // Track expanded states
    var expandedType by remember { mutableStateOf<AlertType?>(null) }
    // Track sound picker state (Generic: Current URI + Callback)
    var soundPickerRequest by remember { mutableStateOf<Pair<String?, (String?) -> Unit>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.glucose_alerts_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // === GLOBAL ALERTS SECTION ===
            item {
                GlobalAlertSettingsCard(
                    allConfigs = configs,
                    onMasterToggle = { enabled ->
                         // Update ALL configs to enabled/disabled
                         configs.forEach { (type, config) ->
                             val updated = config.copy(enabled = enabled)
                             configs[type] = updated
                             AlertRepository.saveConfig(updated)
                         }
                         // Also update Custom Alerts
                         customAlerts.forEach { alert ->
                             val updated = alert.copy(enabled = enabled)
                             CustomAlertRepository.update(updated)
                         }
                         refreshCustomAlerts()
                    },
                    onApplyToAll = { draft ->
                        // Apply draft settings to ALL configs
                        configs.forEach { (type, config) ->
                             val updated = config.copy(
                                 soundEnabled = draft.soundEnabled,
                                 vibrationEnabled = draft.vibrationEnabled,
                                 flashEnabled = draft.flashEnabled,
                                 deliveryMode = draft.deliveryMode,
                                 volumeProfile = draft.volumeProfile,
                                 customSoundUri = draft.customSoundUri,
                                 overrideDND = draft.overrideDND,
                                 timeRangeEnabled = draft.timeRangeEnabled,
                                 activeStartHour = draft.activeStartHour,
                                 activeEndHour = draft.activeEndHour,
                                 retryEnabled = draft.retryEnabled,
                                 retryIntervalMinutes = draft.retryIntervalMinutes,
                                 retryCount = draft.retryCount,
                                 defaultSnoozeMinutes = draft.defaultSnoozeMinutes
                             )
                             configs[type] = updated
                             AlertRepository.saveConfig(updated)
                        }
                        // Also apply to Custom Alerts
                        customAlerts.forEach { alert ->
                            val updated = alert.copy(
                                sound = draft.soundEnabled,
                                vibrate = draft.vibrationEnabled,
                                flash = draft.flashEnabled,
                                style = when(draft.deliveryMode) {
                                    AlertDeliveryMode.NOTIFICATION_ONLY -> "notification"
                                    AlertDeliveryMode.SYSTEM_ALARM -> "alarm"
                                    AlertDeliveryMode.BOTH -> "both"
                                },
                                intensity = when(draft.volumeProfile) {
                                    VolumeProfile.HIGH -> "high"
                                    VolumeProfile.MEDIUM -> "medium"
                                    VolumeProfile.ASCENDING -> "ascending"
                                    else -> "high"
                                },
                                overrideDnd = draft.overrideDND,
                                retryInterval = if(draft.retryEnabled) draft.retryIntervalMinutes else 0,
                                retryCount = draft.retryCount,
                                timeRangeEnabled = draft.timeRangeEnabled,
                                startTimeMinutes = (draft.activeStartHour ?: 0) * 60,
                                endTimeMinutes = (draft.activeEndHour ?: 0) * 60,
                                soundUri = draft.customSoundUri
                            )
                            CustomAlertRepository.update(updated)
                        }
                        refreshCustomAlerts()
                    },
                    onPickSound = { draft, updateDraft ->
                        soundPickerRequest = draft.customSoundUri to { uri ->
                            updateDraft(draft.copy(customSoundUri = uri))
                            soundPickerRequest = null
                        }
                    },
                    onTest = { _ ->
                        AlertStateTracker.resetState(AlertType.LOW)
                        Notify.testTrigger(AlertType.LOW.id)
                    }
                )
            }

            // === HIGH ALERTS SECTION ===
            item {
                SectionHeader(
                    title = "High Alerts",
                    icon = Icons.AutoMirrored.Filled.TrendingUp // Using TrendingUp for Highs
                )
            }

            // Standard High Alerts
            items(highAlerts, key = { it.name }) { type ->
                val config = configs[type] ?: return@items
                val index = highAlerts.indexOf(type)
                val isFirst = index == 0
                val isLast = index == highAlerts.lastIndex
                
                // Position logic: Standard alerts form their own group
                val position = getCardPosition(type, highAlerts)

                AlertCard(
                    config = config,
                    isMmol = isMmol,
                    isExpanded = expandedType == type,
                    position = position,
                    onToggle = { enabled ->
                        val updated = config.copy(enabled = enabled)
                        configs[type] = updated
                        AlertRepository.saveConfig(updated)
                    },
                    onExpand = { expandedType = if (expandedType == type) null else type },
                    onConfigChange = { updated ->
                        configs[type] = updated
                        AlertRepository.saveConfig(updated)
                    },
                    onPickSound = {
                        soundPickerRequest = config.customSoundUri to { uri ->
                            val updated = config.copy(customSoundUri = uri)
                            configs[type] = updated
                            AlertRepository.saveConfig(updated)
                            soundPickerRequest = null
                        }
                    }
                )
            }

            // Custom High Alerts
            val customHighs = customAlerts.filter { it.type == CustomAlertType.HIGH }
            if (customHighs.isNotEmpty()) {
                item { Spacer(Modifier.height(4.dp)) }
            
                items(customHighs, key = { it.id }) { alert ->
                    var isExpanded by remember { mutableStateOf(false) }
                    val index = customHighs.indexOf(alert)
                    val isFirst = index == 0
                    val isLast = index == customHighs.lastIndex
                    
                    // Position logic: Custom alerts form their own group
                    val position = getCardPosition(alert, customHighs)
                    
                    CustomAlertCard(
                        alert = alert,
                        isMmol = isMmol,
                        isExpanded = isExpanded,
                        position = position,
                        onToggle = { enabled ->
                            val updated = alert.copy(enabled = enabled)
                            CustomAlertRepository.update(updated)
                            refreshCustomAlerts()
                        },
                        onExpand = { isExpanded = !isExpanded },
                        onUpdate = { updated ->
                            CustomAlertRepository.update(updated)
                            refreshCustomAlerts()
                        },
                        onDelete = {
                            CustomAlertRepository.delete(alert.id)
                            refreshCustomAlerts()
                        },
                        onPickSound = { config ->
                            soundPickerRequest = config.soundUri to { uri ->
                                val updated = config.copy(soundUri = uri)
                                CustomAlertRepository.update(updated)
                                refreshCustomAlerts()
                                soundPickerRequest = null
                            }
                        }
                    )
                }
            }

            // Add High Alert Button
            item {
                AddCustomAlertButton(
                    text = "Add High Alert",
                    onClick = { createDefaultCustomAlert(CustomAlertType.HIGH) }
                )
            }

            // === LOW ALERTS SECTION ===
            item {
                Spacer(Modifier.height(16.dp))
                SectionHeader(
                    title = "Low Alerts",
                    icon = Icons.AutoMirrored.Filled.TrendingDown // Using TrendingDown for Lows
                )
            }

            // Standard Low Alerts
            items(lowAlerts, key = { it.name }) { type ->
                val config = configs[type] ?: return@items
                val index = lowAlerts.indexOf(type)
                val isFirst = index == 0
                val isLast = index == lowAlerts.lastIndex
                
                // Position logic: Standard alerts form their own group
                val position = getCardPosition(type, lowAlerts)

                AlertCard(
                    config = config,
                    isMmol = isMmol,
                    isExpanded = expandedType == type,
                    position = position,
                    onToggle = { enabled ->
                        val updated = config.copy(enabled = enabled)
                        configs[type] = updated
                        AlertRepository.saveConfig(updated)
                    },
                    onExpand = { expandedType = if (expandedType == type) null else type },
                    onConfigChange = { updated ->
                        configs[type] = updated
                        AlertRepository.saveConfig(updated)
                    },
                    onPickSound = {
                        soundPickerRequest = config.customSoundUri to { uri ->
                            val updated = config.copy(customSoundUri = uri)
                            configs[type] = updated
                            AlertRepository.saveConfig(updated)
                            soundPickerRequest = null
                        }
                    }
                )
            }

            // Custom Low Alerts
            val customLows = customAlerts.filter { it.type == CustomAlertType.LOW }
            if (customLows.isNotEmpty()) {
                item { Spacer(Modifier.height(4.dp)) }

                items(customLows, key = { it.id }) { alert ->
                    var isExpanded by remember { mutableStateOf(false) }
                    val index = customLows.indexOf(alert)
                    val isFirst = index == 0
                    val isLast = index == customLows.lastIndex
                    
                    // Position logic: Custom alerts form their own group
                    val position = getCardPosition(alert, customLows)
                    
                    CustomAlertCard(
                        alert = alert,
                        isMmol = isMmol,
                        isExpanded = isExpanded,
                        position = position,
                        onToggle = { enabled ->
                            val updated = alert.copy(enabled = enabled)
                            CustomAlertRepository.update(updated)
                            refreshCustomAlerts()
                        },
                        onExpand = { isExpanded = !isExpanded },
                        onUpdate = { updated ->
                            CustomAlertRepository.update(updated)
                            refreshCustomAlerts()
                        },
                        onDelete = {
                            CustomAlertRepository.delete(alert.id)
                            refreshCustomAlerts()
                        },
                        onPickSound = { config ->
                            soundPickerRequest = config.soundUri to { uri ->
                                val updated = config.copy(soundUri = uri)
                                CustomAlertRepository.update(updated)
                                refreshCustomAlerts()
                                soundPickerRequest = null
                            }
                        }
                    )
                }
            }

            // Add Low Alert Button
            item {
                AddCustomAlertButton(
                    text = "Add Low Alert",
                    onClick = { createDefaultCustomAlert(CustomAlertType.LOW) }
                )
            }

            // === PREDICTIVE ALERTS SECTION ===
            item {
                Spacer(Modifier.height(16.dp))
                SectionHeader(
                    title = "Predictive Alerts",
                    icon = Icons.AutoMirrored.Filled.TrendingDown
                )
            }

            items(predictiveAlerts, key = { it.name }) { type ->
                val config = configs[type] ?: return@items
                AlertCard(
                    config = config,
                    isMmol = isMmol,
                    isExpanded = expandedType == type,
                    position = getCardPosition(type, predictiveAlerts),
                    onToggle = { enabled ->
                        val updated = config.copy(enabled = enabled)
                        configs[type] = updated
                        AlertRepository.saveConfig(updated)
                    },
                    onExpand = { expandedType = if (expandedType == type) null else type },
                    onConfigChange = { updated ->
                        configs[type] = updated
                        AlertRepository.saveConfig(updated)
                    },
                    onPickSound = {
                        soundPickerRequest = config.customSoundUri to { uri ->
                            val updated = config.copy(customSoundUri = uri)
                            configs[type] = updated
                            AlertRepository.saveConfig(updated)
                            soundPickerRequest = null
                        }
                    }
                )
            }

            // === OTHER ALERTS SECTION ===
            item {
                Spacer(Modifier.height(16.dp))
                SectionHeader(
                    title = "Other Alerts",
                    icon = Icons.Default.NotificationsActive
                )
            }

            items(otherAlerts, key = { it.name }) { type ->
                val config = configs[type] ?: return@items
                AlertCard(
                    config = config,
                    isMmol = isMmol,
                    isExpanded = expandedType == type,
                    position = getCardPosition(type, otherAlerts),
                    onToggle = { enabled ->
                        val updated = config.copy(enabled = enabled)
                        configs[type] = updated
                        AlertRepository.saveConfig(updated)
                    },
                    onExpand = { expandedType = if (expandedType == type) null else type },
                    onConfigChange = { updated ->
                        configs[type] = updated
                        AlertRepository.saveConfig(updated)
                    },
                    onPickSound = {
                        soundPickerRequest = config.customSoundUri to { uri ->
                            val updated = config.copy(customSoundUri = uri)
                            configs[type] = updated
                            AlertRepository.saveConfig(updated)
                            soundPickerRequest = null
                        }
                    }
                )
            }

            // === SNOOZE SECTION ===
            item {
                Spacer(Modifier.height(24.dp))
                PreemptiveSnoozeCard()
            }

            // Bottom padding
            item { Spacer(Modifier.height(100.dp)) }
        }

        // Custom Alert Dialogs
        // Removed showAddDialogType block

        if (alertToEdit != null) {
            CustomAlertDialog(
                initialConfig = alertToEdit,
                onDismiss = { alertToEdit = null },
                onSave = { updatedAlert ->
                    CustomAlertRepository.update(updatedAlert)
                    refreshCustomAlerts()
                }
            )
        }

        // Sound Picker Dialog
        if (soundPickerRequest != null) {
            val (current, callback) = soundPickerRequest!!
            SoundPicker(
                currentUri = current,
                onSoundSelected = callback,
                onDismiss = { soundPickerRequest = null }
            )
        }
    }
}

@Composable
fun AddCustomAlertButton(text: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(50), // Fully rounded
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
fun CustomAlertCard(
    alert: CustomAlertConfig,
    isMmol: Boolean,
    isExpanded: Boolean,
    position: CardPosition,
    onToggle: (Boolean) -> Unit,
    onExpand: () -> Unit,
    onUpdate: (CustomAlertConfig) -> Unit,
    onDelete: () -> Unit,
    onPickSound: (CustomAlertConfig) -> Unit
) {
    // Determine icon/color based on type to match AlertCard style
    val icon = if (alert.type == CustomAlertType.HIGH) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown
    // Standard High is usually Orange, Low is Red/Primary.
    val accentColor = if (alert.type == CustomAlertType.HIGH) Color(0xFFFF9800) else Color(0xFFF44336) 
    
    // For Title/Subtitle
    val title = alert.name.ifEmpty { if (alert.type == CustomAlertType.HIGH) "High Alert" else "Low Alert" }
    val subtitle = buildString {
        append(if (alert.type == CustomAlertType.HIGH) "> " else "< ")
        append(formatThreshold(alert.threshold, isMmol))
        
        if (!alert.enabled) {
            append(" • Disabled")
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (alert.enabled) 1.dp else 0.dp,
        shadowElevation = 0.dp
    ) {
        Column {
            // Main row (always visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Colored icon container (Identical to AlertCard)
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(40.dp),
                    color = accentColor.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(icon, null, tint = accentColor, modifier = Modifier.size(20.dp))
                    }
                }

                // Title and subtitle
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Chevron Indicator
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(Modifier.width(16.dp))

                // Enable/Disable switch
                StyledSwitch(
                    checked = alert.enabled,
                    onCheckedChange = onToggle
                )
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(100))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(16.dp)
                ) {
                    // Map to AlertConfig for shared UI component
                    val genericConfig = AlertConfig(
                        type = if (alert.type == CustomAlertType.HIGH) AlertType.HIGH else AlertType.LOW,
                        enabled = alert.enabled,
                        threshold = alert.threshold,
                        soundEnabled = alert.sound,
                        customSoundUri = alert.soundUri,
                        vibrationEnabled = alert.vibrate,
                        flashEnabled = alert.flash,
                        volumeProfile = VolumeProfile.valueOf(alert.intensity.uppercase()),
                        deliveryMode = when(alert.style.lowercase()) {
                            "alarm", "system_alarm" -> AlertDeliveryMode.SYSTEM_ALARM
                            "both" -> AlertDeliveryMode.BOTH
                            else -> AlertDeliveryMode.NOTIFICATION_ONLY // Default for "notification" or unknown
                        },
                        overrideDND = alert.overrideDnd,
                        timeRangeEnabled = alert.timeRangeEnabled,
                        activeStartHour = alert.startTimeMinutes / 60,
                        activeEndHour = alert.endTimeMinutes / 60
                    )

                    // Render common settings
                    CommonAlertSettings(
                        config = genericConfig,
                        onConfigChange = { newConfig ->
                            val updated = alert.copy(
                                enabled = newConfig.enabled,
                                threshold = newConfig.threshold ?: alert.threshold,
                                sound = newConfig.soundEnabled,
                                soundUri = newConfig.customSoundUri,
                                vibrate = newConfig.vibrationEnabled,
                                flash = newConfig.flashEnabled,
                                style = when(newConfig.deliveryMode) {
                                    AlertDeliveryMode.SYSTEM_ALARM -> "alarm"
                                    AlertDeliveryMode.BOTH -> "both"
                                    else -> "notification"
                                },
                                intensity = newConfig.volumeProfile.name,
                                overrideDnd = newConfig.overrideDND,
                                timeRangeEnabled = newConfig.timeRangeEnabled,
                                startTimeMinutes = (newConfig.activeStartHour ?: 0) * 60,
                                endTimeMinutes = (newConfig.activeEndHour ?: 0) * 60
                            )
                            onUpdate(updated)
                        },
                        onPickSound = { onPickSound(alert) },
                        onTest = {
                             Notify.testCustomTrigger(
                                alert.soundUri,
                                alert.sound,
                                alert.vibrate,
                                alert.flash,
                                alert.type == CustomAlertType.HIGH,
                                alert.style,
                                alert.intensity,
                                alert.overrideDnd
                            )
                        },
                        headerContent = {
                            // Name Field
                            OutlinedTextField(
                                value = alert.name,
                                onValueChange = { onUpdate(alert.copy(name = it)) },
                                label = { Text("Alert Name") },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                            
                            // Threshold Slider using generic UI
                             ThresholdSlider(
                                label = "Threshold",
                                value = alert.threshold,
                                isMmol = isMmol,
                                range = if (isMmol) {
                                     // mmol/L Ranges
                                     if (alert.type == CustomAlertType.HIGH) 4.0f..28.0f else 2.0f..6.0f
                                } else {
                                     // mg/dL Ranges
                                     if (alert.type == CustomAlertType.HIGH) 70f..500f else 40f..110f
                                },
                                onValueChange = { onUpdate(alert.copy(threshold = it)) }
                            )
                        }
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    // Delete Button
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete Custom Alert")
                    }
                }
            }
        }
    }
}






@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

enum class CardPosition { TOP, MIDDLE, BOTTOM, SINGLE }

private fun <T> getCardPosition(item: T, list: List<T>): CardPosition {
    val index = list.indexOf(item)
    return when {
        list.size == 1 -> CardPosition.SINGLE
        index == 0 -> CardPosition.TOP
        index == list.lastIndex -> CardPosition.BOTTOM
        else -> CardPosition.MIDDLE
    }
}

private fun cardShape(position: CardPosition, radius: androidx.compose.ui.unit.Dp = 12.dp): RoundedCornerShape {
    return when (position) {
        CardPosition.SINGLE -> RoundedCornerShape(radius)
        CardPosition.TOP -> RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = 4.dp, bottomEnd = 4.dp)
        CardPosition.MIDDLE -> RoundedCornerShape(4.dp)
        CardPosition.BOTTOM -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = radius, bottomEnd = radius)
    }
}

@Composable
private fun AlertCard(
    config: AlertConfig,
    isMmol: Boolean,
    isExpanded: Boolean,
    position: CardPosition,
    onToggle: (Boolean) -> Unit,
    onExpand: () -> Unit,
    onConfigChange: (AlertConfig) -> Unit,
    onPickSound: () -> Unit
) {
    val context = LocalContext.current
    val (icon, accentColor) = getAlertIconAndColor(config.type)


    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = cardShape(position),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (config.enabled) 1.dp else 0.dp,
        shadowElevation = 0.dp
    ) {
        Column {
            // Main row (always visible) - minimum 72dp touch target
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Colored icon container
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 0.dp),
                    shape = RoundedCornerShape(40.dp),
                    color = accentColor.copy(alpha = 0.12f)
                )
                {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp),

                            )
                    }
                }

                // Title and subtitle
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = stringResource(config.type.nameResId),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val subtitle = buildString {
                        config.threshold?.let { append(formatThreshold(it, isMmol)) }
                        config.durationMinutes?.let {
                            if (isNotEmpty()) append(" • ")
                            append("$it min")
                        }
                        if (!config.enabled) {
                            if (isNotEmpty()) append(" • ")
                            append("Disabled")
                        }
                    }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Chevron Indicator (Before Switch)
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.width(16.dp))

                // Enable/Disable switch
                StyledSwitch(
                    checked = config.enabled,
                    onCheckedChange = onToggle
                )
            }

            // Expanded content with faster animation
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(100))
            ) {
                Column { // Removed redundant animateContentSize() as AnimatedVisibility handles it
                    AlertSettingsExpanded(
                        config = config,
                        isMmol = isMmol,
                        onConfigChange = onConfigChange,
                        onTest = {
                            // Explicitly reset state for TEST to ensure it always fires
                            // (ignoring previous triggers or suppressed state)
                            AlertStateTracker.resetState(config.type)
                            // Use Notify.testTrigger to simulate real alarm flow
                            Notify.testTrigger(config.type.id)
                        },
                        onPickSound = onPickSound
                    )
                }
            }
        }
    }
}

/**
 * Test an alert by playing its sound/vibration briefly.
 */

@Composable
private fun AlertSettingsExpanded(
    config: AlertConfig,
    isMmol: Boolean,
    onConfigChange: (AlertConfig) -> Unit,
    onTest: () -> Unit,
    onPickSound: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp)
    ) {
        CommonAlertSettings(
            config = config,
            onConfigChange = onConfigChange,
            onPickSound = { onPickSound() },
            onTest = onTest,
            headerContent = {
                 // === Threshold Section (If applicable) ===
                if (config.threshold != null) {
                    ThresholdSlider(
                        label = "Threshold",
                        value = config.threshold,
                        isMmol = isMmol,
                        range = getThresholdRange(config.type, isMmol),
                        onValueChange = { onConfigChange(config.copy(threshold = it)) }
                    )
                }
                
                // === Durations Section (If applicable) ===
                if (config.durationMinutes != null || config.forecastMinutes != null) {
                     Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        config.durationMinutes?.let {
                            Box(Modifier.weight(1f)) {
                                DurationSlider(
                                    label = "Alert after",
                                    value = it,
                                    range = 5..120,
                                    stepSize = 5,
                                    onValueChange = { v -> onConfigChange(config.copy(durationMinutes = v)) }
                                )
                            }
                        }
                        config.forecastMinutes?.let {
                            Box(Modifier.weight(1f)) {
                                DurationSlider(
                                    label = "Look ahead",
                                    value = it,
                                    range = 10..60,
                                    stepSize = 5,
                                    onValueChange = { v -> onConfigChange(config.copy(forecastMinutes = v)) }
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}    
        
//        HorizontalDivider()
        


@Composable
private fun ThresholdSlider(
    label: String,
    value: Float,
    isMmol: Boolean,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    // Calculate step size based on range and unit
    // Lower ranges need finer control (0.1 mmol or 1 mg/dL)
    val stepSize = if (isMmol) {
        if (range.start < 5f) 0.1f else 0.2f  // Finer steps for low values
    } else {
        if (range.start < 100f) 1f else 5f   // Finer steps for low values
    }
    val steps = ((range.endInclusive - range.start) / stepSize).toInt() - 1

    Column {
        var sliderValue by remember { mutableStateOf(value) }
        LaunchedEffect(value) {
            sliderValue = value
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                // Use LOCAL sliderValue for real-time updates
                formatThreshold(sliderValue, isMmol),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                val rounded = (kotlin.math.round(sliderValue / stepSize) * stepSize)
                onValueChange(rounded)
            },
            valueRange = range,
            steps = steps.coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Duration slider with snapping to useful values (every 5 mins).
 */
@Composable
internal fun DurationSlider(
    label: String,
    value: Int,
    range: IntRange,
    stepSize: Int = 5,
    onValueChange: (Int) -> Unit
) {
    val steps = ((range.last - range.first) / stepSize) - 1

    Column {
        var sliderValue by remember { mutableStateOf(value.toFloat()) }
        LaunchedEffect(value) {
            sliderValue = value.toFloat()
        }

        // Calculate display value based on current slider position, rounded to step
        val displayValue = (kotlin.math.round(sliderValue / stepSize) * stepSize).toInt()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$displayValue min",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                val rounded = (kotlin.math.round(sliderValue / stepSize) * stepSize).toInt()
                onValueChange(rounded)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps.coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DeliveryModeSelector(
    mode: AlertDeliveryMode,
    onModeChange: (AlertDeliveryMode) -> Unit
) {
    Column {
        Text(
            "Alert Style",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AlertDeliveryMode.entries.forEach { deliveryMode ->
                FilterChip(
                    selected = mode == deliveryMode,
                    onClick = { onModeChange(deliveryMode) },
                    label = { Text(deliveryMode.displayName) },
                    leadingIcon = if (mode == deliveryMode) {
                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun VolumeProfileSelector(
    profile: VolumeProfile,
    onProfileChange: (VolumeProfile) -> Unit
) {
    Column {
        Text(
            "Volume/Vibration Profile",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VolumeProfile.entries.take(3).forEach { volumeProfile ->
                FilterChip(
                    selected = profile == volumeProfile,
                    onClick = { onProfileChange(volumeProfile) },
                    label = { Text(volumeProfile.displayName) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VolumeProfile.entries.drop(3).forEach { volumeProfile ->
                FilterChip(
                    selected = profile == volumeProfile,
                    onClick = { onProfileChange(volumeProfile) },
                    label = { Text(volumeProfile.displayName) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.weight(1f)) // Balance the row
        }
    }
}

@Composable
private fun SnoozeDurationSelector(
    minutes: Int,
    onMinutesChange: (Int) -> Unit
) {
    DurationSlider(
        label = "Default snooze",
        value = minutes,
        range = 5..120,
        stepSize = 5,
        onValueChange = onMinutesChange
    )
}

@Composable
private fun SettingsRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PreemptiveSnoozeCard() {
    var showDialog by remember { mutableStateOf(false) }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier
                .clickable { showDialog = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Snooze,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Preemptive Snooze",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    "Snooze alerts before they trigger",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
    
    if (showDialog) {
        PreemptiveSnoozeDialog(
            onDismiss = { showDialog = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreemptiveSnoozeDialog(
    onDismiss: () -> Unit
) {
    var snoozeLow by remember { mutableStateOf(false) }
    var snoozeHigh by remember { mutableStateOf(false) }
    var snoozeDuration by remember { mutableStateOf(30) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Snooze, contentDescription = null) },
        title = { Text("Preemptive Snooze") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Snooze alerts before they trigger. Useful after eating or taking insulin.",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    FilterChip(
                        selected = snoozeLow,
                        onClick = { snoozeLow = !snoozeLow },
                        label = { Text("Low Alerts") },
                        leadingIcon = if (snoozeLow) {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = snoozeHigh,
                        onClick = { snoozeHigh = !snoozeHigh },
                        label = { Text("High Alerts") },
                        leadingIcon = if (snoozeHigh) {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else null
                    )
                }
                
                DurationSlider(
                    label = "Duration",
                    value = snoozeDuration,
                    range = 15..120,
                    stepSize = 15,
                    onValueChange = { snoozeDuration = it }
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    if (snoozeLow || snoozeHigh) {
                        SnoozeManager.preemptiveSnooze(snoozeLow, snoozeHigh, snoozeDuration)
                    }
                    onDismiss()
                },
                enabled = snoozeLow || snoozeHigh
            ) {
                Text("Snooze")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// === NEW: Time Range Settings ===
// === NEW: Time Range Settings ===
@Composable
internal fun TimeRangeSettings(
    enabled: Boolean,
    startHour: Int?,
    endHour: Int?,
    onEnabledChange: (Boolean) -> Unit,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
//                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                "Active time range",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface
            )
            StyledSwitch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )

        }
        
        AnimatedVisibility(
            visible = enabled,
            enter = expandVertically(animationSpec = tween(durationMillis = 200)),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 200))
        ) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("From", style = MaterialTheme.typography.bodySmall)
                    HourPicker(
                        value = startHour ?: 22,
                        onValueChange = onStartChange
                    )
                    Text("to", style = MaterialTheme.typography.bodySmall)
                    HourPicker(
                        value = endHour ?: 8,
                        onValueChange = onEndChange
                    )
                }
                Text(
                    text = formatTimeRange(startHour ?: 22, endHour ?: 8),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun HourPicker(
    value: Int,
    onValueChange: (Int) -> Unit
) {
    var showDropdown by remember { mutableStateOf(false) }
    
    Surface(
        onClick = { showDropdown = true },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = String.format("%02d:00", value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    }
    
    DropdownMenu(
        expanded = showDropdown,
        onDismissRequest = { showDropdown = false }
    ) {
        (0..23).forEach { hour ->
            DropdownMenuItem(
                text = { Text(String.format("%02d:00", hour)) },
                onClick = {
                    onValueChange(hour)
                    showDropdown = false
                }
            )
        }
    }
}

internal fun formatTimeRange(start: Int, end: Int): String {
    fun fmt(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        // Handle 24:00 as 00:00 (next day) if preferred, or just 00:00
        val hDisplay = h % 24 
        return String.format("%02d:%02d", hDisplay, m)
    }
    return "${fmt(start)}–${fmt(end)}"
}

// === NEW: Retry Settings ===
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RetrySettings(
    enabled: Boolean,
    intervalMinutes: Int,
    retryCount: Int,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onCountChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
//                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Retry if no reaction",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
//                Text(
//                    "Re-alert if not dismissed",
//                    style = MaterialTheme.typography.labelSmall,
//                    color = MaterialTheme.colorScheme.onSurfaceVariant
//                )
            }
            StyledSwitch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
        
        AnimatedVisibility(
            visible = enabled,
            enter = expandVertically(animationSpec = tween(durationMillis = 200)),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 200))
        ) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Text("Retry every", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1, 2, 3, 5, 10, 15).forEach { minutes ->
                        FilterChip(
                            selected = intervalMinutes == minutes,
                            onClick = { onIntervalChange(minutes) },
                            label = { Text("${minutes}m") }
                        )
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                Text("Max retries", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0 to "∞", 1 to "1", 2 to "2", 3 to "3", 5 to "5").forEach { (count, label) ->
                        FilterChip(
                            selected = retryCount == count,
                            onClick = { onCountChange(count) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    }
}

// ---- Helper functions ----

private fun getAlertIconAndColor(type: AlertType): Pair<ImageVector, Color> {
    return when (type) {
        AlertType.VERY_LOW -> Icons.Default.Warning to Color(0xFFE53935)
        AlertType.LOW -> Icons.Default.ArrowDownward to Color(0xFFFF7043)
        AlertType.HIGH -> Icons.Default.ArrowUpward to Color(0xFFFFB300)
        AlertType.VERY_HIGH -> Icons.Default.Warning to Color(0xFFFF6F00)
        AlertType.PRE_LOW -> Icons.AutoMirrored.Filled.TrendingDown to Color(0xFFFF8A65)
        AlertType.PRE_HIGH -> Icons.AutoMirrored.Filled.TrendingUp to Color(0xFFFFCA28)
        AlertType.PERSISTENT_HIGH -> Icons.Default.Timer to Color(0xFFFFA726)
        AlertType.MISSED_READING -> Icons.Default.SignalWifiOff to Color(0xFF78909C)
        AlertType.LOSS -> Icons.Default.BluetoothDisabled to Color(0xFF90A4AE)
        AlertType.SENSOR_EXPIRY -> Icons.Default.Schedule to Color(0xFF7E57C2)
        else -> Icons.Default.Notifications to Color(0xFF42A5F5)  // Default blue
    }
}

private fun formatThreshold(value: Float, isMmol: Boolean): String {
    return if (isMmol) {
        String.format("%.1f", value)
    } else {
        String.format("%.0f", value)
    }
}

private fun getThresholdRange(type: AlertType, isMmol: Boolean): ClosedFloatingPointRange<Float> {
    return when (type) {
        AlertType.VERY_LOW -> if (isMmol) 2.0f..4.0f else 36f..70f
        AlertType.LOW -> if (isMmol) 3.0f..5.5f else 54f..100f
        AlertType.HIGH -> if (isMmol) 7.0f..15.0f else 126f..270f
        AlertType.VERY_HIGH -> if (isMmol) 10.0f..20.0f else 180f..360f
        AlertType.PRE_LOW -> if (isMmol) 3.5f..6.0f else 63f..108f
        AlertType.PRE_HIGH -> if (isMmol) 7.0f..14.0f else 126f..252f
        AlertType.PERSISTENT_HIGH -> if (isMmol) 7.0f..15.0f else 126f..270f
        else -> if (isMmol) 2.0f..20.0f else 36f..360f
    }
}


@Composable
internal fun SoundSelector(
    currentUri: String?,
    onSoundClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Sound",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedCard(
            onClick = onSoundClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                   Text(
                        text = if (currentUri.isNullOrEmpty()) "Default Notification Sound" else "Custom Sound Selected",
                        style = MaterialTheme.typography.bodyLarge
                   )
                   if (!currentUri.isNullOrEmpty()) {
                       Text(
                           text = "Tap to change",
                           style = MaterialTheme.typography.bodySmall,
                           color = MaterialTheme.colorScheme.onSurfaceVariant
                       )
                   }
                }
            }
        }
    }
}
