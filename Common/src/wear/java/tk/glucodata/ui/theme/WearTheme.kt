@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package tk.glucodata.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import tk.glucodata.R

// Same IBM Plex Sans Variable the phone UI uses (shared res/font).
// Condensed width for data-dense watch layouts, mirroring the phone's
// DisplayLarge configuration in src/mobile Type.kt.
private fun plexFont(weight: Int, width: Float) = Font(
    R.font.ibm_plex_sans_var,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
    ),
)

val PlexRegular = FontFamily(plexFont(400, 90f))
val PlexMedium = FontFamily(plexFont(500, 90f))
val PlexDataCondensed = FontFamily(plexFont(500, 85f))

// Watch palette: the phone app's warm expressive dark language (cream primaries,
// sage/olive accents on near-black) rather than stock M3 blue. Glucose range
// colors always come from GlucoseRangeColors — never from this scheme.
private val WearColors = ColorScheme().copy(
    primary = Color(0xFFE8E0C9),          // cream — filled buttons read like the phone's
    onPrimary = Color(0xFF1C1B14),
    secondary = Color(0xFFB9C1A4),        // sage
    onSecondary = Color(0xFF20241A),
    tertiary = Color(0xFFA9C88C),         // healthy-sensor accent green
    onTertiary = Color(0xFF17210F),
    background = Color.Black,
    onBackground = Color(0xFFF1EDE0),
    onSurface = Color(0xFFEDE9DC),
    onSurfaceVariant = Color(0xFFA6A292),
    surfaceContainer = Color(0xFF24231D),
    surfaceContainerLow = Color(0xFF1A1916),
    surfaceContainerHigh = Color(0xFF2E2D26),
)

private fun wearTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = PlexDataCondensed),
        displayMedium = base.displayMedium.copy(fontFamily = PlexDataCondensed),
        displaySmall = base.displaySmall.copy(fontFamily = PlexDataCondensed),
        titleLarge = base.titleLarge.copy(fontFamily = PlexMedium),
        titleMedium = base.titleMedium.copy(fontFamily = PlexMedium),
        titleSmall = base.titleSmall.copy(fontFamily = PlexMedium),
        bodyLarge = base.bodyLarge.copy(fontFamily = PlexRegular),
        bodyMedium = base.bodyMedium.copy(fontFamily = PlexRegular),
        bodySmall = base.bodySmall.copy(fontFamily = PlexRegular),
        labelLarge = base.labelLarge.copy(fontFamily = PlexMedium),
        labelMedium = base.labelMedium.copy(fontFamily = PlexMedium),
        labelSmall = base.labelSmall.copy(fontFamily = PlexMedium),
    )
}

@Composable
fun WearJugglucoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WearColors,
        typography = wearTypography(),
        content = content,
    )
}
