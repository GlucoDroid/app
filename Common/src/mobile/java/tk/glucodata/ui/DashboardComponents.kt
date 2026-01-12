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
import androidx.compose.ui.unit.sp
import tk.glucodata.ui.theme.displayLargeExpressive
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.ui.draw.scale

import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.rotate as modifierRotate
import androidx.compose.runtime.getValue

@Composable
fun DashboardCombinedHeader(
    currentGlucose: String,
    currentRate: Float,
    viewMode: Int,
    latestPoint: GlucosePoint?,
    sensorName: String,
    daysRemaining: String,
    sensorStatus: String,
    activeSensors: List<String> = emptyList(),
    sensorProgress: Float = 0f, // Dynamic Fill Progress
    sensorHoursRemaining: Long = 999L, // Logic Switch
    currentDay: Int = 0, // Animation Trigger
    history: List<GlucosePoint> = emptyList() // Advanced Trend
) {
    // Determine Colors based on logic
    // Glucose: Error if low/high, else Primary Container (Tonal)
    val glucoseContainerColor = MaterialTheme.colorScheme.primaryContainer
    val glucoseContentColor = MaterialTheme.colorScheme.onPrimaryContainer

    // Sensor: Surface Variant (Tonal) vs Tertiary Container (Expiring)
    // FIX: Replaced fragile string matching (failed in Dutch, false positive in English for "1 days in use") 
    // with robust progress-based logic. >90% progress (approx <1.4 days left) triggers Expiring state.
    val isExpiring = sensorProgress > 0.90f
    // FIX: User reported "Silly" contrast in Dark Mode with surfaceVariant (too bright/green).
    // Switched to primaryContainer to match the Glucose Card (Left) for a cohesive dark look.
    val sensorContainerColor = if (isExpiring) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) // Darker variant
    val sensorContentColor = if (isExpiring) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer

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
            tk.glucodata.logic.TrendEngine.TrendResult(tk.glucodata.logic.TrendEngine.TrendState.Unknown, 0f, 0f, 0f, 0f)
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
        
        // 2. Secondary Value: Strictly follow ViewMode (dvs.secondaryStr).
        // Only show Delta if we are in a mode that supports it or if we explicitly want it.
        // The user reported "phantom negative values" in Auto/Raw modes, so we MUST NOT fallback to deltaString here.
        val secondaryText = dvs?.secondaryStr
        
        // 3. Adaptive Layout Params
        // "More compensation" -> Increased start padding to be substantial
        val startPadding = 24.dp // Unified padding or even larger if needed

        Card(
            modifier = Modifier
                .weight(0.7f)
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
                .weight(0.3f)
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
                        else -> androidx.compose.ui.graphics.Color(0xFF66BB6A) // Muted Green (400) - "Make it Green"
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(sensorProgress)
                            .background(fillColor.copy(alpha = 0.25f)) // Slightly increased opacity for visibility
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp), // Matched Vertical Padding (12dp)
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start
                ) {
                    // 0. Signal Quality Indicator (above sensor name)
                    if (trendResult.noiseLevel > 0f) {
                        SignalQualityIndicator(
                            noiseLevel = trendResult.noiseLevel,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                    
                    // 1. Sensor Name (Top Label)
                    if (sensorName.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = sensorName,
                                style = MaterialTheme.typography.labelMedium, // M3 Standard
                                color = sensorContentColor.copy(alpha = 0.7f),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false) // Allow shrinking for dots
                            )
                            
                            // Active Indicators (Dots) - Right of Name
                            // Hidden if only 1 active sensor
                            if (activeSensors.size > 1) {
                                Spacer(modifier = Modifier.width(6.dp))
                                activeSensors.forEach { serial ->
                                     Box(
                                         modifier = Modifier
                                             .size(6.dp)
                                             .background(tk.glucodata.ui.viewmodel.SensorColors.getColor(serial), androidx.compose.foundation.shape.CircleShape)
                                     )
                                     Spacer(modifier = Modifier.width(4.dp))
                                }
                            }
                        }
                    }


                    // 2. Main Status / Days (Hero Value)
                    // Priority: Status if critical, else Days "1 / 14"
                    val mainText = if (sensorStatus.isNotEmpty() && sensorStatus != "Ready") sensorStatus 
                                   else {
                                       if (sensorHoursRemaining <= 24) "$sensorHoursRemaining" + "h"
                                       else daysRemaining
                                   }
                    if (mainText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                             // Text First
                             Text(
                                text = mainText,
                                style = MaterialTheme.typography.labelLarge,
                                color = sensorContentColor,
                                maxLines = 1
                            )

                             // Icon: Always show for Main sensor, regardless of count
                             Spacer(modifier = Modifier.width(6.dp))
                             
                             if (sensorHoursRemaining <= 24) {
                                  // Urgent: Hourglass with Dynamic Speed
                                  val duration = (500 + (1500 * (sensorHoursRemaining / 24f))).toInt().coerceAtLeast(500)
                                  AnimatedHourglassIcon(
                                      color = sensorContentColor.copy(alpha = 0.8f),
                                      modifier = Modifier.size(14.dp),
                                      cycleDuration = duration
                                  )
                             } else {
                                  // Normal: Custom Calendar with Page Flip
                                  CustomCalendarIcon(
                                      color = sensorContentColor.copy(alpha = 0.6f),
                                      modifier = Modifier.size(13.dp),
                                      currentDay = currentDay
                                  )
                             }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomCalendarIcon(
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    currentDay: Int = 0
) {
    // 1. Subtle Idle "Breathing" Flip (0 -> -6 -> 0)
    val infiniteTransition = rememberInfiniteTransition(label = "CalendarFlip")
    val subtleFlip by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.keyframes {
                durationMillis = 8000 // Occasional flip
                0f at 0
                -8f at 200 using androidx.compose.animation.core.EaseOut // Flip up
                0f at 500 using androidx.compose.animation.core.EaseIn   // Settle
                0f at 8000
            }
        ),
        label = "CalendarPageFlip"
    )

    // 2. Prominent "Day Change" Flip (Triggered by currentDay)
    // 0 -> -25 -> 0 (Big Nod)
    val prominentFlip = remember { androidx.compose.animation.core.Animatable(0f) }
    
    androidx.compose.runtime.LaunchedEffect(currentDay) {
        if (currentDay > 0) { // Don't animate on initial 0
             prominentFlip.animateTo(
                 targetValue = -25f,
                 animationSpec = tween(300, easing = androidx.compose.animation.core.EaseOutBack)
             )
             prominentFlip.animateTo(
                 targetValue = 0f,
                 animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
             )
        }
    }

    val totalRotation = subtleFlip + prominentFlip.value

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Refine stroke: 1.5dp might be too thick for 13dp size. Let's try 1.25dp visual weight equivalence.
        // Actually, user said minimalist style. 1.2dp is fine.
        
        // 1. Body
        val cornerRadius = 2.dp.toPx()
        
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.25f),
            size = androidx.compose.ui.geometry.Size(w, h * 0.75f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx())
        )
        
        // "Substance in the middle": Draw 2 small horizontal lines (rows)
        // Positioned in the center of the body area.
        val bodyTop = h * 0.25f
        val bodyHeight = h * 0.75f
        val row1Y = bodyTop + (bodyHeight * 0.4f)
        val row2Y = bodyTop + (bodyHeight * 0.7f)
        val margin = w * 0.25f
        val lineWidth = w * 0.5f // Centered line
        
        drawLine(
            color = color.copy(alpha = 0.5f), // Fainter lines
            start = androidx.compose.ui.geometry.Offset(margin, row1Y),
            end = androidx.compose.ui.geometry.Offset(margin + lineWidth, row1Y),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
             color = color.copy(alpha = 0.5f),
             start = androidx.compose.ui.geometry.Offset(margin, row2Y),
             end = androidx.compose.ui.geometry.Offset(margin + lineWidth, row2Y),
             strokeWidth = 1.dp.toPx()
        )

        // 2. Header (Top Binding Bar) with "Page Flip" Rotation
        rotate(degrees = totalRotation, pivot = androidx.compose.ui.geometry.Offset(w / 2, 0f)) {
            // Filled Header
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(w, h * 0.25f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
                style = androidx.compose.ui.graphics.drawscope.Fill
            )
            
            // Rings (Cutouts)
            val ringX1 = w * 0.25f
            val ringX2 = w * 0.75f
            val ringH = h * 0.3f
            
            drawLine(
                color = androidx.compose.ui.graphics.Color.Transparent,
                start = androidx.compose.ui.geometry.Offset(ringX1, 0f),
                end = androidx.compose.ui.geometry.Offset(ringX1, ringH),
                strokeWidth = 1.dp.toPx(),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear 
            )
             drawLine(
                color = androidx.compose.ui.graphics.Color.Transparent, 
                start = androidx.compose.ui.geometry.Offset(ringX2, 0f),
                end = androidx.compose.ui.geometry.Offset(ringX2, ringH),
                strokeWidth = 1.dp.toPx(),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear
            )
        }
    }
}

