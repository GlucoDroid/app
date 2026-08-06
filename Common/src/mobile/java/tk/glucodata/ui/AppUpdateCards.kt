@file:OptIn(ExperimentalLayoutApi::class)

package tk.glucodata.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.update.AppUpdateController
import tk.glucodata.update.UpdateError

/**
 * Dashboard card for the in-app updater, drawn in the same idiom as the CGM readiness banner.
 *
 * At most one card is ever shown, and each shows at most once:
 *  - the one-time opt-in card, until the user answers it;
 *  - an "update available" card, until the user dismisses that particular release.
 */
@Composable
fun DashboardAppUpdateBanner(
    modifier: Modifier = Modifier,
    onOpenAppUpdates: () -> Unit
) {
    val context = LocalContext.current
    val state by AppUpdateController.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { AppUpdateController.initialize(context) }

    AnimatedVisibility(
        visible = state.showIntroCard,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        AppUpdateSurface(
            icon = Icons.Filled.SystemUpdate,
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.app_updates_intro_title),
            body = stringResource(R.string.app_updates_intro_body),
            primaryLabel = stringResource(R.string.app_updates_intro_enable),
            onPrimary = { AppUpdateController.answerIntro(context, true) },
            secondaryLabel = stringResource(R.string.app_updates_intro_decline),
            onSecondary = { AppUpdateController.answerIntro(context, false) }
        )
    }

    AnimatedVisibility(
        visible = state.showUpdateCard,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        val update = state.available
        AppUpdateSurface(
            icon = Icons.Filled.SystemUpdate,
            accent = MaterialTheme.colorScheme.tertiary,
            title = stringResource(R.string.app_updates_card_title),
            body = if (update == null) {
                ""
            } else {
                stringResource(
                    R.string.app_updates_card_body,
                    update.versionName,
                    Formatter.formatShortFileSize(context, update.artifact.sizeBytes)
                )
            },
            primaryLabel = stringResource(R.string.app_updates_action_view),
            onPrimary = onOpenAppUpdates,
            onDismiss = { AppUpdateController.dismissBanner(context) }
        )
    }
}

/**
 * Data management entry. The subtitle carries the answer the user actually came for, so the
 * common case ("nothing to do") needs no navigation at all.
 */
@Composable
fun AppUpdatesSettingsItem(
    iconTint: Color,
    position: CardPosition,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    val state by AppUpdateController.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { AppUpdateController.initialize(context) }

    val subtitle = when {
        !state.supported -> stringResource(R.string.app_updates_state_unavailable)
        state.available != null ->
            stringResource(R.string.app_updates_state_available, state.available!!.versionName)
        state.checking -> stringResource(R.string.app_updates_state_checking)
        !state.autoCheckEnabled -> stringResource(R.string.app_updates_state_off)
        else -> stringResource(R.string.app_updates_state_up_to_date, state.installedVersionName)
    }

    SettingsItem(
        title = stringResource(R.string.app_updates_title),
        subtitle = subtitle,
        showArrow = true,
        icon = Icons.Filled.SystemUpdate,
        iconTint = iconTint,
        position = position,
        onClick = onOpen
    )
}

/**
 * The card body shared by the dashboard banner and the App updates screen hero. Mirrors
 * `CgmReadinessSummaryCard`: 20 dp corners, a tinted 44 dp status glyph, a hairline border in
 * the accent colour, and actions in a [FlowRow] so they wrap instead of clipping.
 */
@Composable
internal fun AppUpdateSurface(
    icon: ImageVector,
    accent: Color,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    elevated: Boolean = false,
    content: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (elevated) {
            accent.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
        shadowElevation = if (elevated) 1.dp else 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                StatusIconSurface(icon = icon, color = accent)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (body.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cgm_readiness_dismiss_action),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (content != null) {
                Spacer(Modifier.height(14.dp))
                content()
            }

            if (primaryLabel != null || secondaryLabel != null) {
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (primaryLabel != null && onPrimary != null) {
                        Button(
                            onClick = onPrimary,
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Text(primaryLabel)
                        }
                    }
                    if (secondaryLabel != null && onSecondary != null) {
                        OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
                    }
                }
            }
        }
    }
}

/** User-facing text for a failure. Never derived from exception messages: proguard strips those. */
@Composable
internal fun updateErrorText(error: UpdateError): String = stringResource(
    when (error) {
        UpdateError.NETWORK -> R.string.app_updates_error_network
        UpdateError.RATE_LIMITED -> R.string.app_updates_error_rate_limited
        UpdateError.PARSE -> R.string.app_updates_error_parse
        UpdateError.NO_ARTIFACT -> R.string.app_updates_error_no_artifact
        UpdateError.CHECKSUM -> R.string.app_updates_error_checksum
        UpdateError.SIGNATURE -> R.string.app_updates_error_signature
        UpdateError.PACKAGE_MISMATCH -> R.string.app_updates_error_package
        UpdateError.DOWNGRADE -> R.string.app_updates_error_downgrade
        UpdateError.STORAGE -> R.string.app_updates_error_storage
        UpdateError.INSTALL_PERMISSION -> R.string.app_updates_error_install_permission
        UpdateError.INSTALL_FAILED -> R.string.app_updates_error_install_failed
        UpdateError.CANCELLED -> R.string.app_updates_error_cancelled
    }
)
