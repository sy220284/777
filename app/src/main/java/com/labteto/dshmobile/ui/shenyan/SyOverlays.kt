package com.labteto.dshmobile.ui.shenyan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.shenyan.designsystem.components.SyMaterialSurface
import com.shenyan.designsystem.components.SySceneBackgroundSurface
import com.shenyan.designsystem.components.SyMaterialTier
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import com.shenyan.designsystem.foundation.LocalSySceneBackdrop
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.shenyan.designsystem.components.SyToast
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.foundation.SyMotion
import com.shenyan.designsystem.foundation.LocalSyMotionTempo
import kotlinx.coroutines.delay

/** The only native generic dialog: V5 L4, correct back and explicit scrim behavior. */
@Composable
fun SyDialog(
    title: String?, onDismiss: () -> Unit,
    dismissOnScrimTap: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalSyColors.current
    val reduced = LocalSyReduceMotion.current
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .85f
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        if (shown) 1f else .92f,
        tween(SyMotion.duration(SyMotion.dialogMs, reduced, LocalSyMotionTempo.current)), label = "syDialogScale",
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnScrimTap,
        ),
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("sy-dialog-scrim").clickable(
            enabled = dismissOnScrimTap,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismiss,
        ), contentAlignment = Alignment.Center) {
            SyMaterialSurface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.92f).heightIn(max = maxHeight).scale(scale)
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(SyMetric.radiusSheet),
                tier = SyMaterialTier.L4_OVERLAY,
                border = BorderStroke(1.dp, p.border),
                shadowElevation = 16.dp,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!title.isNullOrBlank()) {
                        Text(title, color = p.text, style = MaterialTheme.typography.titleMedium)
                    }
                    content()
                }
            }
        }
    }
}

@Composable
fun SyFullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        content = content,
    )
}

/** Caller-owned feedback. Repeated identical messages restart their TTL. */
@Composable
fun rememberSyToast(): Pair<State<String?>, (String) -> Unit> {
    val state = remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.value, revision) {
        if (state.value != null) {
            delay(3000L)
            state.value = null
        }
    }
    return state to { message -> revision++; state.value = message }
}

@Composable
fun SyToastHost(state: Pair<State<String?>, (String) -> Unit>, modifier: Modifier = Modifier) {
    val reduced = LocalSyReduceMotion.current
    val message = state.first.value
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(SyMotion.duration(SyMotion.toastMs, reduced, LocalSyMotionTempo.current))) +
                slideInVertically(tween(SyMotion.duration(SyMotion.toastMs, reduced, LocalSyMotionTempo.current))) { -it / 3 },
            exit = fadeOut(tween(SyMotion.duration(SyMotion.toastMs, reduced, LocalSyMotionTempo.current))) +
                slideOutVertically(tween(SyMotion.duration(SyMotion.toastMs, reduced, LocalSyMotionTempo.current))) { -it / 3 },
        ) {
            Box(Modifier.padding(top = 12.dp).semantics {
                liveRegion = LiveRegionMode.Polite
            }) {
                SyToast(message.orEmpty())
            }
        }
    }
}

data class SyMenuItem(
    val text: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun SyMenu(anchor: @Composable () -> Unit, items: List<SyMenuItem>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.sizeIn(minWidth = SyMetric.touch, minHeight = SyMetric.touch)
            .clickable { expanded = true }, contentAlignment = Alignment.Center) { anchor() }
        SyPopupMenu(expanded = expanded, onDismiss = { expanded = false }, items = items)
    }
}