@Composable
private fun AnimatedHourglassIcon(
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    cycleDuration: Int = 10000
) {
    val infiniteTransition = rememberInfiniteTransition(label = "HourglassFlip")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 180f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.keyframes {
                durationMillis = cycleDuration
                0f at 0
                180f at (cycleDuration * 0.4f).toInt() using androidx.compose.animation.core.EaseInOut
                180f at cycleDuration
            }
        ),
        label = "HourglassRotation"
    )

    Icon(
        imageVector = Icons.Rounded.HourglassEmpty,
        contentDescription = null,
        tint = color,
        modifier = modifier.modifierRotate(rotation)
    )
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

/**
 * Animated Signal Quality Indicator using xDrip-style noise (Poly Fit Error Variance).
 * Shows actual noise value - calculated via 2nd-degree polynomial regression.
 */
@Composable
fun SignalQualityIndicator(
    noiseLevel: Float, // Error Variance from Poly Fit
    modifier: Modifier = Modifier
) {
    // Color thresholds (Standard xDrip 0-200+ scale)
    // <10: Clean (Green), 10-25: Light (Lt Green), 25-60: Medium (Amber), >60: Heavy (Red)
    val color = when {
        noiseLevel < 10f -> androidx.compose.ui.graphics.Color(0xFF4CAF50)  // Green
        noiseLevel < 25f -> androidx.compose.ui.graphics.Color(0xFF8BC34A)  // Light Green
        noiseLevel < 60f -> androidx.compose.ui.graphics.Color(0xFFFFC107)  // Amber
        else -> androidx.compose.ui.graphics.Color(0xFFF44336)               // Red
    }
    
    // Animation: Pulse for medium+, Shake for heavy
    val infiniteTransition = rememberInfiniteTransition(label = "signalPulse")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (noiseLevel >= 25f) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (noiseLevel >= 60f) 300 else 600,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    val shakeRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (noiseLevel >= 60f) 8f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shakeRotation"
    )
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // Animated Icon
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = "Noise: $noiseLevel",
            tint = color,
            modifier = Modifier
                .size(14.dp)
                .scale(pulseScale)
                .modifierRotate(shakeRotation)
        )
        
        Spacer(modifier = Modifier.width(4.dp))
        
        // Raw noise value (xDrip-style, 1 decimal)
        Text(
            text = String.format(java.util.Locale.US, "%.1f", noiseLevel),
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.9f)
        )
    }
}


