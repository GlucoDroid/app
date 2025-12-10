package tk.glucodata.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tk.glucodata.ui.viewmodel.DashboardViewModel
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.animation.Crossfade
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith

data class GlucosePoint(
    val value: Float, 
    val time: String, 
    val timestamp: Long = 0L, 
    val rawValue: Float = 0f,
    val rate: Float = 0f
)

fun getTrendIcon(rate: Float): ImageVector {
    return when {
        rate > 0.5f -> Icons.Default.KeyboardArrowUp
        rate < -0.5f -> Icons.Default.KeyboardArrowDown
        else -> Icons.Default.KeyboardArrowRight // Flat fallback
    }
}
fun getTrendDescription(rate: Float): String {
    return when {
        rate > 0 -> "The trend is rising by $rate percent."
        rate < 0 -> "The trend is falling by ${Math.abs(rate)} percent."
        else -> "The trend remains unchanged."
    }
}


enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

enum class TimeRange(val label: String, val hours: Int) {
    H1("1H", 1),
    H3("3H", 3),
    H6("6H", 6),
    H12("12H", 12),
    H24("24H", 24),
    D3("3D", 72),
    D7("7D", 168)

}

fun setComposeContent(activity: AppCompatActivity, legacyView: View?) {
    activity.setContent {
        val prefs = activity.getSharedPreferences(activity.packageName + "_preferences", Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        var themeMode by remember { mutableStateOf(ThemeMode.valueOf(savedTheme)) }

        JugglucoTheme(themeMode = themeMode) {
            MainApp(
                themeMode = themeMode,
                onThemeChanged = { newMode ->
                    themeMode = newMode
                    prefs.edit().putString("theme_mode", newMode.name).apply()
                }
            )
        }
    }
}



// ...

@Composable
fun JugglucoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        android.os.Build.VERSION.SDK_INT >= 31 -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = Color(0xFF90CAF9),
            secondary = Color(0xFF81D4FA),
            tertiary = Color(0xFFCE93D8),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.Black,
            onSecondary = Color.Black,
            onTertiary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        )
        else -> lightColorScheme(
            primary = Color(0xFF1565C0),
            secondary = Color(0xFF039BE5),
            tertiary = Color(0xFF7B1FA2),
            background = Color(0xFFFAFAFA),
            surface = Color.White,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onTertiary = Color.White,
            onBackground = Color.Black,
            onSurface = Color.Black
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@Composable
fun MainApp(themeMode: ThemeMode, onThemeChanged: (ThemeMode) -> Unit) {
    val navController = rememberNavController()
    val dashboardViewModel: DashboardViewModel = viewModel()
    val context = LocalContext.current
    
    // Handle back button to exit app when on start destination
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    
    BackHandler(enabled = currentRoute == "dashboard") {
        (context as? Activity)?.finish()
    }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                val currentRoute = currentBackStackEntry?.destination?.route

                val onNavigate = { route: String ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                // --- DASHBOARD ---
                NavigationBarItem(
                    icon = {
                        TabIcon(
                            isSelected = currentRoute == "dashboard",
                            selectedIcon = Icons.Filled.Home,
                            unselectedIcon = Icons.Outlined.Home,
                            description = "Dashboard"
                        )
                    },
                    label = { Text("Dashboard") },
                    selected = currentRoute == "dashboard",
                    onClick = { onNavigate("dashboard") }
                )

                // --- SENSORS ---
                NavigationBarItem(
                    icon = {
                        TabIcon(
                            isSelected = currentRoute == "sensors",
                            selectedIcon = Icons.Filled.Info,
                            unselectedIcon = Icons.Outlined.Info,
                            description = "Sensors"
                        )
                    },
                    label = { Text("Sensors") },
                    selected = currentRoute == "sensors",
                    onClick = { onNavigate("sensors") }
                )

                // --- SETTINGS ---
                NavigationBarItem(
                    icon = {
                        TabIcon(
                            isSelected = currentRoute == "settings",
                            selectedIcon = Icons.Filled.Settings,
                            unselectedIcon = Icons.Outlined.Settings,
                            description = "Settings"
                        )
                    },
                    label = { Text("Settings") },
                    selected = currentRoute == "settings",
                    onClick = { onNavigate("settings") }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding),
            // Use a fast fade (200ms) for a snappy feel that isn't jarring
            enterTransition = { fadeIn(animationSpec = tween(200)) },
            exitTransition = { fadeOut(animationSpec = tween(200)) },
            popEnterTransition = { fadeIn(animationSpec = tween(200)) },
            popExitTransition = { fadeOut(animationSpec = tween(200)) }
        ) {
            composable("dashboard") { DashboardScreen(dashboardViewModel) }
            composable("sensors") { SensorScreen() }
            composable("settings") { SettingsScreen(themeMode, onThemeChanged, dashboardViewModel) }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    // Refresh data whenever the screen is shown
    LaunchedEffect(Unit) {
        viewModel.refreshData()
    }

    val currentGlucose by viewModel.currentGlucose.collectAsState()
    val currentRate by viewModel.currentRate.collectAsState()
    val sensorName by viewModel.sensorName.collectAsState()
    val daysRemaining by viewModel.daysRemaining.collectAsState()
    
    val glucoseData by viewModel.glucoseHistory.collectAsState()
    val unit by viewModel.unit.collectAsState()
    val targetLow by viewModel.targetLow.collectAsState()
    val targetHigh by viewModel.targetHigh.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()

    Scaffold(
        floatingActionButton = {
            if (glucoseData.isEmpty()) {
                FloatingActionButton(onClick = {
                    tk.glucodata.MainActivity.launchQrScan()
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Sensor")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val latestPoint = remember(glucoseData) { glucoseData.maxByOrNull { it.timestamp } }


            // Current Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {

                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    // Removed title "Current Glucose"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.End) {
                            // 2. Calculate the display string based on ViewMode
                            // Mode 0 = Auto, Mode 1 = Raw, Mode 2 = Auto + Raw
                            val finalDisplayGlucose = remember(currentGlucose, viewMode, latestPoint) {
                                if (latestPoint != null && (viewMode == 1 || viewMode == 2)) {
                                    val rawVal = latestPoint.rawValue
                                    // Format: Use 1 decimal if < 30, otherwise integer
                                    val rawStr = if (rawVal < 30) String.format("%.1f", rawVal) else rawVal.toInt().toString()

                                    when (viewMode) {
                                        1 -> rawStr // Show ONLY Raw
                                        2 -> "$currentGlucose | $rawStr" // Show "Auto (Raw)"
                                        else -> currentGlucose
                                    }
                                } else {
                                    currentGlucose // Fallback or Standard Mode
                                }
                            }
                            Text(
                                text = finalDisplayGlucose,
                                style = MaterialTheme.typography.displayLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = unit,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = getTrendIcon(currentRate),
                            contentDescription = getTrendDescription(currentRate), // Extract logic to helper
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (sensorName.isNotEmpty()) {
                    Text(sensorName, style = MaterialTheme.typography.titleMedium)
                }
                if (daysRemaining.isNotEmpty()) {
                    Text(daysRemaining, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            // Chart
            Card(modifier = Modifier.fillMaxWidth().height(500.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    if (glucoseData.isNotEmpty()) {
                        InteractiveGlucoseChart(
                            fullData = glucoseData,
                            targetLow = targetLow,
                            targetHigh = targetHigh,
                            unit = unit,
                            viewMode = viewMode
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No data available")
                        }
                    }
                }
            }

            // Recent Readings List

            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(glucoseData.takeLast(10).reversed()) { item ->
                    ReadingRow(item, unit, viewMode)
                }
            }
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun InteractiveGlucoseChart(
    fullData: List<GlucosePoint>,
    targetLow: Float,
    targetHigh: Float,
    unit: String,
    viewMode: Int = 0
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = Color.Magenta.copy(alpha = 0.7f)
    val pointColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val targetBandColor = Color.Green.copy(alpha = 0.1f)
    val targetLineColor = Color.Green.copy(alpha = 0.5f)
    val hoverLineColor = Color.Red.copy(alpha = 0.8f)
    val averageLineColor = Color(0xFFFF9800).copy(alpha = 0.6f) 
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()

    val density = LocalContext.current.resources.displayMetrics.density
    val textPaint = remember(textColor) {
        android.graphics.Paint().apply {
            color = textColor
            textSize = 10f * density
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }
    val xTextPaint = remember(textColor) {
        android.graphics.Paint().apply {
            color = textColor
            textSize = 10f * density
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    // Viewport State
    val now = System.currentTimeMillis()
    // 1. Find the timestamp of the newest data point
    val latestDataTimestamp = remember(fullData) {
        fullData.maxOfOrNull { it.timestamp } ?: 0L
    }

// 2. Track the last timestamp we automatically scrolled to.
// using rememberSaveable ensures this survives screen rotation/navigation.
    var lastAutoScrolledTimestamp by rememberSaveable { mutableLongStateOf(0L) }

// 3. Persist the Zoom Level (visibleDuration)
    var visibleDuration by rememberSaveable { mutableLongStateOf(3L * 60 * 60 * 1000) }

// 4. Persist the Scroll Position (centerTime)
// We initialize it to 'now', but rememberSaveable will restore the PREVIOUS value
// if the app is simply redrawing or rotating.
    var centerTime by rememberSaveable { mutableLongStateOf(now - visibleDuration / 2) }

// 5. Logic: Snap to end ONLY if new data has arrived
    LaunchedEffect(latestDataTimestamp) {
        // If the data we just loaded is newer than the last time we auto-scrolled...
        if (latestDataTimestamp > lastAutoScrolledTimestamp) {
            // ...update the centerTime to show the latest data (now)
            centerTime = System.currentTimeMillis() - visibleDuration / 2

            // ...and update our tracker
            lastAutoScrolledTimestamp = latestDataTimestamp
        }
    }
// --- REPLACEMENT END ---

    val minDuration = 10L * 60 * 1000
    val maxDuration = 72L * 60 * 60 * 1000
    val minTime = fullData.minOfOrNull { it.timestamp } ?: (now - maxDuration)
    val maxTime = now

    // Touch Handling
    var selectedPoint by remember { mutableStateOf<GlucosePoint?>(null) }
    var touchX by remember { mutableStateOf<Float?>(null) }

    // Helper for finding point at X
    fun getPointAt(timeAtTap: Double): GlucosePoint? {
        val idx = fullData.binarySearch { it.timestamp.compareTo(timeAtTap.toLong()) }
        val exactPoint = if (idx >= 0) fullData[idx] else null
        if (exactPoint != null) return exactPoint

        val insertionPoint = -(idx + 1)
        if (insertionPoint > 0 && insertionPoint < fullData.size) {
            val p1 = fullData[insertionPoint - 1]
            val p2 = fullData[insertionPoint]
            val t1 = p1.timestamp
            val t2 = p2.timestamp
            if (t2 > t1) {
                val fraction = (timeAtTap - t1).toFloat() / (t2 - t1)
                val v1 = p1.value
                val v2 = p2.value
                val interpolatedValue = v1 + (v2 - v1) * fraction

                val rv1 = p1.rawValue
                val rv2 = p2.rawValue
                val interpolatedRaw = rv1 + (rv2 - rv1) * fraction

                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timeAtTap.toLong()))
                return GlucosePoint(interpolatedValue, timeStr, timeAtTap.toLong(), interpolatedRaw, 0f)
            }
        }
        return null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(fullData, visibleDuration, centerTime, viewMode) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val changes = event.changes
                            if (changes.isEmpty()) continue

                            val firstDown = changes.firstOrNull { it.pressed } ?: continue
                            val position = firstDown.position

                            val width = size.width.toFloat()
                            val height = size.height.toFloat()

                            val viewportStart = centerTime - visibleDuration / 2
                            val viewportEnd = centerTime + visibleDuration / 2

                            // Get value at touch X
                            val timeAtTouch = viewportStart + (position.x / width) * visibleDuration
                            val point = getPointAt(timeAtTouch.toDouble())

                            var isScrubbing = false

                            if (point != null) {
                                val paddingTime = visibleDuration / 10
                                val visiblePoints = fullData.filter {
                                    it.timestamp >= (viewportStart - paddingTime) &&
                                    it.timestamp <= (centerTime + visibleDuration / 2 + paddingTime)
                                }
                                if (visiblePoints.isNotEmpty()) {
                                    val visibleValues = visiblePoints.map {
                                        if (viewMode == 1) it.rawValue else it.value
                                    }
                                    var dataMin = visibleValues.minOrNull() ?: 0f
                                    var dataMax = visibleValues.maxOrNull() ?: 300f
                                    if (viewMode == 2) {
                                        val rawValues = visiblePoints.map { it.rawValue }
                                        dataMin = min(dataMin, rawValues.minOrNull() ?: dataMin)
                                        dataMax = max(dataMax, rawValues.maxOrNull() ?: dataMax)
                                    }
                                    val avgValue = visibleValues.average().toFloat()
                                    val yMin = min(min(dataMin, targetLow), avgValue) * 0.9f
                                    val yMax = max(max(dataMax, targetHigh), avgValue) * 1.1f
                                    val yRange = yMax - yMin

                                    val chartHeight = height - 20.dp.toPx()
                                    val valToY = { v: Float -> chartHeight - ((v - yMin) / yRange) * chartHeight }

                                    val pointY = valToY(if(viewMode==1) point.rawValue else point.value)
                                    if (kotlin.math.abs(pointY - position.y) < 150f) {
                                        isScrubbing = true
                                    }
                                    if (!isScrubbing && viewMode == 2) {
                                        val pointYRaw = valToY(point.rawValue)
                                        if (kotlin.math.abs(pointYRaw - position.y) < 150f) {
                                            isScrubbing = true
                                        }
                                    }
                                }
                            }

                            if (isScrubbing) {
                                touchX = position.x
                                selectedPoint = point
                                changes.forEach { it.consume() }

                                while (true) {
                                    val dragEvent = awaitPointerEvent()
                                    val dragChanges = dragEvent.changes
                                    val dragChange = dragChanges.firstOrNull()
                                    if (dragChange == null || !dragChange.pressed) {
                                        touchX = null
                                        selectedPoint = null
                                        break
                                    }

                                    touchX = dragChange.position.x
                                    val tAt = (centerTime - visibleDuration / 2) + (dragChange.position.x / width) * visibleDuration
                                    selectedPoint = getPointAt(tAt.toDouble())
                                    dragChange.consume()
                                }
                            } else {
                                do {
                                    val moveEvent = awaitPointerEvent()
                                    val moveChanges = moveEvent.changes
                                    val canceled = moveChanges.any { it.isConsumed }
                                    if (canceled) break

                                    val pointers = moveChanges.filter { it.pressed }
                                    if (pointers.isEmpty()) break

                                    // Calculate centroid
                                    var cx = 0f
                                    var cy = 0f
                                    pointers.forEach { cx += it.position.x; cy += it.position.y }
                                    cx /= pointers.size
                                    cy /= pointers.size

                                    // Calculate previous centroid
                                    var pcx = 0f
                                    var pcy = 0f
                                    pointers.forEach { pcx += it.previousPosition.x; pcy += it.previousPosition.y }
                                    pcx /= pointers.size
                                    pcy /= pointers.size

                                    // Calculate zoom
                                    var avgDist = 0f
                                    var pAvgDist = 0f
                                    pointers.forEach {
                                        avgDist += (it.position - Offset(cx, cy)).getDistance()
                                        pAvgDist += (it.previousPosition - Offset(pcx, pcy)).getDistance()
                                    }
                                    val z = if (pAvgDist > 0) avgDist / pAvgDist else 1f

                                    if (pointers.size > 1) {
                                         // Apply Zoom
                                        val oldDuration = visibleDuration.toFloat()
                                        val newDurationRaw = oldDuration / z
                                        val newDuration = newDurationRaw.toLong().coerceIn(minDuration, maxDuration)

                                        val viewportStartOld = centerTime - visibleDuration / 2
                                        val timeAtCentroid = viewportStartOld + (cx / width) * visibleDuration
                                        val newViewportStart = timeAtCentroid - (cx / width) * newDuration
                                        var newCenter = newViewportStart.toLong() + newDuration / 2

                                        visibleDuration = newDuration
                                        centerTime = newCenter.coerceIn(minTime, maxTime)
                                    }

                                    // Pan (1 or more fingers)
                                    val panDelta = Offset(cx - pcx, cy - pcy)
                                    val tShift = (panDelta.x / width) * visibleDuration
                                    centerTime -= tShift.toLong()
                                    centerTime = centerTime.coerceIn(minTime, maxTime)

                                    pointers.forEach { it.consume() }
                                } while (moveChanges.any { it.pressed })
                            }
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val bottomPadding = 20.dp.toPx() 
                val chartHeight = height - bottomPadding
                
                val viewportStart = centerTime - visibleDuration / 2
                val viewportEnd = centerTime + visibleDuration / 2

                val paddingTime = visibleDuration / 10
                val visiblePoints = fullData.filter { 
                    it.timestamp >= (viewportStart - paddingTime) && 
                    it.timestamp <= (viewportEnd + paddingTime) 
                }

                if (visiblePoints.isEmpty()) return@Canvas

                val visibleValues = visiblePoints.map { 
                    if (viewMode == 1) it.rawValue else it.value 
                }
                var dataMin = visibleValues.minOrNull() ?: 0f
                var dataMax = visibleValues.maxOrNull() ?: 300f
                
                if (viewMode == 2) {
                    val rawValues = visiblePoints.map { it.rawValue }
                    dataMin = min(dataMin, rawValues.minOrNull() ?: dataMin)
                    dataMax = max(dataMax, rawValues.maxOrNull() ?: dataMax)
                }
                
                val avgValue = visibleValues.average().toFloat()

                val yMin = min(min(dataMin, targetLow), avgValue) * 0.9f
                val yMax = max(max(dataMax, targetHigh), avgValue) * 1.1f
                val yRange = yMax - yMin

                fun timeToX(t: Long): Float = ((t - viewportStart).toFloat() / visibleDuration.toFloat()) * width
                fun valToY(v: Float): Float = chartHeight - ((v - yMin) / yRange) * chartHeight

                val targetTopY = valToY(targetHigh)
                val targetBottomY = valToY(targetLow)
                drawRect(
                    color = targetBandColor,
                    topLeft = Offset(0f, targetTopY),
                    size = androidx.compose.ui.geometry.Size(width, targetBottomY - targetTopY)
                )
                
                val avgY = valToY(avgValue)
                drawLine(
                    color = averageLineColor,
                    start = Offset(0f, avgY),
                    end = Offset(width, avgY),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(20f, 10f), 0f)
                )

                val steps = 5
                val stepVal = yRange / steps
                for (i in 0..steps) {
                    val v = yMin + stepVal * i
                    val y = valToY(v)
                    drawLine(gridColor, Offset(0f, y), Offset(width, y), 1.dp.toPx())
                    
                    drawContext.canvas.nativeCanvas.drawText(
                        String.format("%.1f", v),
                        width - 8.dp.toPx(),
                        y - 6.dp.toPx(),
                        textPaint
                    )
                }
                
                val timeSteps = 4
                val timeStepVal = visibleDuration / timeSteps
                for (i in 0..timeSteps) {
                    val t = viewportStart + timeStepVal * i
                    val x = timeToX(t.toLong())
                    val timeLabel = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t.toLong()))
                    drawContext.canvas.nativeCanvas.drawText(
                        timeLabel,
                        x,
                        height - 5.dp.toPx(),
                        xTextPaint
                    )
                }

                if (visiblePoints.size > 1) {
                    if (viewMode == 0 || viewMode == 2) {
                        val path = Path()
                        path.moveTo(timeToX(visiblePoints.first().timestamp), valToY(visiblePoints.first().value))
                        for (i in 1 until visiblePoints.size) {
                            val p = visiblePoints[i]
                            path.lineTo(timeToX(p.timestamp), valToY(p.value))
                        }
                        drawPath(path, primaryColor, style = Stroke(width = 2.dp.toPx()))
                    }
                    
                    if (viewMode == 1 || viewMode == 2) {
                        val path = Path()
                        path.moveTo(timeToX(visiblePoints.first().timestamp), valToY(visiblePoints.first().rawValue))
                        for (i in 1 until visiblePoints.size) {
                            val p = visiblePoints[i]
                            path.lineTo(timeToX(p.timestamp), valToY(p.rawValue))
                        }
                        val color = if (viewMode == 2) secondaryColor else primaryColor
                        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                    }
                }

                touchX?.let { x ->
                    drawLine(
                        color = hoverLineColor,
                        start = Offset(x, 0f),
                        end = Offset(x, chartHeight),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }

                selectedPoint?.let { point ->
                    val px = timeToX(point.timestamp)
                    
                    if (px in 0f..width) {
                        if (viewMode == 0 || viewMode == 2) {
                            val py = valToY(point.value)
                            drawCircle(pointColor, 5.dp.toPx(), Offset(px, py))
                        }
                        if (viewMode == 1 || viewMode == 2) {
                            val pyRaw = valToY(point.rawValue)
                            val color = if (viewMode == 2) secondaryColor else pointColor
                            drawCircle(color, 5.dp.toPx(), Offset(px, pyRaw))
                        }
                    }
                }
            }
            
            selectedPoint?.let { point ->
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                        if (viewMode == 0 || viewMode == 2) {
                            Text(
                                text = "Auto: ${String.format("%.1f", point.value)} $unit",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                        if (viewMode == 1 || viewMode == 2) {
                            Text(
                                text = "Raw: ${String.format("%.1f", point.rawValue)} $unit",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (viewMode == 2) secondaryColor else MaterialTheme.colorScheme.inverseOnSurface
                            )
                        }
                        Text(
                            text = point.time,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }


        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TimeRange.values().forEach { range ->
                // Calculate this button's duration in milliseconds
                val rangeDuration = range.hours.toLong() * 60 * 60 * 1000

                // Check if it matches the current zoom level
                // We use a small buffer (1000ms) just in case floating point math during pinch-zoom
                // drifted the value slightly, though exact equality usually works too.
                val isSelected = kotlin.math.abs(visibleDuration - rangeDuration) < 1000

                FilterChip(
                    selected = isSelected,
                    onClick = {
                        visibleDuration = rangeDuration
                        centerTime = now - visibleDuration / 2
                    },
                    label = { Text(range.label) },

                )
            }
        }
    }
}

@Composable
fun ReadingRow(point: GlucosePoint, unit: String, viewMode: Int = 0) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("${point.time}")
        val valToUse = if (viewMode == 1) point.rawValue else point.value
        val valStr = if (valToUse < 30) String.format("%.1f", valToUse) else valToUse.toInt().toString()
        
        if (viewMode == 2) {
             val rawStr = if (point.rawValue < 30) String.format("%.1f", point.rawValue) else point.rawValue.toInt().toString()
             Text("$valStr | $rawStr $unit", fontWeight = FontWeight.Bold)
        } else {
             Text("$valStr $unit", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SearchScreen() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Search", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = "",
            onValueChange = {},
            label = { Text("Filter by date") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SettingsScreen(themeMode: ThemeMode, onThemeChanged: (ThemeMode) -> Unit, viewModel: DashboardViewModel = viewModel()) {
    val context = LocalContext.current
    val xDripEnabled by viewModel.xDripBroadcastEnabled.collectAsState()
    val patchedLibreEnabled by viewModel.patchedLibreBroadcastEnabled.collectAsState()

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Theme", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeMode.values().forEach { mode ->
                FilterChip(
                    selected = mode == themeMode,
                    onClick = { onThemeChanged(mode) },
                    label = { Text(mode.name.lowercase().capitalize()) }
                )
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        
        Text("Exchange Data", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))

        ListItem(
            headlineContent = { Text("xDrip Broadcast") },
            supportingContent = { Text("Libre (patched App)") },
            trailingContent = { 
                Switch(
                    checked = patchedLibreEnabled, 
                    onCheckedChange = { viewModel.togglePatchedLibreBroadcast(it) }
                ) 
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

//        ListItem(
//            headlineContent = { Text("Unit") },
//            supportingContent = { Text("mg/dL") },
//            trailingContent = { Switch(checked = true, onCheckedChange = {}) }
//        )
        
//        Button(
//            onClick = {
//                if (context is tk.glucodata.MainActivity) {
//                    context.showLegacyUI()
//                }
//            },
//            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
//        ) {
//            Text("Switch to Legacy View")
//        }
    }
}

private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
}

@Composable
fun SensorScreen(viewModel: tk.glucodata.ui.viewmodel.SensorViewModel = viewModel()) {
    val sensors by viewModel.sensors.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.refreshSensors()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                tk.glucodata.MainActivity.launchQrScan()
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add Sensor")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text("Sensors", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            
            if (sensors.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No sensors found")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sensors) { sensor ->
                        SensorCard(sensor, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun TabIcon(
    isSelected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    description: String
) {
    AnimatedContent(
        targetState = isSelected,
        transitionSpec = {
            if (targetState) {
                // Selected: Filled icon "pops" in (Scale + Fade)
                (scaleIn(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)))
                    .togetherWith(fadeOut(animationSpec = tween(200)))
            } else {
                // Deselected: Outline icon fades back in normal
                fadeIn(animationSpec = tween(200))
                    .togetherWith(scaleOut(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)))
            }
        },
        label = "TabIconAnimation"
    ) { selected ->
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = description
        )
    }
}


@Composable
fun SensorCard(sensor: tk.glucodata.ui.viewmodel.SensorInfo, viewModel: tk.glucodata.ui.viewmodel.SensorViewModel) {
    var showTerminateDialog by remember { mutableStateOf(false) }
    var showForgetDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    if (showTerminateDialog) {
        AlertDialog(
            onDismissRequest = { showTerminateDialog = false },
            title = { Text("Disconnect Sensor?") },
            text = { Text("This will permanently stop the sensor session. Are you sure?") },
            confirmButton = {
                TextButton(onClick = { 
                    viewModel.terminateSensor(sensor.serial)
                    showTerminateDialog = false 
                }) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showTerminateDialog = false }) { Text("Cancel") }
            }
        )
    }
    
    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text("Forget Sensor?") },
            text = { Text("This will remove the sensor from the list. It may reappear if scanned again.") },
            confirmButton = {
                TextButton(onClick = { 
                    viewModel.forgetSensor(sensor.serial)
                    showForgetDialog = false 
                }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { showForgetDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset sensor?") },
            text = { Text("This will reset the Sibionics 2 sensor. Continue?") },
            confirmButton = {
                TextButton(onClick = { 
                    viewModel.resetSensor(sensor.serial)
                    showResetDialog = false 
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Reset calibration?") },
            text = { Text("Clear all calibration data and restart algorithm?") },
            confirmButton = {
                TextButton(onClick = { 
                    viewModel.clearCalibration(sensor.serial)
                    showClearDialog = false 
                }) { Text("Reset calibration") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Reset all?") },
            text = { Text("Clear all old data, calibrations, and reset the sensor?") },
            confirmButton = {
                TextButton(onClick = { 
                    viewModel.clearAll(sensor.serial)
                    showClearAllDialog = false 
                }) { Text("Reset all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(sensor.serial, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Status:", style = MaterialTheme.typography.bodyMedium)
                Text(sensor.connectionStatus, style = MaterialTheme.typography.bodyMedium)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Address:", style = MaterialTheme.typography.bodyMedium)
                Text(sensor.deviceAddress, style = MaterialTheme.typography.bodyMedium)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Started:", style = MaterialTheme.typography.bodyMedium)
                // APPLY FORMATTER HERE
                Text(formatSensorTime(sensor.starttime), style = MaterialTheme.typography.bodyMedium)
            }
            if (sensor.officialEnd.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Ends Officially:", style = MaterialTheme.typography.bodyMedium)
                    // APPLY FORMATTER HERE
                    Text(formatSensorTime(sensor.officialEnd), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (sensor.expectedEnd.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Expected End:", style = MaterialTheme.typography.bodyMedium)
                    // APPLY FORMATTER HERE
                    Text(formatSensorTime(sensor.expectedEnd), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Streaming:", style = MaterialTheme.typography.bodyMedium)
                Text(if (sensor.streaming) "Enabled" else "Disabled", style = MaterialTheme.typography.bodyMedium)
            }
            if (sensor.rssi != 0) {
                 Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("RSSI:", style = MaterialTheme.typography.bodyMedium)
                    Text("${sensor.rssi}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Calibration Mode
            Text("Calibration Algorithm:", style = MaterialTheme.typography.labelMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val modes = listOf("Auto", "Raw", "Auto+Raw")
                modes.forEachIndexed { index, title ->
                    FilterChip(
                        selected = sensor.viewMode == index,
                        onClick = { viewModel.setCalibrationMode(sensor.serial, index) },
                        label = { Text(title) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { showTerminateDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Disconnect")
                }
                Button(onClick = { showForgetDialog = true }) {
                    Text("Forget")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { showResetDialog = true }) { Text("Reset sensor") }
                Button(onClick = { showClearDialog = true }) { Text("Reset calibration") }
                Button(onClick = { showClearAllDialog = true }) { Text("Reset all") }
            }
        }
    }
}

// Helper to format timestamps or date strings into "Mon 20.05.2024 14:30"
fun formatSensorTime(rawTime: String): String {
    if (rawTime.isBlank()) return ""

    val outputFormat = java.text.SimpleDateFormat("EEE dd.MM.yyyy HH:mm", java.util.Locale.getDefault())

    // 1. Try as Unix Timestamp (Digits only)
    if (rawTime.all { it.isDigit() }) {
        val timestamp = rawTime.toLongOrNull()
        if (timestamp != null) {
            val millis = if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
            return outputFormat.format(java.util.Date(millis))
        }
    }

    // 2. Try as Full Date "yyyy-MM-dd HH:mm:ss"
    try {
        val fullDateParser = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val date = fullDateParser.parse(rawTime)
        if (date != null) return outputFormat.format(date)
    } catch (e: Exception) {
        // Ignore and try next format
    }

    // 3. Try as Partial Date "MM-dd HH:mm:ss" (What you likely have)
    try {
        val partialParser = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val date = partialParser.parse(rawTime)

        if (date != null) {
            // Fix the Year (partial date defaults to 1970)
            val cal = java.util.Calendar.getInstance()
            cal.time = date

            // Set to Current Year
            val nowCal = java.util.Calendar.getInstance()
            val currentYear = nowCal.get(java.util.Calendar.YEAR)
            cal.set(java.util.Calendar.YEAR, currentYear)

            // Smart Logic: If the resulting date is more than 30 days in the future,
            // it likely belongs to the PREVIOUS year (e.g. It's Jan 2025, but date is "12-31")
            // Sensors usually don't have future start dates.
            if (cal.timeInMillis > System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000)) {
                cal.add(java.util.Calendar.YEAR, -1)
            }

            return outputFormat.format(cal.time)
        }
    } catch (e: Exception) {
        // Ignore
    }

    // 4. Fallback: Return raw string if nothing matched
    return rawTime
}