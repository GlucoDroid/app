package tk.glucodata.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import tk.glucodata.logic.TrendEngine

// Thin wrapper: the actual arrow lives in src/main TrendArrowCanvas so the
// wear hero card renders the identical arrow.
@Composable
fun TrendIndicator(
    trendResult: TrendEngine.TrendResult,
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    outlineColor: Color? = null,
    shadowColor: Color? = null
) {
    TrendArrowCanvas(
        velocity = trendResult.velocity,
        pulseKey = trendResult,
        modifier = modifier,
        color = color,
        outlineColor = outlineColor,
        shadowColor = shadowColor,
    )
}
