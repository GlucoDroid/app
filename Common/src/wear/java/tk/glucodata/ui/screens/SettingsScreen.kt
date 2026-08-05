package tk.glucodata.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import tk.glucodata.BuildConfig
import tk.glucodata.R
import tk.glucodata.ui.WearNavigationRow
import tk.glucodata.ui.WearSectionTitle

@Composable
fun SettingsScreen(
    onOpenAlerts: () -> Unit,
    onOpenSensor: () -> Unit,
) {
    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp),
        ) {
            item {
                WearSectionTitle(stringResource(R.string.settings))
            }
            item {
                WearNavigationRow(stringResource(R.string.alarms), onClick = onOpenAlerts)
            }
            item {
                WearNavigationRow(stringResource(R.string.sensor), onClick = onOpenSensor)
            }
            item {
                var colorInRange by remember { mutableStateOf(tk.glucodata.ui.WearColorPrefs.colorInRange()) }
                WearNavigationRow(
                    stringResource(R.string.wear_color_in_range),
                    subtitle = if (colorInRange) stringResource(R.string.wear_color_in_range_on) else stringResource(R.string.wear_color_in_range_off),
                    onClick = {
                        colorInRange = !colorInRange
                        tk.glucodata.ui.WearColorPrefs.setColorInRange(colorInRange)
                        tk.glucodata.UiRefreshBus.requestDataRefresh()
                    },
                )
            }
            item {
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
