package tk.glucodata.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.GlucoseRangeColors.Band
import tk.glucodata.GlucoseRangeColors.Palette
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsItem

// The three selectable base presets (CUSTOM is a derived state, not a chip).
private val BASE_PRESETS = listOf(Palette.MUTED, Palette.VIBRANT, Palette.GDH_LIKE)

private fun presetLabelRes(palette: Palette): Int = when (palette) {
    Palette.MUTED -> R.string.glucose_palette_preset_muted
    Palette.VIBRANT -> R.string.glucose_palette_preset_vibrant
    Palette.GDH_LIKE -> R.string.glucose_palette_preset_gdh
    Palette.CUSTOM -> R.string.glucose_palette_preset_custom
}

private val BAND_ORDER = listOf(
    Band.VERY_LOW, Band.LOW, Band.IN_RANGE, Band.HIGH, Band.VERY_HIGH
)

private fun bandLabelRes(band: Band): Int = when (band) {
    Band.VERY_LOW -> R.string.glucose_palette_band_very_low
    Band.LOW -> R.string.glucose_palette_band_low
    Band.IN_RANGE -> R.string.glucose_palette_band_in_range
    Band.HIGH -> R.string.glucose_palette_band_high
    Band.VERY_HIGH -> R.string.glucose_palette_band_very_high
}

/** Effective ARGB for a band (override or preset), for the given theme. */
private fun effectiveBandColor(band: Band, dark: Boolean): Int = when (band) {
    Band.VERY_LOW -> GlucoseRangeColors.veryLow(dark)
    Band.LOW -> GlucoseRangeColors.low(dark)
    Band.IN_RANGE -> GlucoseRangeColors.inRange(dark)
    Band.HIGH -> GlucoseRangeColors.high(dark)
    Band.VERY_HIGH -> GlucoseRangeColors.veryHigh(dark)
}

/**
 * Settings entry for the glucose colour palette. Renders a row with an inline
 * live preview of the five bands; tapping it opens the editor (preset choice +
 * per-band custom colours). Reads [GlucosePaletteState.revision] so both the
 * preview and the editor update the instant a colour changes.
 */
@Composable
fun GlucosePaletteCard(position: CardPosition = CardPosition.SINGLE) {
    val revision = GlucosePaletteState.revision
    val isDark = isSystemInDarkTheme()
    var showEditor by remember { mutableStateOf(false) }

    val activePreset = remember(revision) { GlucosePaletteState.palette() }
    val hasOverrides = remember(revision) { GlucosePaletteState.hasAnyOverride() }
    val subtitle = buildString {
        append(stringResource(presetLabelRes(activePreset)))
        if (hasOverrides) {
            append(" ")
            append(stringResource(R.string.glucose_palette_custom_suffix))
        }
    }

    SettingsItem(
        title = stringResource(R.string.glucose_palette_title),
        subtitle = subtitle,
        onClick = { showEditor = true },
        position = position,
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                BAND_ORDER.forEach { band ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .background(Color(effectiveBandColor(band, isDark)), CircleShape)
                    )
                }
            }
        }
    )

    if (showEditor) {
        GlucosePaletteEditorDialog(onDismiss = { showEditor = false })
    }
}

