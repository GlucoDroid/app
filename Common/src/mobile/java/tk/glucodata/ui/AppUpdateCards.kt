package tk.glucodata.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
 * True when [DashboardAppUpdateBanner] would draw something.
 *
 * The dashboard's LazyColumn uses `Arrangement.spacedBy`, which reserves its gap around *every*
 * item — including one that renders nothing. Call sites use this to skip emitting the item at
 * all, rather than pushing the whole dashboard down by a phantom row.
 */
@Composable
fun rememberAppUpdateBannerVisible(): Boolean {
    val context = LocalContext.current
    val state by AppUpdateController.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { AppUpdateController.initialize(context) }
    return state.showIntroCard || state.showUpdateCard
}

/**
 * Dashboard card for the in-app updater.
 *
 * At most one card is ever shown, and each shows at most once: the one-time opt-in card until
 * the user answers it, then an "update available" card until that particular release is
 * dismissed.
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
        AppUpdateCard(
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.app_updates_intro_title),
            body = stringResource(R.string.app_updates_intro_body)
        ) {
            AppUpdateTextAction(
                label = stringResource(R.string.app_updates_intro_decline),
                onClick = { AppUpdateController.answerIntro(context, false) }
            )
            AppUpdateFilledAction(
                label = stringResource(R.string.app_updates_intro_enable),
                accent = MaterialTheme.colorScheme.primary,
                onClick = { AppUpdateController.answerIntro(context, true) }
            )
        }
    }

    AnimatedVisibility(
        visible = state.showUpdateCard,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        val update = state.available
        AppUpdateCard(
            accent = MaterialTheme.colorScheme.tertiary,
            title = stringResource(R.string.app_updates_card_title),
            // Just version and size here — the detail screen carries the release notes.
            body = update?.let {
                stringResource(
                    R.string.app_updates_latest_value,
                    it.versionName,
                    Formatter.formatShortFileSize(context, it.artifact.sizeBytes)
                )
            },
            onDismiss = { AppUpdateController.dismissBanner(context) }
        ) {
            AppUpdateFilledAction(
                label = stringResource(R.string.app_updates_action_view),
                accent = MaterialTheme.colorScheme.tertiary,
                onClick = onOpenAppUpdates
            )
        }
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
 * The card shared by the dashboard banner and the App updates screen.
 *
 * Every metric here is copied from [tk.glucodata.ui.components.SettingsItem] on purpose —
 * 16 dp padding, a 40 dp/12 dp icon tile, a 12 dp gap — so the card's text column starts at
 * exactly the same x as the text in every settings row on the same screen. The first version
 * had the title next to the icon and the body under it, which put the two halves of one
 * sentence on two different left edges, neither of which matched the rows below.
 *
 * The only thing that deviates from a row is the corner radius: 20 dp, so it reads as a card
 * rather than another list item.
 */
@Composable
internal fun AppUpdateCard(
    accent: Color,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onDismiss: (() -> Unit)? = null,
    elevated: Boolean = false,
    content: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (elevated) {
            accent.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            if (icon != null) {
                AppUpdateIconTile(icon = icon, color = accent)
                Spacer(Modifier.width(12.dp))
            }
            // One column: title, body, progress and buttons all hang off a single left edge.
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (onDismiss != null) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.cgm_readiness_dismiss_action),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (!body.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (content != null) {
                    Spacer(Modifier.height(16.dp))
                    content()
                }

                if (actions != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
        }
    }
}

/** Same tile as a settings row's icon: 40 dp, 12 dp radius, 12 % tint, 24 dp glyph. */
@Composable
private fun AppUpdateIconTile(icon: ImageVector, color: Color) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun AppUpdateFilledAction(label: String, accent: Color, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = accent)
    ) {
        Text(label)
    }
}

@Composable
internal fun AppUpdateTextAction(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(label) }
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
