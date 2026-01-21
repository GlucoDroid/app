package tk.glucodata.ui.alerts

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import tk.glucodata.alerts.CustomAlertConfig
import tk.glucodata.alerts.CustomAlertType
import java.util.UUID

@Composable
fun CustomAlertDialog(
    initialConfig: CustomAlertConfig? = null,
    defaultType: CustomAlertType = CustomAlertType.HIGH,
    onDismiss: () -> Unit,
    onSave: (CustomAlertConfig) -> Unit
) {
    var name by remember { mutableStateOf(initialConfig?.name ?: "") }
    var thresholdStr by remember { mutableStateOf(initialConfig?.threshold?.toString() ?: "") }
    var startTimeMinutes by remember { mutableIntStateOf(initialConfig?.startTimeMinutes ?: 0) }
    var endTimeMinutes by remember { mutableIntStateOf(initialConfig?.endTimeMinutes ?: 1440) }
    
    val context = LocalContext.current

    fun showTimePicker(initialMinutes: Int, onTimeSelected: (Int) -> Unit) {
        val hour = (initialMinutes / 60) % 24
        val minute = initialMinutes % 60
        TimePickerDialog(context, { _, h, m ->
            onTimeSelected(h * 60 + m)
        }, hour, minute, true).show()
    }

    fun formatTime(minutes: Int): String {
        if (minutes >= 1440) return "24:00" // End of day
        val h = minutes / 60
        val m = minutes % 60
        return String.format("%02d:%02d", h, m)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = if (initialConfig == null) "New Custom Alert" else "Edit Alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Alert Name") },
                    placeholder = { Text("e.g. Dawn Phenomenon") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = thresholdStr,
                    onValueChange = { thresholdStr = it },
                    label = { Text("Threshold (mg/dL)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Active Time Range", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showTimePicker(startTimeMinutes) { startTimeMinutes = it } }
                    ) {
                        Text("Start: ${formatTime(startTimeMinutes)}")
                    }
                    Text("-")
                    OutlinedButton(
                        onClick = { showTimePicker(endTimeMinutes) { endTimeMinutes = it } }
                    ) {
                        Text("End: ${formatTime(endTimeMinutes)}")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val threshold = thresholdStr.toFloatOrNull()
                    if (name.isNotBlank() && threshold != null) {
                        val newConfig = initialConfig?.copy(
                            name = name,
                            threshold = threshold,
                            startTimeMinutes = startTimeMinutes,
                            endTimeMinutes = endTimeMinutes
                        ) ?: CustomAlertConfig(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            type = defaultType,
                            threshold = threshold,
                            startTimeMinutes = startTimeMinutes,
                            endTimeMinutes = endTimeMinutes,
                            enabled = true
                        )
                        onSave(newConfig)
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank() && thresholdStr.toFloatOrNull() != null
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
