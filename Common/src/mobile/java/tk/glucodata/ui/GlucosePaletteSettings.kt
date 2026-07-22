@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.GlucoseRangeColors.Band
import tk.glucodata.GlucoseRangeColors.Palette
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.ExpressiveHueWheelPicker
import tk.glucodata.ui.components.SettingsItem

private val BASE_PRESETS = listOf(Palette.MUTED, Palette.VIBRANT, Palette.GDH_LIKE)
private val BAND_ORDER = listOf(Band.VERY_LOW, Band.LOW, Band.IN_RANGE, Band.HIGH, Band.VERY_HIGH)

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
fun GlucosePaletteCard(position: CardPosition = CardPosition.SINGLE) {
    val revision = GlucosePaletteState.revision
    val isDark = isSystemInDarkTheme()
    var showEditor by remember { mutableStateOf(false) }
    val activePreset = remember(revision) { GlucosePaletteState.palette() }
    val hasOverrides = remember(revision) { GlucosePaletteState.hasAnyOverride() }
    val subtitle = if (hasOverrides) {
        stringResource(R.string.glucose_palette_preset_custom)
    } else {
        stringResource(presetLabelRes(activePreset))
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
        GlucosePaletteEditorSheet(onDismiss = { showEditor = false })
    }
}

@Composable
private fun GlucosePaletteEditorSheet(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision = GlucosePaletteState.revision
    val isDark = isSystemInDarkTheme()
    val activePreset = remember(revision) { GlucosePaletteState.palette() }
    val hasOverrides = remember(revision) { GlucosePaletteState.hasAnyOverride() }
    var editingBand by remember { mutableStateOf<Band?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { CompactSheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            val band = editingBand
            if (band == null) {
                PaletteOverview(
                    activePreset = activePreset,
                    hasOverrides = hasOverrides,
                    isDark = isDark,
                    onPresetSelected = { GlucosePaletteState.setPalette(context, it) },
                    onBandSelected = { editingBand = it },
                    onResetAll = { GlucosePaletteState.clearOverrides(context) },
                    onDismiss = onDismiss
                )
            } else {
                key(band, revision) {
                    BandColorEditor(
                        band = band,
                        initialArgb = effectiveBandColor(band, isDark),
                        isOverridden = GlucosePaletteState.override(band) != null,
                        onBack = { editingBand = null },
                        onSave = { color ->
                            GlucosePaletteState.setOverride(context, band, color)
                            editingBand = null
                        },
                        onReset = {
                            GlucosePaletteState.setOverride(context, band, null)
                            editingBand = null
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.PaletteOverview(
    activePreset: Palette,
    hasOverrides: Boolean,
    isDark: Boolean,
    onPresetSelected: (Palette) -> Unit,
    onBandSelected: (Band) -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit
) {
    SheetHeader(
        title = stringResource(R.string.glucose_palette_edit_title),
        onClose = onDismiss
    )
    Text(
        text = stringResource(R.string.glucose_palette_sheet_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BAND_ORDER.forEachIndexed { index, band ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .background(
                        Color(effectiveBandColor(band, isDark)),
                        when (index) {
                            0 -> RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
                            BAND_ORDER.lastIndex -> RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
                            else -> RoundedCornerShape(4.dp)
                        }
                    )
            )
        }
    }

    Text(
        text = stringResource(R.string.glucose_palette_preset_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BASE_PRESETS.forEach { preset ->
            FilterChip(
                selected = activePreset == preset && !hasOverrides,
                onClick = { onPresetSelected(preset) },
                label = { Text(stringResource(presetLabelRes(preset))) }
            )
        }
    }

    Text(
        text = stringResource(R.string.glucose_palette_bands_label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BAND_ORDER.forEachIndexed { index, band ->
            val overridden = GlucosePaletteState.override(band) != null
            SettingsItem(
                title = stringResource(bandLabelRes(band)),
                subtitle = if (overridden) stringResource(R.string.glucose_palette_preset_custom) else null,
                onClick = { onBandSelected(band) },
                position = when (index) {
                    0 -> CardPosition.TOP
                    BAND_ORDER.lastIndex -> CardPosition.BOTTOM
                    else -> CardPosition.MIDDLE
                },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                .background(Color(effectiveBandColor(band, isDark)), RoundedCornerShape(10.dp))
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    }
    if (hasOverrides) {
        TextButton(onClick = onResetAll, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.glucose_palette_reset_all))
        }
    }
}

@Composable
private fun ColumnScope.BandColorEditor(
    band: Band,
    initialArgb: Int,
    isOverridden: Boolean,
    onBack: () -> Unit,
    onSave: (Int) -> Unit,
    onReset: () -> Unit
) {
    val initialHsv = remember(initialArgb) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialArgb or (0xFF shl 24), it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    val currentArgb = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
        }
        Text(
            text = stringResource(bandLabelRes(band)),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
    }
    Surface(
        modifier = Modifier
            .size(68.dp)
            .align(Alignment.CenterHorizontally),
        shape = CircleShape,
        color = Color(currentArgb),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {}
    Text(
        text = String.format("#%06X", currentArgb and 0xFFFFFF),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.align(Alignment.CenterHorizontally)
    )
    ExpressiveHueWheelPicker(hue = hue, onHueChange = { hue = it })
    PaletteSlider(
        label = stringResource(R.string.glucose_palette_saturation),
        value = saturation,
        onValueChange = { saturation = it }
    )
    PaletteSlider(
        label = stringResource(R.string.glucose_palette_brightness),
        value = brightness,
        onValueChange = { brightness = it }
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isOverridden) {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.glucose_palette_reset))
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.cancel))
        }
        Button(onClick = { onSave(currentArgb or (0xFF shl 24)) }) {
            Text(stringResource(R.string.save))
        }
    }
}

@Composable
private fun PaletteSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..1f)
    }
}

@Composable
private fun SheetHeader(title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
        }
    }
}