@Composable
private fun GlucosePaletteEditorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val revision = GlucosePaletteState.revision
    val isDark = isSystemInDarkTheme()
    val activePreset = remember(revision) { GlucosePaletteState.palette() }
    val hasOverrides = remember(revision) { GlucosePaletteState.hasAnyOverride() }
    var editingBand by remember { mutableStateOf<Band?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.glucose_palette_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.glucose_palette_preset_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BASE_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = activePreset == preset && !hasOverrides,
                            onClick = { GlucosePaletteState.setPalette(context, preset) },
                            label = { Text(stringResource(presetLabelRes(preset))) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.glucose_palette_bands_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                BAND_ORDER.forEach { band ->
                    val overridden = GlucosePaletteState.override(band) != null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .background(Color(effectiveBandColor(band, isDark)), RoundedCornerShape(8.dp))
                                .pointerInput(band) { detectTapGestures { editingBand = band } }
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(bandLabelRes(band)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (overridden) {
                            TextButton(onClick = { GlucosePaletteState.setOverride(context, band, null) }) {
                                Text(stringResource(R.string.glucose_palette_reset))
                            }
                        }
                        TextButton(onClick = { editingBand = band }) {
                            Text(stringResource(R.string.glucose_palette_edit))
                        }
                    }
                }

                if (hasOverrides) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { GlucosePaletteState.clearOverrides(context) }) {
                        Text(stringResource(R.string.glucose_palette_reset_all))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        }
    )

    editingBand?.let { band ->
        val initial = effectiveBandColor(band, isDark)
        HsvColorPickerDialog(
            title = stringResource(bandLabelRes(band)),
            initialArgb = initial,
            isOverridden = GlucosePaletteState.override(band) != null,
            onConfirm = { argb ->
                GlucosePaletteState.setOverride(context, band, argb)
                editingBand = null
            },
            onReset = {
                GlucosePaletteState.setOverride(context, band, null)
                editingBand = null
            },
            onDismiss = { editingBand = null }
        )
    }
}

/**
 * Lightweight HSV colour picker (saturation/value panel + hue slider). No
 * external dependency. Overrides intentionally apply to both themes.
 */
@Composable
private fun HsvColorPickerDialog(
    title: String,
    initialArgb: Int,
    isOverridden: Boolean,
    onConfirm: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val hsv = remember(initialArgb) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialArgb or (0xFF shl 24), it) }
    }
    var hue by remember(initialArgb) { mutableFloatStateOf(hsv[0]) }
    var sat by remember(initialArgb) { mutableFloatStateOf(hsv[1]) }
    var value by remember(initialArgb) { mutableFloatStateOf(hsv[2]) }

    val currentArgb = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
    val hexText = String.format("#%06X", currentArgb and 0xFFFFFF)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .background(Color(currentArgb), RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(hexText, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(12.dp))
                SaturationValuePanel(
                    hue = hue,
                    saturation = sat,
                    value = value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.4f)
                ) { s, v -> sat = s; value = v }
                Spacer(Modifier.height(12.dp))
                HueSlider(
                    hue = hue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                ) { hue = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentArgb or (0xFF shl 24)) }) {
                Text(stringResource(android.R.string.ok))
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
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    )
}

@Composable
private fun SaturationValuePanel(
    hue: Float,
    saturation: Float,
    value: Float,
    modifier: Modifier = Modifier,
    onChange: (Float, Float) -> Unit
) {
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    fun report(pos: Offset) {
        val w = panelSize.width.coerceAtLeast(1)
        val h = panelSize.height.coerceAtLeast(1)
        val s = (pos.x / w).coerceIn(0f, 1f)
        val v = (1f - pos.y / h).coerceIn(0f, 1f)
        onChange(s, v)
    }

    Box(
        modifier = modifier
            .onSizeChanged { panelSize = it }
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures { report(it) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { report(it) }) { change, _ -> report(change.position) }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(hueColor)
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val cx = saturation * size.width
            val cy = (1f - value) * size.height
            drawCircle(Color.White, radius = 7f, center = Offset(cx, cy))
            drawCircle(Color.Black, radius = 7f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        }
    }
}

@Composable
private fun HueSlider(
    hue: Float,
    modifier: Modifier = Modifier,
    onHueChange: (Float) -> Unit
) {
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val hueColors = remember {
        (0..360 step 30).map { Color(AndroidColor.HSVToColor(floatArrayOf(it.toFloat(), 1f, 1f))) }
    }

    fun report(x: Float) {
        val w = barSize.width.coerceAtLeast(1)
        onHueChange((x / w).coerceIn(0f, 1f) * 360f)
    }

    Box(
        modifier = modifier
            .onSizeChanged { barSize = it }
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures { report(it.x) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { report(it.x) }) { change, _ -> report(change.position.x) }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                brush = Brush.horizontalGradient(hueColors),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
            )
            val cx = (hue / 360f) * size.width
            drawCircle(Color.White, radius = size.height / 2f - 2f, center = Offset(cx, size.height / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
    }
}
