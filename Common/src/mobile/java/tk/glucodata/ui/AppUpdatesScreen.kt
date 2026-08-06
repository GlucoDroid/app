@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tk.glucodata.BuildConfig
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.update.AppUpdateController
import tk.glucodata.update.AppUpdateUiState
import tk.glucodata.update.UpdateChannel
import tk.glucodata.update.UpdateEligibility
import tk.glucodata.update.UpdateError
import tk.glucodata.update.UpdateStage

/**
 * "App updates" — the detail screen behind the Data management entry.
 *
 * The whole flow is here in one place, in the order the user walks it: what is installed, what
 * is available, download, verify, install. Nothing on this screen happens without a tap.
 */
@Composable
fun AppUpdatesScreen(navController: NavController) {
    val context = LocalContext.current
    val state by AppUpdateController.state.collectAsStateWithLifecycle()
    var showChannelDialog by remember { mutableStateOf(false) }

    // Returning from the "install unknown apps" settings page should pick the flow back up
    // rather than leaving the user on a card that still says "not allowed".
    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        AppUpdateController.clearError()
        if (UpdateEligibility.canRequestPackageInstalls(context) &&
            state.stage == UpdateStage.READY_TO_INSTALL
        ) {
            AppUpdateController.install(context)
        }
    }

    LaunchedEffect(Unit) { AppUpdateController.initialize(context) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_updates_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (!state.supported) {
                UnsupportedCard(state)
                Spacer(Modifier.height(12.dp))
                InstalledVersionRow(position = CardPosition.SINGLE)
                return@Column
            }

            UpdateHeroCard(
                state = state,
                onCheck = { AppUpdateController.checkNow(context) },
                onDownload = { AppUpdateController.startDownload(context) },
                onCancel = { AppUpdateController.cancelDownload(context) },
                onInstall = {
                    if (UpdateEligibility.canRequestPackageInstalls(context)) {
                        AppUpdateController.install(context)
                    } else {
                        context.launchInstallPermission(installPermissionLauncher::launch)
                    }
                },
                onGrantInstallPermission = {
                    context.launchInstallPermission(installPermissionLauncher::launch)
                }
            )

            SectionLabel(stringResource(R.string.app_updates_section_preferences))

            SettingsSwitchItem(
                title = stringResource(R.string.app_updates_auto_title),
                subtitle = stringResource(R.string.app_updates_auto_desc),
                checked = state.autoCheckEnabled,
                icon = Icons.Filled.CloudDownload,
                iconTint = MaterialTheme.colorScheme.secondary,
                position = CardPosition.TOP,
                onCheckedChange = { AppUpdateController.setAutoCheckEnabled(context, it) }
            )
            SettingsItem(
                title = stringResource(R.string.app_updates_channel_title),
                subtitle = stringResource(state.channel.labelRes()),
                showArrow = true,
                icon = Icons.Filled.Tune,
                iconTint = MaterialTheme.colorScheme.secondary,
                position = CardPosition.BOTTOM,
                onClick = { showChannelDialog = true }
            )

            SectionLabel(stringResource(R.string.app_updates_section_versions))

            InstalledVersionRow(position = CardPosition.TOP)
            SettingsItem(
                title = stringResource(R.string.app_updates_latest_title),
                subtitle = state.available?.let { update ->
                    val size = Formatter.formatShortFileSize(context, update.artifact.sizeBytes)
                    if (update.prerelease) {
                        stringResource(
                            R.string.app_updates_latest_prerelease_value,
                            update.versionName,
                            size
                        )
                    } else {
                        stringResource(R.string.app_updates_latest_value, update.versionName, size)
                    }
                } ?: stringResource(R.string.app_updates_latest_none),
                icon = Icons.Filled.SystemUpdate,
                iconTint = MaterialTheme.colorScheme.secondary,
                position = CardPosition.MIDDLE
            )
            SettingsItem(
                title = stringResource(R.string.app_updates_release_page_title),
                subtitle = "github.com/${BuildConfig.UPDATE_REPO}",
                showArrow = true,
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                iconTint = MaterialTheme.colorScheme.secondary,
                position = CardPosition.BOTTOM,
                onClick = { context.openReleasePage(state.available?.tagName) }
            )

            val notes = state.available?.notes?.trim().orEmpty()
            if (notes.isNotEmpty()) {
                SectionLabel(stringResource(R.string.app_updates_notes_title))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    if (showChannelDialog) {
        ChannelPickerDialog(
            current = state.channel,
            onSelect = {
                AppUpdateController.setChannel(context, it)
                showChannelDialog = false
            },
            onDismiss = { showChannelDialog = false }
        )
    }
}

/** The status card at the top: one state, one obvious next action. */
@Composable
private fun UpdateHeroCard(
    state: AppUpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onGrantInstallPermission: () -> Unit
) {
    val context = LocalContext.current
    val update = state.available
    val lastChecked = if (state.lastCheckAtMillis > 0L) {
        DateUtils.getRelativeTimeSpanString(
            state.lastCheckAtMillis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    } else {
        stringResource(R.string.app_updates_never_checked)
    }

    if (state.error == UpdateError.INSTALL_PERMISSION) {
        AppUpdateSurface(
            icon = Icons.Filled.Security,
            accent = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.app_updates_permission_title),
            body = stringResource(R.string.app_updates_permission_body),
            primaryLabel = stringResource(R.string.app_updates_permission_action),
            onPrimary = onGrantInstallPermission,
            elevated = true
        )
        return
    }

    when (state.stage) {
        UpdateStage.DOWNLOADING -> AppUpdateSurface(
            icon = Icons.Filled.CloudDownload,
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.app_updates_downloading_title),
            body = stringResource(
                R.string.app_updates_downloading_body,
                Formatter.formatShortFileSize(context, state.downloadedBytes),
                Formatter.formatShortFileSize(context, state.totalBytes)
            ),
            primaryLabel = stringResource(R.string.app_updates_action_cancel),
            onPrimary = onCancel,
            content = {
                LinearProgressIndicator(
                    progress = { state.downloadFraction },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )

        UpdateStage.VERIFYING -> AppUpdateSurface(
            icon = Icons.Filled.Security,
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.app_updates_verifying_title),
            body = stringResource(R.string.app_updates_verifying_body),
            content = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        )

        UpdateStage.READY_TO_INSTALL, UpdateStage.INSTALLING -> AppUpdateSurface(
            icon = Icons.Filled.SystemUpdate,
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.app_updates_ready_title, update?.versionName.orEmpty()),
            body = state.error?.let { updateErrorText(it) }
                ?: stringResource(R.string.app_updates_ready_body),
            primaryLabel = stringResource(R.string.app_updates_action_install),
            onPrimary = onInstall,
            secondaryLabel = stringResource(R.string.app_updates_action_discard),
            onSecondary = onCancel,
            elevated = true
        )

        UpdateStage.IDLE -> when {
            state.error != null -> AppUpdateSurface(
                icon = Icons.Filled.ErrorOutline,
                accent = MaterialTheme.colorScheme.error,
                title = stringResource(R.string.app_updates_error_title),
                body = updateErrorText(state.error),
                primaryLabel = stringResource(R.string.app_updates_action_retry),
                onPrimary = onCheck,
                elevated = true
            )

            update != null -> AppUpdateSurface(
                icon = Icons.Filled.SystemUpdate,
                accent = MaterialTheme.colorScheme.tertiary,
                title = stringResource(R.string.app_updates_card_title),
                body = stringResource(
                    R.string.app_updates_card_body,
                    update.versionName,
                    Formatter.formatShortFileSize(context, update.artifact.sizeBytes)
                ),
                primaryLabel = stringResource(R.string.app_updates_action_download),
                onPrimary = onDownload,
                elevated = true
            )

            state.checking -> AppUpdateSurface(
                icon = Icons.Filled.CloudDownload,
                accent = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.app_updates_checking_title),
                body = stringResource(R.string.app_updates_checking_body),
                content = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            )

            else -> AppUpdateSurface(
                icon = Icons.Filled.CheckCircle,
                accent = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.app_updates_up_to_date_title),
                body = stringResource(R.string.app_updates_last_checked, lastChecked),
                primaryLabel = stringResource(R.string.app_updates_action_check),
                onPrimary = onCheck
            )
        }
    }
}

