package com.shenyan.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.LocalSyMotionTempo
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.foundation.SyMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val sheetStops = listOf(.50f, .75f, 1f)

internal fun snapSheetStop(fraction: Float): Int =
    sheetStops.indices.minBy { abs(sheetStops[it] - fraction) }

/** The height belongs to the sheet; dragging its handle cannot change or submit a field. */
@Composable
fun SySnapBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    bodyScrollState: ScrollState = rememberScrollState(),
    bodyViewport: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalSyColors.current
    var stop by rememberSaveable { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragHeight by remember { mutableFloatStateOf(.5f) }
    val settled by animateFloatAsState(
        sheetStops[stop],
        tween(SyMotion.duration(SyMotion.sheetMs, LocalSyReduceMotion.current, LocalSyMotionTempo.current)),
        label = "sheetSnapHeight",
    )
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = dismissEnabled,
            dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val available = maxHeight
            val pixels = with(LocalDensity.current) { available.toPx() }.coerceAtLeast(1f)
            val panelHeight = available * if (dragging) dragHeight else settled
            Box(Modifier.matchParentSize().background(colors.overlay)
                .then(if (dismissEnabled) Modifier.clickable(onClick = onDismiss) else Modifier))
            val shape = RoundedCornerShape(topStart = SyMetric.radiusSheet, topEnd = SyMetric.radiusSheet)
            SyMaterialSurface(
                modifier = Modifier.fillMaxWidth().height(panelHeight).testTag("sy-snap-sheet"),
                tier = SyMaterialTier.L4_OVERLAY,
                shape = shape,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth()
                            .draggable(
                                orientation = Orientation.Vertical,
                                enabled = dismissEnabled,
                                state = rememberDraggableState { delta ->
                                    dragHeight = (dragHeight - delta / pixels).coerceIn(.34f, 1f)
                                },
                                onDragStarted = { dragHeight = settled; dragging = true },
                                onDragStopped = {
                                    val released = dragHeight
                                    stop = snapSheetStop(released)
                                    dragging = false
                                    if (released < .40f) onDismiss()
                                },
                            )
                            .semantics { stateDescription = "${(sheetStops[stop] * 100).toInt()}%" }
                            .testTag("sy-snap-handle")
                            .padding(horizontal = SyMetric.gapSm),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        listOf("半屏", "四分之三", "全屏").forEachIndexed { index, label ->
                            SyButton(label, { stop = index }, enabled = dismissEnabled,
                                style = if (index == stop) SyButtonStyle.INFO else SyButtonStyle.GHOST,
                                compact = true, modifier = Modifier.weight(1f).testTag("sy-snap-stop-$index"))
                        }
                    }
                    SySheetScaffold(
                        title = title, showDragHandle = false,
                        onDismiss = if (dismissEnabled) onDismiss else null,
                        scrollable = true, maxBodyHeight = (panelHeight - 150.dp).coerceAtLeast(48.dp),
                        bodyScrollState = bodyScrollState, bodyViewport = bodyViewport,
                        content = content,
                    )
                }
            }
        }
    }
}

/** Radial file actions keep spoken labels and a list presentation on compact or enlarged layouts. */
@Composable
fun SyRadialActionMenu(
    title: String,
    entries: List<SyActionEntry>,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    if (entries.size !in 3..4 || configuration.screenWidthDp < 320 ||
        configuration.screenHeightDp < 370 || fontScale >= 1.45f || entries.any { it.destructive }) {
        SyActionSheet(title, entries, onChoose = onChoose, onDismiss = onDismiss)
        return
    }
    val colors = LocalSyColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SyMaterialSurface(
            modifier = Modifier.size(304.dp).testTag("sy-radial-actions"),
            tier = SyMaterialTier.L4_OVERLAY,
            shape = RoundedCornerShape(SyMetric.radiusSheet),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SyButton("关闭", onDismiss, compact = true, style = SyButtonStyle.GHOST)
                entries.forEachIndexed { index, entry ->
                    val angle = index * 2 * PI / entries.size - PI / 2
                    SyFlatSurface(
                        modifier = Modifier.offset((cos(angle) * 100).dp, (sin(angle) * 100).dp)
                            .width(92.dp).height(72.dp).testTag("menu-item:" + entry.title),
                        shape = RoundedCornerShape(SyMetric.radiusControl),
                        color = colors.materialL3,
                        enabled = entry.enabled,
                        onClick = { onDismiss(); onChoose(entry.id) },
                    ) {
                        Column(Modifier.fillMaxSize().padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            entry.icon?.let { Icon(it, null, Modifier.size(18.dp), tint = colors.linkInk) }
                            Text(entry.title, color = colors.text, textAlign = TextAlign.Center,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    val colors = LocalSyColors.current
    PullToRefreshBox(
        isRefreshing = refreshing, onRefresh = onRefresh, state = state, modifier = modifier,
        indicator = {
            if (LocalSyReduceMotion.current) {
                if (refreshing) SyActivityIndicator(
                    modifier = Modifier.align(Alignment.TopCenter).padding(SyMetric.gapSm).size(24.dp),
                    color = colors.linkInk,
                )
            } else {
                PullToRefreshDefaults.Indicator(
                    state = state, isRefreshing = refreshing,
                    containerColor = colors.materialL5, color = colors.linkInk,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        },
    ) { content() }
}
