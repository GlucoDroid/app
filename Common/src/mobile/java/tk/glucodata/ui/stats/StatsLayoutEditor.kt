package tk.glucodata.ui.stats

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import tk.glucodata.R
import kotlin.math.roundToInt

private val EditorRowHeight = 56.dp

/**
 * Arrange mode.
 *
 * Reordering full statistics cards in place would mean dragging a 350 dp chart around
 * a scrolling list. Instead every card collapses to a uniform row for the duration of
 * the edit, which makes both the drag maths and the user's aim exact, then expands
 * back when they are done.
 */
@Composable
internal fun StatsLayoutEditor(
    layout: StatsLayoutState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.stats_arrange_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = stringResource(R.string.stats_arrange_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDone) {
                Text(text = stringResource(R.string.libre_setup_done))
            }
        }

        EditorSectionLabel(stringResource(R.string.stats_arrange_sections))
        ReorderableRows(
            items = layout.cardOrder,
            onReordered = StatsLayoutStore::setCardOrder
        ) { card, dragging, handle ->
            EditorRow(
                title = stringResource(card.titleResId),
                hidden = card in layout.hiddenCards,
                dragging = dragging,
                handle = handle,
                onToggleHidden = {
                    StatsLayoutStore.setCardHidden(card, card !in layout.hiddenCards)
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        EditorSectionLabel(
            stringResource(
                R.string.stats_arrange_metrics,
                layout.dashboardMetrics.size,
                StatsLayoutStore.MAX_DASHBOARD_METRICS
            )
        )
        ReorderableRows(
            items = layout.metricOrder,
            onReordered = StatsLayoutStore::setMetricOrder
        ) { metric, dragging, handle ->
            EditorRow(
                title = stringResource(metric.titleResId),
                hidden = metric in layout.hiddenMetrics,
                dragging = dragging,
                handle = handle,
                pinned = metric in layout.dashboardMetrics,
                pinnable = metric.pinnable,
                onTogglePinned = {
                    val accepted = StatsLayoutStore.setPinnedToDashboard(
                        metric,
                        metric !in layout.dashboardMetrics
                    )
                    if (!accepted) {
                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    }
                },
                onToggleHidden = {
                    StatsLayoutStore.setMetricHidden(metric, metric !in layout.hiddenMetrics)
                }
            )
        }

        TextButton(
            onClick = { StatsLayoutStore.resetLayout() },
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text(text = stringResource(R.string.stats_arrange_reset))
        }
    }
}

@Composable
private fun EditorSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

/**
 * Uniform-height drag reordering. Because every row is exactly [EditorRowHeight], the
 * drop target is just the accumulated offset divided by that height — no layout
 * inspection, no guessing at partially visible items.
 */
@Composable
private fun <T> ReorderableRows(
    items: List<T>,
    onReordered: (List<T>) -> Unit,
    row: @Composable (item: T, dragging: Boolean, handle: Modifier) -> Unit
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val rowHeightPx = with(density) { EditorRowHeight.toPx() }
    var working by remember(items) { mutableStateOf(items) }
    var dragIndex by remember(items) { mutableStateOf<Int?>(null) }
    var dragOffset by remember(items) { mutableFloatStateOf(0f) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        working.forEachIndexed { index, item ->
            val dragging = index == dragIndex
            val handle = Modifier.pointerInput(working, index) {
                detectDragGestures(
                    onDragStart = {
                        dragIndex = index
                        dragOffset = 0f
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    },
                    onDragEnd = {
                        dragIndex = null
                        dragOffset = 0f
                        onReordered(working)
                    },
                    onDragCancel = {
                        dragIndex = null
                        dragOffset = 0f
                        working = items
                    }
                ) { change, amount ->
                    change.consume()
                    dragOffset += amount.y
                    val from = dragIndex ?: return@detectDragGestures
                    val steps = (dragOffset / (rowHeightPx + 4f)).roundToInt()
                    if (steps == 0) return@detectDragGestures
                    val to = (from + steps).coerceIn(0, working.lastIndex)
                    if (to == from) return@detectDragGestures
                    working = working.moved(from, to)
                    dragOffset -= (to - from) * (rowHeightPx + 4f)
                    dragIndex = to
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(EditorRowHeight)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else 0f
                    }
            ) {
                row(item, dragging, handle)
            }
        }
    }
}

@Composable
private fun EditorRow(
    title: String,
    hidden: Boolean,
    dragging: Boolean,
    handle: Modifier,
    onToggleHidden: () -> Unit,
    pinned: Boolean = false,
    pinnable: Boolean = false,
    onTogglePinned: () -> Unit = {}
) {
    val container by animateColorAsState(
        targetValue = if (dragging) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "editorRowContainer"
    )
    val elevation: Dp by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "editorRowLift")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(EditorRowHeight)
            .graphicsLayer { shadowElevation = elevation.toPx() }
            .clip(statsCardShape(20.dp, 12.dp))
            .background(container)
            .padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = handle.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.stats_arrange_drag),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (hidden) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (pinnable) {
            IconButton(onClick = onTogglePinned) {
                Icon(
                    imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = stringResource(R.string.stats_arrange_pin),
                    tint = if (pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(onClick = onToggleHidden) {
            Icon(
                imageVector = if (hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = stringResource(R.string.stats_arrange_visibility),
                tint = if (hidden) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Bottom entry point into arrange mode, for people who never try a long press. */
@Composable
internal fun StatsEditLayoutButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
        horizontalArrangement = Arrangement.Center
    ) {
        TextButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = stringResource(R.string.stats_arrange_action))
        }
    }
}

/**
 * Long press that enters arrange mode without stealing anything from the card.
 *
 * `detectTapGestures` consumes the down event, which would have killed every tap and
 * scrub inside the cards — the TIR rows, the AGP chart, the calendar squares. This
 * watches the Initial pass instead, consumes nothing, and gives up the moment a child
 * consumes the gesture or the finger travels past touch slop.
 */
internal fun Modifier.longPressToArrange(onArrange: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var stillHeld = true
        val fired = try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                while (stillHeld) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed || change.isConsumed ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    ) {
                        stillHeld = false
                    }
                }
            }
            false
        } catch (_: PointerEventTimeoutCancellationException) {
            stillHeld
        }
        if (fired) onArrange()
    }
}
