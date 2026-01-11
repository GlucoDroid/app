package tk.glucodata.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.dp
import tk.glucodata.logic.TrendEngine

@Composable
fun TrendIndicator(
    trendResult: TrendEngine.TrendResult,
    modifier: Modifier = Modifier,
    color: Color = Color.Black
) {
    // "Optically Correct Arrow" Engine
    // 1. Visual: 90-degree Head, Round Caps/Joins, Optical Centering.
    // 2. Logic: User Tuned (25f).
    
    // Formula: Rate 2.0 -> 50 deg.
    val sensitivity = 25f
    val targetRotation = (-trendResult.velocity * sensitivity).coerceIn(-90f, 90f)

    // Animate Rotation
    val rotation by animateFloatAsState(
        targetValue = targetRotation,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy, 
            stiffness = Spring.StiffnessLow
        ),
        label = "TrendRotation"
    )
    
    // Dynamic Scale + "Active Reading" Pulse
    val speed = kotlin.math.abs(trendResult.velocity)
    val baseScale = 1.0f + (speed * 0.12f).coerceAtMost(0.5f)
    
    // Pulse Animation: Triggered when trendResult changes (New Reading)
    val pulseAnim = remember { Animatable(1f) }
    LaunchedEffect(trendResult) {
        // 1. Initial "Kick" (Immediate Visual Feedback)
        pulseAnim.snapTo(1.25f)
        pulseAnim.animateTo(
            targetValue = 1.0f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)
        )
        
        // 2. Decaying "Heartbeat" (Lingering Resonance)
        // User Request: "Stick for some time", "Slower after a while", "Stop slowly"
        
        // Pulse 1: Medium Speed, Medium Amplitude
        delay(250)
        pulseAnim.animateTo(1.15f, tween(300, easing = FastOutSlowInEasing))
        pulseAnim.animateTo(1.0f, tween(300, easing = FastOutSlowInEasing))
        
        // Pulse 2: Slower, Smaller (Slowing down)
        delay(400)
        pulseAnim.animateTo(1.08f, tween(500, easing = LinearOutSlowInEasing))
        pulseAnim.animateTo(1.0f, tween(500, easing = LinearOutSlowInEasing))
        
        // Pulse 3: Very Slow, Almost Invisible (Stopping slowly)
        delay(600)
        pulseAnim.animateTo(1.03f, tween(800, easing = LinearOutSlowInEasing))
        pulseAnim.animateTo(1.0f, tween(800, easing = LinearOutSlowInEasing))
    }
    
    val totalScale = baseScale * pulseAnim.value

    Canvas(modifier = modifier.size(24.dp)) {
        val cx = size.width / 2
        val cy = size.height / 2

        // Specs (User Tuned)
        val showDouble = speed > 2.0f
        
        // Fast: -> > (Arrow + Gap + Head)
        // Normal: ->
        
        val strokeWidth = size.width * 0.12f // 12% (Bold)
        
        // Base dimensions
        val headSpan = size.width * 0.55f
        val headDepth = headSpan / 2 
        val gap = headDepth * 0.5f // Gap between arrow tip and 2nd head
        
        // Calculate Total Length for Centering
        // Normal: Arrow Length ~ 0.6w
        // Fast: Shortened Arrow (~0.45w) + Gap + HeadDepth
        
        val arrowLenFactor = if (showDouble) 0.35f else 0.6f
        val arrowLen = size.width * arrowLenFactor * totalScale
        val totalVisualLen = if (showDouble) arrowLen + gap + headDepth else arrowLen
        
        rotate(rotation, pivot = Offset(cx, cy)) {
            val arrStyle = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )

            // Centering Logic
            // We want the center of "totalVisualLen" to be at cx
            val startX = cx - totalVisualLen/2
            
            // 1. Draw Main Arrow (->)
            // Shaft Start to Arrow Tip
            val arrowTipX = startX + arrowLen
            val arrowWingX = arrowTipX - headDepth
            
            val pArrow = Path().apply {
                // Shaft
                moveTo(startX, cy)
                lineTo(arrowTipX, cy)
                
                // Head 1
                moveTo(arrowWingX, cy - headSpan/2)
                lineTo(arrowTipX, cy)
                lineTo(arrowWingX, cy + headSpan/2)
            }
            drawPath(path = pArrow, color = color, style = arrStyle)
            
            if (showDouble) {
                // 2. Draw Second Head (>)
                // Positioned after Arrow Tip + Gap
                val secondTipX = arrowTipX + gap + headDepth
                val secondWingX = arrowTipX + gap
                
                val pSecond = Path().apply {
                    moveTo(secondWingX, cy - headSpan/2)
                    lineTo(secondTipX, cy)
                    lineTo(secondWingX, cy + headSpan/2)
                }
                drawPath(path = pSecond, color = color, style = arrStyle)
            }
        }
    }
}