@Composable
private fun UnsupportedCard(state: AppUpdateUiState) {
    val context = LocalContext.current
    when (state.blocker) {
        UpdateEligibility.Blocker.MANAGED_BY_STORE -> {
            val installer = remember(context) {
                UpdateEligibility.installerPackage(context).orEmpty()
            }
            AppUpdateSurface(
                icon = Icons.Filled.Storefront,
                accent = MaterialTheme.colorScheme.secondary,
                title = stringResource(R.string.app_updates_unsupported_store_title),
                body = stringResource(R.string.app_updates_unsupported_store_body, installer)
            )
        }

        else -> AppUpdateSurface(
            icon = Icons.Filled.Info,
            accent = MaterialTheme.colorScheme.secondary,
            title = stringResource(R.string.app_updates_unsupported_debug_title),
            body = stringResource(R.string.app_updates_unsupported_debug_body)
        )
    }
}

@Composable
private fun InstalledVersionRow(position: CardPosition) {
    SettingsItem(
        title = stringResource(R.string.app_updates_installed_title),
        subtitle = stringResource(
            R.string.app_updates_installed_value,
            BuildConfig.BASE_VERSION_NAME,
            BuildConfig.VERSION_CODE
        ),
        icon = Icons.Filled.Info,
        iconTint = MaterialTheme.colorScheme.secondary,
        position = position
    )
}

@Composable
private fun ChannelPickerDialog(
    current: UpdateChannel,
    onSelect: (UpdateChannel) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_updates_channel_title)) },
        text = {
            Column {
                UpdateChannel.entries.forEach { channel ->
                    SettingsItem(
                        title = stringResource(channel.labelRes()),
                        subtitle = stringResource(channel.descriptionRes()),
                        position = CardPosition.SINGLE,
                        onClick = { onSelect(channel) },
                        trailingContent = {
                            RadioButton(selected = channel == current, onClick = { onSelect(channel) })
                        }
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private fun UpdateChannel.labelRes(): Int = when (this) {
    UpdateChannel.STABLE -> R.string.app_updates_channel_stable
    UpdateChannel.PRERELEASE -> R.string.app_updates_channel_prerelease
}

private fun UpdateChannel.descriptionRes(): Int = when (this) {
    UpdateChannel.STABLE -> R.string.app_updates_channel_stable_desc
    UpdateChannel.PRERELEASE -> R.string.app_updates_channel_prerelease_desc
}

/** Android 8+ grants "install unknown apps" per source, so the target is our own package page. */
private fun android.content.Context.launchInstallPermission(launch: (Intent) -> Unit) {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:$packageName")
    )
    try {
        launch(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                getString(R.string.cgm_readiness_settings_unavailable),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

private fun android.content.Context.openReleasePage(tag: String?) {
    val url = if (tag.isNullOrBlank()) {
        "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest"
    } else {
        "https://github.com/${BuildConfig.UPDATE_REPO}/releases/tag/$tag"
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            this,
            getString(R.string.cgm_readiness_settings_unavailable),
            Toast.LENGTH_LONG
        ).show()
    }
}
