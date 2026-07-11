package tk.glucodata.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import tk.glucodata.ui.screens.AlertsScreen
import tk.glucodata.ui.screens.CalibrationScreen
import tk.glucodata.ui.screens.ChartScreen
import tk.glucodata.ui.screens.MainScreen
import tk.glucodata.ui.screens.SensorScreen
import tk.glucodata.ui.screens.SettingsScreen

object WearRoutes {
    const val MAIN = "main"
    const val CHART = "chart"
    const val ALERTS = "alerts"
    const val SENSOR = "sensor"
    const val CALIBRATE = "calibrate"
    const val SETTINGS = "settings"
}

@Composable
fun WearApp() {
    val navController = rememberSwipeDismissableNavController()
    // System back (button/bezel gesture) pops the stack; at the root it
    // backgrounds the app like the legacy watch UI did, never finishing it.
    // Swipe-to-dismiss stays handled by SwipeDismissableNavHost.
    val activity = LocalContext.current as? Activity
    BackHandler {
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        } else {
            activity?.moveTaskToBack(true)
        }
    }
    AppScaffold {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = WearRoutes.MAIN,
        ) {
            composable(WearRoutes.MAIN) {
                MainScreen(
                    onOpenChart = { navController.navigate(WearRoutes.CHART) },
                    onOpenSettings = { navController.navigate(WearRoutes.SETTINGS) },
                )
            }
            composable(WearRoutes.CHART) { ChartScreen() }
            composable(WearRoutes.ALERTS) { AlertsScreen() }
            composable(WearRoutes.SENSOR) {
                SensorScreen(
                    onCalibrate = { navController.navigate(WearRoutes.CALIBRATE) },
                )
            }
            composable(WearRoutes.CALIBRATE) {
                CalibrationScreen(onDone = { navController.popBackStack() })
            }
            composable(WearRoutes.SETTINGS) {
                SettingsScreen(
                    onOpenAlerts = { navController.navigate(WearRoutes.ALERTS) },
                    onOpenSensor = { navController.navigate(WearRoutes.SENSOR) },
                )
            }
        }
    }
}
