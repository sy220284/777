package com.labteto.dshmobile.ui.shenyan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import com.shenyan.designsystem.foundation.LocalSySceneBackdrop
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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

import androidx.compose.ui.draw.clip
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
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        // A full-screen dialog owns another window and needs its own scene painter.
        SySceneBackgroundSurface(
            modifier = Modifier.fillMaxSize(),
            shape = androidx.compose.ui.graphics.RectangleShape,
            tag = "syFullScreenDialogBackdrop",
            content = content,
        )
    }
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
                    Text(item.text,
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

internal data class ContextMenuPlacement(val offset: IntOffset, val opensAbove: Boolean)

internal fun contextActionMenuPosition(
    anchor: IntRect,
    popup: IntSize,
    viewport: IntRect,
    direction: LayoutDirection,
    touchAnchor: IntOffset?,
    gap: Int,
    stayWithinAnchor: Boolean = false,
): ContextMenuPlacement {
    val anchorLeft = maxOf(viewport.left, anchor.left + gap)
    val anchorRight = minOf(viewport.right, anchor.right - gap)
    val fitsAnchor = stayWithinAnchor && anchorRight - anchorLeft >= popup.width
    val minX = if (fitsAnchor) anchorLeft else viewport.left
    val right = if (fitsAnchor) anchorRight else viewport.right
    val maxX = (right - popup.width).coerceAtLeast(minX)
    val maxY = (viewport.bottom - popup.height).coerceAtLeast(viewport.top)
    val alignedX = when (direction) {
        LayoutDirection.Ltr -> anchor.right - popup.width
        LayoutDirection.Rtl -> anchor.left
    }
    val x = (touchAnchor?.let { it.x - popup.width / 2 } ?: alignedX)
        .coerceIn(minX, maxX)
    val originTop = touchAnchor?.y ?: anchor.top
    val originBottom = touchAnchor?.y ?: anchor.bottom
    val below = originBottom + gap
    val above = originTop - popup.height - gap
    val opensAbove = below > maxY &&
        (above >= viewport.top || originTop - viewport.top > viewport.bottom - originBottom)
    return ContextMenuPlacement(
        IntOffset(x, (if (opensAbove) above else below).coerceIn(viewport.top, maxY)),
        opensAbove,
    )
}

/** A single action menu for conversation rows, message actions and header overflow. */
@Composable
fun SyContextActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<SyMenuItem>,
    touchAnchor: IntOffset? = null,
    primaryActionCount: Int = 0,
    stayWithinAnchor: Boolean = false,
) {
    val visibility = remember { MutableTransitionState(false) }
    val open = expanded && items.isNotEmpty()
    visibility.targetState = open
    val reduceMotion = LocalSyReduceMotion.current
    val tempo = LocalSyMotionTempo.current
    val enterMs = SyMotion.duration(SyMotion.selectMs, reduceMotion, tempo)
    val exitMs = SyMotion.duration(SyMotion.interactionColorMs, reduceMotion, tempo)
    val progress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(if (open) enterMs else exitMs, easing = SyMotion.ease),
        label = "contextMenuAnchorMotion",
    )
    if (!visibility.currentState && !visibility.targetState) return

    val palette = LocalSyColors.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val margin = with(density) { 8.dp.roundToPx() }
    val left = insets.getLeft(density, direction) + margin
    val top = insets.getTop(density) + margin
    val right = insets.getRight(density, direction) + margin
    val bottom = insets.getBottom(density) + margin
    val window = LocalWindowInfo.current.containerSize
    val maxWidth = with(density) { (window.width - left - right).toDp() }.coerceAtLeast(1.dp)
    val maxHeight = with(density) { (window.height - top - bottom).toDp() }.coerceAtLeast(1.dp)
    val gridCount = if (primaryActionCount >= 2 && maxWidth >= 208.dp && density.fontScale < 1.3f)
        primaryActionCount else 0
    val width = minOf(maxWidth, when {
        gridCount > 0 -> 208.dp
        density.fontScale >= 1.3f -> 220.dp
        else -> 184.dp
    })
    val origin = remember { mutableStateOf(TransformOrigin(.5f, 0f)) }
    val provider = remember(touchAnchor, left, top, right, bottom, margin, stayWithinAnchor) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val bounds = IntRect(left, top, maxOf(left, windowSize.width - right),
                    maxOf(top, windowSize.height - bottom))
                val placement = contextActionMenuPosition(
                    anchorBounds, popupContentSize, bounds, layoutDirection, touchAnchor, margin, stayWithinAnchor,
                )
                val anchorX = touchAnchor?.x ?: anchorBounds.center.x
                val fraction = (anchorX - placement.offset.x).toFloat() / maxOf(1, popupContentSize.width)
                origin.value = TransformOrigin(fraction.coerceIn(0f, 1f), if (placement.opensAbove) 1f else 0f)
                return placement.offset
            }
        }
    }
    val travel = with(density) { 5.dp.toPx() }
    val shape = RoundedCornerShape(SyMetric.radiusControl)
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = open, dismissOnBackPress = open, dismissOnClickOutside = open, clippingEnabled = true,
        ),
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(tween(enterMs, easing = SyMotion.ease)),
            exit = fadeOut(tween(exitMs, easing = SyMotion.ease)),
        ) {
            Surface(
                modifier = Modifier.width(width).heightIn(max = maxHeight)
                    .graphicsLayer {
                        transformOrigin = origin.value
                        scaleX = .96f + progress * .04f
                        scaleY = scaleX
                        translationY = (1f - progress) * travel *
                            if (transformOrigin.pivotFractionY == 1f) 1f else -1f
                        shadowElevation = 6.dp.toPx()
                        this.shape = shape
                    }
                    .testTag("conversation-action-popup"),
                shape = shape,
                color = palette.materialL5,
                border = BorderStroke(1.dp, palette.border),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(4.dp)) {
                    if (gridCount > 0) {
                        Column(Modifier.testTag("conversation-action-shortcuts")) {
                            items.take(gridCount).chunked(2).forEach { row ->
                                Row(Modifier.fillMaxWidth()) {
                                    row.forEach { item ->
                                        ContextMenuAction(
                                            item, open, onDismiss, Modifier.weight(1f), iconAbove = true,
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    items.drop(gridCount).forEachIndexed { index, item ->
                        val position = gridCount + index
                        if (position > 0 && (position == primaryActionCount ||
                                item.danger && !items[position - 1].danger)) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = SyMetric.gapMd, vertical = 2.dp),
                                color = palette.border,
                            )
                        }
                        ContextMenuAction(item, open, onDismiss, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextMenuAction(
    item: SyMenuItem,
    enabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier,
    iconAbove: Boolean = false,
) {
    val palette = LocalSyColors.current
    val ink = if (item.danger) palette.dangerInk else palette.text
    val action = modifier.heightIn(min = SyMetric.touch)
        .clip(RoundedCornerShape(SyMetric.radiusSm))
        .clickable(enabled = enabled) { onDismiss(); item.onClick() }
        .testTag("menu-item:" + item.text)
    val content: @Composable () -> Unit = {
        item.icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(18.dp),
                tint = if (item.danger) ink else palette.textSecondary)
        }
        Text(item.text, color = ink,
            style = if (iconAbove) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            textAlign = if (iconAbove) TextAlign.Center else TextAlign.Start)
    }
    if (iconAbove) {
        Column(action.padding(horizontal = SyMetric.gapXs, vertical = SyMetric.gapSm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs, Alignment.CenterVertically),
        ) { content() }
    } else {
        Row(action.padding(horizontal = SyMetric.gapMd, vertical = SyMetric.gapSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
        ) { content() }
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
