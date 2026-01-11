package tk.glucodata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import tk.glucodata.ui.theme.displayLargeExpressive
import tk.glucodata.ui.theme.labelSmallPrim
import tk.glucodata.ui.theme.labelLargeExpressive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tk.glucodata.ui.theme.displayLargeExpressive

@Composable
fun DashboardCombinedHeader(
    currentGlucose: String,
    currentRate: Float,
    viewMode: Int,
    latestPoint: GlucosePoint?,
    sensorName: String,
    daysRemaining: String,
    sensorStatus: String,
    sensorProgress: Float = 0f, // Dynamic Fill Progress
    history: List<GlucosePoint> = emptyList() // Advanced Trend
) {
    // Determine Colors based on logic
    // Glucose: Error if low/high, else Primary Container (Tonal)
    val glucoseContainerColor = MaterialTheme.colorScheme.primaryContainer
    val glucoseContentColor = MaterialTheme.colorScheme.onPrimaryContainer

    // Sensor: Surface Variant (Tonal) vs Tertiary Container (Expiring)
    val isExpiring = daysRemaining.contains("1 day") || daysRemaining.contains("0 day")
    val sensorContainerColor = if (isExpiring) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val sensorContentColor = if (isExpiring) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    // Advanced Trend
    val trendResult = remember(history, latestPoint) {
        if (history.isNotEmpty()) {
             // Map Kotlin UI points to Native Java points for shared TrendEngine
             val nativeList = history.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) }
             tk.glucodata.logic.TrendEngine.calculateTrend(nativeList, useRaw = (viewMode == 1 || viewMode == 3))
        } else if (latestPoint != null) {
            // Fallback
             val nativeList = listOf(tk.glucodata.GlucosePoint(latestPoint.timestamp, latestPoint.value, latestPoint.rawValue))
             tk.glucodata.logic.TrendEngine.calculateTrend(nativeList, useRaw = (viewMode == 1 || viewMode == 3))
        } else {
            tk.glucodata.logic.TrendEngine.TrendResult(tk.glucodata.logic.TrendEngine.TrendState.Unknown, 0f, 0f, 0f)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min), // Equal height
        horizontalArrangement = Arrangement.spacedBy(4.dp) // 12dp Gap
    ) {
        // --- LEFT CARD: GLUCOSE (2/3) ---
        // HERO CARD: M3 Expressive Adaptive Layout
        
        // 1. Resolve Values using shared logic
        val dvs = remember(latestPoint, viewMode, currentGlucose) {
            if (latestPoint != null) {
                getDisplayValues(latestPoint, viewMode, "")
            } else {
                null
            }
        }

        val primaryText = dvs?.primaryStr ?: currentGlucose
        
        // 2. Secondary Value Priority: Raw Value (from user view settings) > Delta
        val deltaString = if (currentRate != 0f) String.format(java.util.Locale.getDefault(), "%+.1f", currentRate) else null
        val secondaryText = dvs?.secondaryStr ?: deltaString
        
        // 3. Adaptive Layout Params
        // "More compensation" -> Increased start padding to be substantial
        val startPadding = 24.dp // Unified padding or even larger if needed

        Card(
            modifier = Modifier
                .weight(0.66f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(
                containerColor = glucoseContainerColor, // Restored: primaryContainer
                contentColor = glucoseContentColor // Restored: onPrimaryContainer
            ),
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 8.dp, bottomEnd = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = startPadding, end = 16.dp, top = 12.dp, bottom = 12.dp), // Reduced vertical padding
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                // 1. Primary Value (Glucose)
                // Use AnimatedContent for smooth transitions
                AnimatedContent(
                    targetState = primaryText,
                    transitionSpec = {
                        (slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) { -it / 2 } + fadeIn(tween(200)))
                            .togetherWith(slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it / 2 } + fadeOut(tween(100)))
                    },
                    label = "GlucoseHeroAnimation"
                ) { glucoseValue ->
                    Text(
                        text = glucoseValue,
                        style = MaterialTheme.typography.displayLargeExpressive, // Restored: Old Font Style
                        color = glucoseContentColor
                    )
                }

                // 2. Secondary Value (Raw or Delta) with Separator
                if (secondaryText != null) {
                    Text(
                        text = " / ",
                        style = MaterialTheme.typography.displaySmall,
                        color = glucoseContentColor.copy(alpha = 0.5f), // Adapted color
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Text(
                        text = secondaryText,
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Medium),
                        color = glucoseContentColor.copy(alpha = 0.7f) // Adapted color
                    )
                }

                Spacer(modifier = Modifier.width(24.dp)) // Increased spacer for arrow

                // 3. Trend Icon (Tinted Primary)
                tk.glucodata.ui.components.TrendIndicator(
                    trendResult = trendResult,
                    modifier = Modifier.size(40.dp), // Restored: Bigger Arrow
                    color = glucoseContentColor
                )
            }
        }

        // --- RIGHT CARD: SENSOR (1/3) ---
        Card(
            modifier = Modifier
                .weight(0.33f)
                .fillMaxHeight(),
             colors = CardDefaults.cardColors(
                containerColor = sensorContainerColor,
                contentColor = sensorContentColor
            ),
            shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 28.dp, bottomEnd = 28.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Dynamic Fill Layer
                if (sensorProgress > 0f) {
                    val fillColor = when {
                        sensorProgress > 0.95f -> MaterialTheme.colorScheme.error
                        sensorProgress > 0.80f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(sensorProgress)
                            .background(fillColor.copy(alpha = 0.2f)) 
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp), // Matched Vertical Padding (12dp)
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start
                ) {
                    // 1. Sensor Name (Top Label)
                    if (sensorName.isNotEmpty()) {
                        Text(
                            text = sensorName,
                            style = MaterialTheme.typography.labelMedium, // M3 Standard
                            color = sensorContentColor.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }

                    // 2. Main Status / Days (Hero Value if possible, or clear text)
                    // Priority: Status if critical, else Days Remaining
                    val mainText = if (sensorStatus.isNotEmpty() && sensorStatus != "Ready") sensorStatus else daysRemaining
                    
                    if (mainText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = mainText,
                            style = MaterialTheme.typography.labelLarge, // Removed SemiBold
                            color = sensorContentColor,
                             maxLines = 2,
                             lineHeight = 16.sp
                        )
                    }
                    
                    // 3. Secondary info if replaced
                    if (mainText == sensorStatus && daysRemaining.isNotEmpty()) {
                         Text(
                            text = daysRemaining,
                            style = MaterialTheme.typography.bodySmall,
                            color = sensorContentColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
}
}

@Composable
fun RecentReadingsCard(
    recentReadings: List<GlucosePoint>,
    unit: String,
    viewMode: Int,
    content: @Composable (Int, GlucosePoint) -> Unit // Rendering the row with Index
) {
    if (recentReadings.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            // User Request: "readings row card 2 bottom edges 4dp radius"
            // Top: 16dp (Standard), Bottom: 4dp (Continuity)
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
            // User Request: "kill it" (shadows)
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column {
                recentReadings.forEachIndexed { index, item ->
                    content(index, item)
                }
            }
        }
    }
}
