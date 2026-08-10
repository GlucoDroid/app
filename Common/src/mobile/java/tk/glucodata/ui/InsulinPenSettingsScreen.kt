@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tk.glucodata.InsulinPen
import tk.glucodata.InsulinPenManager
import tk.glucodata.R
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import java.text.DateFormat
import java.util.Date

@Composable
fun InsulinPenSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val enabled by InsulinPenManager.enabled.collectAsStateWithLifecycle()
    val pens by InsulinPenManager.pens.collectAsStateWithLifecycle()
    val presets by rememberInsulinPresets()
    val journalEnabled = remember(context) {
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
            .getBoolean("dashboard_journal_enabled", true)
    }

    // Held by serial, not by value: choosing an insulin replaces the record, and a captured
    // copy would keep the dialog showing the old selection.
    var editingSerial by remember { mutableStateOf<String?>(null) }
    var forgetTarget by remember { mutableStateOf<InsulinPen?>(null) }
    val editing = pens.firstOrNull { it.serial == editingSerial }
    val selectableInsulins = presets.filterNot(JournalInsulinPreset::isArchived)

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.insulin_pens_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item("pen_master") {
                MasterSwitchCard(
                    title = stringResource(R.string.insulin_pens_enable_title),
                    subtitle = stringResource(R.string.insulin_pens_enable_desc),
                    checked = enabled,
                    onCheckedChange = InsulinPenManager::setEnabled,
                    icon = Icons.Default.Vaccines,
                )
            }

            // Doses become journal entries, so with the journal switched off a scan would
            // land somewhere the reader never looks. Say so rather than silently working.
            if (enabled && !journalEnabled) {
                item("pen_journal_off") {
                    Text(
                        stringResource(R.string.insulin_pens_journal_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            if (enabled) {
                item("pen_how") {
                    Spacer(Modifier.size(16.dp))
                    HowToScanCard()
                }

                item("pen_list_label") {
                    Spacer(Modifier.size(16.dp))
                    SectionLabel(stringResource(R.string.insulin_pens_paired))
                }

                if (pens.isEmpty()) {
                    item("pen_list_empty") {
                        SettingsItem(
                            title = stringResource(R.string.insulin_pens_none),
                            subtitle = stringResource(R.string.insulin_pens_none_desc),
                            icon = Icons.Default.Vaccines,
                            iconTint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                } else {
                    itemsIndexed(pens, key = { _, pen -> pen.serial }) { index, pen ->
                        SettingsItem(
                            title = stringResource(R.string.insulin_pen_name, pen.serial),
                            subtitle = penSubtitle(pen),
                            icon = Icons.Default.Vaccines,
                            iconTint = MaterialTheme.colorScheme.primary,
                            showArrow = true,
                            position = cardPositionFor(index, pens.size),
                            onClick = { editingSerial = pen.serial },
                        )
                    }
                }
            }
        }
    }

    editing?.let { pen ->
        PenDialog(
            pen = pen,
            presets = selectableInsulins,
            onDismiss = { editingSerial = null },
            onSelected = { preset ->
                InsulinPenManager.setInsulin(pen.serial, preset.id, preset.displayName)
            },
            onForget = {
                editingSerial = null
                forgetTarget = pen
            },
        )
    }

    forgetTarget?.let { pen ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text(stringResource(R.string.insulin_pen_forget_confirm, pen.serial)) },
            text = { Text(stringResource(R.string.insulin_pen_forget_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    InsulinPenManager.forget(pen.serial)
                    forgetTarget = null
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun HowToScanCard() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(
                    Icons.Default.Contactless,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp).size(28.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.insulin_pens_how_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.insulin_pens_how_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * One pen holds one insulin, so the only thing there is to set is which one — plus the way
 * out when the pen is finished.
 */
@Composable
private fun PenDialog(
    pen: InsulinPen,
    presets: List<JournalInsulinPreset>,
    onDismiss: () -> Unit,
    onSelected: (JournalInsulinPreset) -> Unit,
    onForget: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insulin_pen_name, pen.serial)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.insulin_pen_choose_insulin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                InsulinPresetOptions(
                    presets = presets,
                    selectedId = pen.insulinPresetId,
                    onSelected = onSelected,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onForget) {
                Text(
                    stringResource(R.string.insulin_pen_forget),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
internal fun InsulinPresetOptions(
    presets: List<JournalInsulinPreset>,
    selectedId: Long,
    onSelected: (JournalInsulinPreset) -> Unit,
) {
    Column {
        presets.forEach { preset ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = preset.id == selectedId,
                        onClick = { onSelected(preset) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = preset.id == selectedId, onClick = { onSelected(preset) })
                Spacer(Modifier.width(12.dp))
                Text(preset.displayName, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun penSubtitle(pen: InsulinPen): String {
    val insulin = pen.insulinName ?: stringResource(R.string.insulin_pen_insulin_unset)
    val doses = stringResource(R.string.insulin_pen_dose_count, pen.importedDoseCount)
    val scan = if (pen.lastScanAt > 0L) {
        stringResource(
            R.string.insulin_pen_last_read,
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(pen.lastScanAt))
        )
    } else {
        stringResource(R.string.insulin_pen_never_read)
    }
    return "$insulin · $doses · $scan"
}

/** Presets are the journal's insulin library; a pen is tagged with one of them. */
@Composable
internal fun rememberInsulinPresets(): State<List<JournalInsulinPreset>> {
    val repository = remember { JournalRepository() }
    LaunchedEffect(repository) { repository.ensureDefaultInsulinPresets() }
    val flow = remember(repository) { repository.observeInsulinPresets() }
    return flow.collectAsStateWithLifecycle(initialValue = emptyList())
}

private fun cardPositionFor(index: Int, size: Int): CardPosition = when {
    size == 1 -> CardPosition.SINGLE
    index == 0 -> CardPosition.TOP
    index == size - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}