@Composable
fun SyPopupMenu(
    expanded: Boolean, onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    focusable: Boolean = true,
    containerColor: Color? = null,
    seamlessBackground: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalSyColors.current
    val scene = LocalSySceneBackdrop.current
    var regionY by remember { mutableStateOf(.5f) }
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        modifier = modifier.onGloballyPositioned { coordinates ->
            val backdrop = scene ?: return@onGloballyPositioned
            if (backdrop.viewportSize.height <= 0) return@onGloballyPositioned
            val centre = coordinates.positionOnScreen().y + coordinates.size.height / 2f
            val fraction = (centre - backdrop.viewportOrigin.y) / backdrop.viewportSize.height
            // Sample the same top/middle/bottom bands as every on-screen glass surface.
            val sampled = when {
                fraction < .30f -> .15f
                fraction < .74f -> .50f
                else -> .88f
            }
            if (regionY != sampled) regionY = sampled
        },
        shape = RoundedCornerShape(SyMetric.radiusControl),
        containerColor = if (seamlessBackground) Color.Transparent else
            (containerColor ?: com.shenyan.designsystem.components.syMaterialColor(
                SyMaterialTier.L4_OVERLAY, regionY)),
        tonalElevation = 0.dp,
        shadowElevation = if (seamlessBackground) 0.dp else 8.dp,
        border = if (seamlessBackground) null else BorderStroke(1.dp, p.border),
        properties = PopupProperties(
            focusable = focusable, dismissOnBackPress = true,
            dismissOnClickOutside = true, clippingEnabled = true,
        ),
        content = {
            if (seamlessBackground) {
                SySceneBackgroundSurface(
                    modifier = Modifier,
                    shape = RoundedCornerShape(SyMetric.radiusControl),
                    tag = "sySeamlessPopupScene",
                ) {
                    Column(content = content)
                }
            } else {
                content()
            }
        },
    )
}

@Composable
fun SyPopupMenu(
    expanded: Boolean, onDismiss: () -> Unit,
    items: List<SyMenuItem>,
    focusable: Boolean = true,
    containerColor: Color? = null,
    seamlessBackground: Boolean = true,
) {
    val p = LocalSyColors.current
    SyPopupMenu(expanded, onDismiss,
        focusable = focusable, containerColor = containerColor,
        seamlessBackground = seamlessBackground) {
        items.forEach { item ->
            DropdownMenuItem(
                text = {
                    Text(item.text, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (item.danger) p.dangerInk else p.text)
                },
                leadingIcon = if (item.icon == null) null else {
                    { Icon(item.icon, contentDescription = null,
                        tint = if (item.danger) p.dangerInk else p.textSecondary) }
                },
                onClick = { onDismiss(); item.onClick() },
                modifier = Modifier.heightIn(min = SyMetric.touch)
                    .testTag("menu-item:" + item.text),
            )
        }
    }
}

/** Keeps exact RTL and on-screen action placement instead of moving tool menus. */
@Composable
fun SyContextActionMenu(
    expanded: Boolean, onDismiss: () -> Unit, items: List<SyMenuItem>,
) {
    if (!expanded || items.isEmpty()) return
    val p = LocalSyColors.current
    val maxMenuWidth = (LocalConfiguration.current.screenWidthDp.dp - 24.dp).coerceAtLeast(220.dp)
    val provider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val x = when(layoutDirection) {
                    LayoutDirection.Ltr -> anchorBounds.right - popupContentSize.width
                    LayoutDirection.Rtl -> anchorBounds.left
                }.coerceIn(0,maxX)
                val below = anchorBounds.bottom
                val above = anchorBounds.top - popupContentSize.height
                val y = when {
                    below + popupContentSize.height <= windowSize.height -> below
                    above >= 0 -> above
                    else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                }
                return IntOffset(x,y)
            }
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true, dismissOnBackPress = true,
            dismissOnClickOutside = true, clippingEnabled = true)) {
        SySceneBackgroundSurface(
            modifier = Modifier.widthIn(max = maxMenuWidth),
            shape = RoundedCornerShape(SyMetric.radiusControl),
            tag = "sySeamlessContextScene",
        ) {
            // Wrap actions rather than allowing the popup to run off the display.
            FlowRow(
                modifier = Modifier.widthIn(max = maxMenuWidth).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items.forEach { item ->
                    Column(
                        Modifier.width(96.dp).heightIn(min = 72.dp)
                            .clickable { onDismiss(); item.onClick() }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    ) {
                        item.icon?.let {
                            Icon(it, contentDescription = null, modifier = Modifier.size(22.dp),
                                tint = if (item.danger) p.dangerInk else p.textSecondary)
                        }
                        Text(item.text, color = if (item.danger) p.dangerInk else p.text,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * Neutral, edge-to-edge photo inspection surface. Only image-specific controls are provided
 * by callers; this shared primitive owns back navigation and tap-outside dismissal.
 */
@Composable
fun SyFullscreenMediaOverlay(
    onDismiss: () -> Unit,
    dismissOnBackgroundTap: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalSyColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnBackgroundTap,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(colors.canvas)
                .testTag("syFullscreenMediaOverlay")
                .clickable(
                    enabled = dismissOnBackgroundTap,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
