package tk.glucodata.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import tk.glucodata.Notify
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager

class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        turnScreenOnAndKeyguard()
        showAlarmContent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showAlarmContent()
    }

    private fun showAlarmContent() {
        val model = buildUiModel(intent)

        setContent {
            AlarmScreen(
                primaryGlucose = model.primaryGlucose,
                secondaryGlucose = model.secondaryGlucose,
                alarmLabel = model.alarmLabel,
                supportingText = model.supportingText,
                severity = model.severity,
                rate = model.rate,
                timeText = model.timeText,
                onSnooze = {
                    Notify.stopalarm()
                    model.alertType?.let { SnoozeManager.snooze(it, model.snoozeMinutes) }
                    cancelAlarmNotification()
                    finish()
                },
                onDismiss = {
                    Notify.stopalarm()
                    model.alertType?.let {
                        SnoozeManager.clearSnooze(it)
                        AlertStateTracker.onAlertDismissed(it)
                    }
                    cancelAlarmNotification()
                    finish()
                }
            )
        }
    }

    private fun buildUiModel(intent: Intent): AlarmUiModel {
        val rawValue = intent.getStringExtra(EXTRA_GLUCOSE_VAL).orEmpty()
        val rawMessage = intent.getStringExtra(EXTRA_ALARM_MESSAGE).orEmpty()
        val rate = intent.getFloatExtra(EXTRA_RATE, Float.NaN)
        val alertType = AlertType.fromId(intent.getIntExtra(EXTRA_ALERT_TYPE_ID, -1))

        val glucoseSource = rawValue.ifBlank { rawMessage }
        val (parsedValueRaw, parsedValueMessage) = parseGlucoseString(glucoseSource)
        val glucoseParts = parsedValueRaw
            .split('/')
            .map { normalizeAlarmValue(it) }
            .filter { it.isNotEmpty() }
        val primaryGlucose = glucoseParts.firstOrNull().orEmpty().ifBlank { "---" }
        val secondaryGlucose = glucoseParts.getOrNull(1)

        val alertLabel = alertType?.let { getString(it.nameResId) }
            ?: intent.getStringExtra(EXTRA_ALARM_TYPE).orEmpty().ifBlank { parsedValueMessage.ifBlank { rawMessage } }
                .ifBlank { getString(tk.glucodata.R.string.alarms) }

        val supportingText = parsedValueMessage.takeIf {
            it.isNotBlank() &&
                !it.equals(alertLabel, ignoreCase = true) &&
                !it.equals("low", ignoreCase = true) &&
                !it.equals("high", ignoreCase = true) &&
                !it.equals("alarm", ignoreCase = true)
        }
            ?: rawMessage.takeIf {
                it.isNotBlank() && !it.equals(alertLabel, ignoreCase = true) && !it.equals(rawValue, ignoreCase = true)
            }
            ?: rawValue.takeIf { it.isNotBlank() && it != parsedValueRaw }
            ?: ""

        val severity = when (alertType) {
            AlertType.LOW, AlertType.VERY_LOW, AlertType.PRE_LOW -> AlarmSeverity.LOW
            AlertType.HIGH, AlertType.VERY_HIGH, AlertType.PRE_HIGH, AlertType.PERSISTENT_HIGH -> AlarmSeverity.HIGH
            else -> when {
                supportingText.contains("low", ignoreCase = true) || alertLabel.contains("low", ignoreCase = true) -> AlarmSeverity.LOW
                supportingText.contains("high", ignoreCase = true) || alertLabel.contains("high", ignoreCase = true) -> AlarmSeverity.HIGH
                else -> AlarmSeverity.NEUTRAL
            }
        }

        val timeText = DateFormat.getTimeFormat(this).format(System.currentTimeMillis())
        val snoozeMinutes = alertType?.let { AlertRepository.loadConfig(it).defaultSnoozeMinutes } ?: 15

        return AlarmUiModel(
            primaryGlucose = primaryGlucose,
            secondaryGlucose = secondaryGlucose,
            alarmLabel = alertLabel,
            supportingText = supportingText,
            severity = severity,
            rate = rate,
            timeText = timeText,
            alertType = alertType,
            snoozeMinutes = snoozeMinutes
        )
    }

    private fun parseGlucoseString(input: String): Pair<String, String> {
        val numberRegex = Regex("(\\d+([.,]\\d+)?(\\s*[/]\\s*\\d+([.,]\\d+)?)?)")
        val match = numberRegex.find(input)

        return if (match != null) {
            val value = match.value
            var message = input.removeRange(match.range)
            val unitsToRemove = listOf(
                Notify.unitlabel,
                "mmol/L",
                "mg/dL",
                "mmol/l",
                "mg/dl",
                ",0",
                ".0"
            )
            for (unit in unitsToRemove) {
                if (unit.isNotEmpty()) {
                    message = message.replace(unit, "", ignoreCase = true)
                }
            }
            value to message.replace(Regex("\\s+"), " ").trim()
        } else {
            "---" to input.trim()
        }
    }

    private fun normalizeAlarmValue(raw: String): String {
        val token = raw.trim().replace(',', '.')
        if (token.isEmpty()) {
            return ""
        }
        val number = token.toDoubleOrNull() ?: return raw.trim().replace('.', decimalSeparator())
        val pattern = if (tk.glucodata.Applic.unit == 1) "0.0" else "0"
        val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
        return DecimalFormat(pattern, symbols).format(number)
    }

    private fun decimalSeparator(): Char = DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator

    private fun cancelAlarmNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(81432)
    }

    private fun turnScreenOnAndKeyguard() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    data class AlarmUiModel(
        val primaryGlucose: String,
        val secondaryGlucose: String?,
        val alarmLabel: String,
        val supportingText: String,
        val severity: AlarmSeverity,
        val rate: Float,
        val timeText: String,
        val alertType: AlertType?,
        val snoozeMinutes: Int
    )

    companion object {
        const val EXTRA_GLUCOSE_VAL = "EXTRA_GLUCOSE_VAL"
        const val EXTRA_ALARM_TYPE = "EXTRA_ALARM_TYPE"
        const val EXTRA_ALARM_MESSAGE = "EXTRA_ALARM_MESSAGE"
        const val EXTRA_ALERT_TYPE_ID = "EXTRA_ALERT_TYPE_ID"
        const val EXTRA_RATE = "EXTRA_RATE"

        fun createIntent(
            context: Context,
            glucoseVal: String,
            alarmType: String,
            alarmMessage: String,
            alertTypeId: Int,
            rate: Float
        ): Intent {
            return Intent(context, AlarmActivity::class.java).apply {
                putExtra(EXTRA_GLUCOSE_VAL, glucoseVal)
                putExtra(EXTRA_ALARM_TYPE, alarmType)
                putExtra(EXTRA_ALARM_MESSAGE, alarmMessage)
                putExtra(EXTRA_ALERT_TYPE_ID, alertTypeId)
                putExtra(EXTRA_RATE, rate)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }
}
