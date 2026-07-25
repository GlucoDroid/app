package tk.glucodata.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.GlucoseRangeColors.Band
import tk.glucodata.GlucoseRangeColors.Palette
import tk.glucodata.R
import tk.glucodata.ui.components.ColorSwatchButton

private val BASE_PRESETS = listOf(Palette.MUTED, Palette.VIBRANT, Palette.GDH_LIKE)

private fun presetLabelRes(palette: Palette): Int = when (palette) {
    Palette.MUTED -> R.string.glucose_palette_preset_muted
    Palette.VIBRANT -> R.string.glucose_palette_preset_vibrant
    Palette.GDH_LIKE -> R.string.glucose_palette_preset_gdh
    Palette.CUSTOM -> R.string.glucose_palette_preset_custom
}

private fun bandLabelRes(band: Band): Int = when (band) {
    Band.VERY_LOW -> R.string.glucose_palette_band_very_low
    Band.LOW -> R.string.glucose_palette_band_low
    Band.IN_RANGE -> R.string.glucose_palette_band_in_range
    Band.HIGH -> R.string.glucose_palette_band_high
    Band.VERY_HIGH -> R.string.glucose_palette_band_very_high
}

private fun effectiveBandColor(band: Band, dark: Boolean): Int = when (band) {
    Band.VERY_LOW -> GlucoseRangeColors.veryLow(dark)
    Band.LOW -> GlucoseRangeColors.low(dark)
    Band.IN_RANGE -> GlucoseRangeColors.inRange(dark)
    Band.HIGH -> GlucoseRangeColors.high(dark)
    Band.VERY_HIGH -> GlucoseRangeColors.veryHigh(dark)
}

@Composable
fun GlucosePalettePresetSelector(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val revision = GlucosePaletteState.revision
    val activePreset = remember(revision) { GlucosePaletteState.palette() }
    val hasOverrides = remember(revision) { GlucosePaletteState.hasAnyOverride() }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BASE_PRESETS.forEach { preset ->
            FilterChip(
                selected = activePreset == preset && !hasOverrides,
                onClick = {
                    GlucosePaletteState.setPalette(context, preset)
                    GlucosePaletteState.clearOverrides(context)
                },
                label = { Text(stringResource(presetLabelRes(preset))) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun GlucoseBandColorButton(
    band: Band,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    val context = LocalContext.current
    val revision = GlucosePaletteState.revision
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    var showDialog by remember { mutableStateOf(false) }
    val color = remember(revision, isDark, band) { effectiveBandColor(band, isDark) }

    ColorSwatchButton(
        color = Color(color),
        onClick = { showDialog = true },
        modifier = modifier,
        containerColor = containerColor
    )

    if (showDialog) {
        PaletteColorDialog(
            title = stringResource(bandLabelRes(band)),
            initialColor = color,
            isOverridden = GlucosePaletteState.override(band) != null,
            onDismiss = { showDialog = false },
            onReset = {
                GlucosePaletteState.setOverride(context, band, null)
                showDialog = false
            },
            onConfirm = { updatedColor ->
                GlucosePaletteState.setOverride(context, band, updatedColor)
                showDialog = false
            }
        )
    }
}

@Composable
private fun PaletteColorDialog(
    title: String,
    initialColor: Int,
    isOverridden: Boolean,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var colorState by remember(initialColor) { mutableStateOf(initialColor.toPaletteColorState()) }
    var colorText by remember(initialColor) { mutableStateOf(formatColorHex(initialColor)) }
    val composedColor = remember(colorState) { colorState.toColorInt() }
    val parsedColor = remember(colorText) { parseColorHex(colorText) }

    LaunchedEffect(colorState) {
        val resolvedHex = formatColorHex(composedColor)
        if (colorText != resolvedHex) {
            colorText = resolvedHex
        }
    }

    LaunchedEffect(parsedColor) {
        parsedColor?.let { parsed ->
            val parsedState = parsed.toPaletteColorState()
            if (parsedState != colorState) {
                colorState = parsedState
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    modifier = Modifier
                        .size(60.dp)
                        .align(Alignment.CenterHorizontally),
                    shape = CircleShape,
                    color = Color(composedColor)
                ) {}
                tk.glucodata.ui.components.ExpressiveHueWheelPicker(
                    hue = colorState.hue,
                    onHueChange = { hue -> colorState = colorState.copy(hue = hue) }
                )
                ColorControlRow(icon = Icons.Default.Palette) {
                    Slider(
                        value = colorState.saturation,
                        onValueChange = { saturation ->
                            colorState = colorState.copy(saturation = saturation.coerceIn(0f, 1f))
                        }
                    )
                }
                ColorControlRow(
                    indicator = {
                        Text(
                            text = "${(colorState.alpha * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                ) {
                    Slider(
                        value = colorState.alpha,
                        onValueChange = { alpha ->
                            colorState = colorState.copy(alpha = alpha.coerceIn(0f, 1f))
                        }
                    )
                }
                OutlinedTextField(
                    value = colorText,
                    onValueChange = { colorText = it.trim() },
                    label = { Text(stringResource(R.string.colors)) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(composedColor) },
                enabled = parsedColor != null
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                if (isOverridden) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.glucose_palette_reset))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

@Composable
private fun ColorControlRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    indicator: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Box(
                modifier = Modifier.width(24.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                indicator?.invoke()
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

private data class PaletteColorState(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float
)

private fun Int.toPaletteColorState(): PaletteColorState {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(this, hsv)
    return PaletteColorState(
        hue = hsv[0],
        saturation = hsv[1],
        value = hsv[2],
        alpha = ((this ushr 24) and 0xFF) / 255f
    )
}

private fun PaletteColorState.toColorInt(): Int {
    return AndroidColor.HSVToColor(
        (alpha * 255f).roundToInt(),
        floatArrayOf(hue, saturation, value)
    )
}

private fun formatColorHex(color: Int): String = "#%08X".format(color)

private fun parseColorHex(raw: String): Int? {
    val cleaned = raw.trim().removePrefix("#")
    val normalized = when (cleaned.length) {
        6 -> "FF$cleaned"
        8 -> cleaned
        else -> return null
    }
    return normalized.toLongOrNull(16)?.toInt()
}
