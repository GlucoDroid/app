package tk.glucodata.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ExpressiveHueWheelPicker(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val sweepColors = remember {
        listOf(
            Color(0xFFFF1744),
            Color(0xFFFF9100),
            Color(0xFFFFEA00),
            Color(0xFF00E676),
            Color(0xFF00B0FF),
            Color(0xFF651FFF),
            Color(0xFFFF1744)
        )
    }
    val handleHaloColor = MaterialTheme.colorScheme.surface

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(176.dp)
            .pointerInput(Unit) {
                fun updateHue(offset: Offset) {
                    val angle = Math.toDegrees(
                        atan2(
                            (offset.y - size.height / 2f).toDouble(),
                            (offset.x - size.width / 2f).toDouble()
                        )
                    ).toFloat()
                    onHueChange((angle + 450f) % 360f)
                }
                detectTapGestures(onTap = ::updateHue)
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val angle = Math.toDegrees(
                        atan2(
                            (change.position.y - size.height / 2f).toDouble(),
                            (change.position.x - size.width / 2f).toDouble()
                        )
                    ).toFloat()
                    onHueChange((angle + 450f) % 360f)
                }
            }
    ) {
        val ringWidth = 22.dp.toPx()
        val radius = (size.minDimension / 2f) - ringWidth
        drawCircle(
            brush = Brush.sweepGradient(sweepColors),
            radius = radius,
            style = Stroke(width = ringWidth, cap = StrokeCap.Round)
        )

        val angleRadians = Math.toRadians((hue - 90f).toDouble())
        val handleCenter = Offset(
            x = center.x + (cos(angleRadians) * radius).toFloat(),
            y = center.y + (sin(angleRadians) * radius).toFloat()
        )
        drawCircle(handleHaloColor, radius = 12.dp.toPx(), center = handleCenter)
        drawCircle(
            color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))),
            radius = 8.dp.toPx(),
            center = handleCenter
        )
    }
}
