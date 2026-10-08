package com.labteto.dshmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
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
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.labteto.dshmobile.ui.theme.DshTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Modal dialog: dim overlay (platform scrim ~ overlayMask), r24 bgLayer2 plate
 * with a hairline border. [content] receives a [ColumnScope].
 */
@Composable
fun DsDialog(
    title: String?,
    onDismiss: () -> Unit,
    dismissOnScrimTap: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val scrimInteraction = remember { MutableInteractionSource() }
    val surfaceInteraction = remember { MutableInteractionSource() }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.985f,
        animationSpec = DsAnimations.pageFade,
        label = "dialogScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = DsAnimations.fade,
        label = "dialogAlpha",
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnScrimTap,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (dismissOnScrimTap) {
                        Modifier.clickable(
                            interactionSource = scrimInteraction,
                            indication = null,
                            onClick = onDismiss,
                        )
                    } else {
                        Modifier
                    },
                )
                .safeDrawingPadding()
                .imePadding()
                .padding(
                    horizontal = com.labteto.dshmobile.ui.theme.DsSpacing.comfortable,
                    vertical = com.labteto.dshmobile.ui.theme.DsSpacing.comfortable,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .then(
                        if (dismissOnScrimTap) {
                            Modifier.clickable(
                                interactionSource = surfaceInteraction,
                                indication = null,
                                onClick = {},
                            )
                        } else {
                            Modifier
                        },
                    ),
                shape = DsShapes.dialog,
                color = colors.wallpaperSurface(WallpaperSurfaceLevel.DIALOG),
                border = BorderStroke(1.dp, colors.borderL2),
                shadowElevation = 1.dp,
            ) {
                Column(
                    Modifier.padding(com.labteto.dshmobile.ui.theme.DsSpacing.comfortable),
                    verticalArrangement = Arrangement.spacedBy(
                        com.labteto.dshmobile.ui.theme.DsSpacing.small,
                    ),
                ) {
                    title?.let {
                        Text(it, style = DsType.base16Strong.withReadingWeight(), color = colors.labelPrimary)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(
                            com.labteto.dshmobile.ui.theme.DsSpacing.small,
                        ),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

/**
 * Full-screen modal surface with the same dismissal contract as [DsDialog].
 *
 * Feature code should use this instead of constructing raw [Dialog] instances so back/outside
 * dismissal policy remains centralized even for document and media viewers.
 */
@Composable
fun DsFullScreenDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
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

/**
 * Toast state pair: the current message ([State]) and a [show] lambda. The
 * message auto-clears 3s after the last [show] call.
 */
@Composable
fun rememberDsToast(): Pair<State<String?>, (String) -> Unit> {
    val flow = remember { MutableStateFlow<String?>(null) }
    var revision by remember { mutableLongStateOf(0L) }
    val state = flow.collectAsState()
    val message = state.value
    LaunchedEffect(message, revision) {
        if (message != null) {
            delay(3000)
            flow.value = null
        }
    }
    return state to {
        revision += 1L
        flow.value = it
    }
}

/** Top-center toast plate driven by [rememberDsToast]. */
@Composable
fun DsToastHost(state: Pair<State<String?>, (String) -> Unit>, modifier: Modifier = Modifier) {
    val message = state.first.value
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(DsAnimations.fade) +
                slideInVertically(DsAnimations.pageSlide) { -it / 3 },
            exit = fadeOut(DsAnimations.fade) +
                slideOutVertically(DsAnimations.pageSlide) { -it / 3 },
        ) {
            Surface(
                shape = DsShapes.toast,
                color = DsTheme.colors.toastBg,
                shadowElevation = 2.dp,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(
                    message.orEmpty(),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.onAccent,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** One entry in a [DsMenu]. */
data class MenuItem(
    val text: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Dropdown menu anchored to [anchor]; r12 bgLayer3 surface with h40 r10 cells,
 * hover fill, and danger rows in error/dangerHover.
 */
@Composable
fun DsMenu(anchor: @Composable () -> Unit, items: List<MenuItem>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .sizeIn(
                    minWidth = DsSpacing.touchTarget,
                    minHeight = DsSpacing.touchTarget,
                )
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            anchor()
        }
        DsPopupMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            items = items,
        )
    }
}

/**
 * Shared anchored popup primitive. All dropdown-style overlays route through this function so
 * tapping anywhere outside the menu always dismisses it.
 */
@Composable
fun DsPopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<MenuItem>,
    focusable: Boolean = true,
    containerColor: Color? = null,
) {
    val colors = DsTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = DsShapes.menu,
        containerColor = containerColor ?: colors.wallpaperSurface(WallpaperSurfaceLevel.MENU),
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, colors.borderL1),
        properties = PopupProperties(
            focusable = focusable,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = true,
        ),
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = {
                    Text(
                        item.text,
                        style = DsType.std14.withReadingWeight(),
                        color = if (item.danger) colors.error else colors.labelPrimary,
                    )
                },
                leadingIcon = item.icon?.let { icon ->
                    {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (item.danger) colors.error else colors.labelSecondary,
                        )
                    }
                },
                onClick = {
                    onDismiss()
                    item.onClick()
                },
                modifier = Modifier.heightIn(min = com.labteto.dshmobile.ui.theme.DsSpacing.touchTarget).clip(DsShapes.row),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Compact contextual action menu used for message long-press actions.
 *
 * It deliberately shares the same popup/dismiss contract as [DsPopupMenu]: tapping any area
 * outside the plate closes it. Actions are laid out horizontally to keep frequent message
 * operations close to the selected bubble without turning them into a permanent toolbar.
 */
@Composable
fun DsContextActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<MenuItem>,
) {
    if (!expanded || items.isEmpty()) return
    val colors = DsTheme.colors
    val positionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val endAlignedX = when (layoutDirection) {
                    LayoutDirection.Ltr -> anchorBounds.right - popupContentSize.width
                    LayoutDirection.Rtl -> anchorBounds.left
                }
                val belowY = anchorBounds.bottom
                val aboveY = anchorBounds.top - popupContentSize.height
                val resolvedY = when {
                    belowY + popupContentSize.height <= windowSize.height -> belowY
                    aboveY >= 0 -> aboveY
                    else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                }
                return IntOffset(
                    x = endAlignedX.coerceIn(0, maxX),
                    y = resolvedY,
                )
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = true,
        ),
    ) {
        Surface(
            shape = DsShapes.menu,
            color = colors.wallpaperSurface(WallpaperSurfaceLevel.MENU),
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, colors.borderL1),
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    Column(
                        modifier = Modifier
                            .widthIn(min = 64.dp)
                            .heightIn(min = 64.dp)
                            .clip(DsShapes.row)
                            .clickable {
                                onDismiss()
                                item.onClick()
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (item.danger) colors.error else colors.labelSecondary,
                            )
                        }
                        Text(
                            text = item.text,
                            style = DsType.small13.withReadingWeight(),
                            color = if (item.danger) colors.error else colors.labelPrimary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shared free-floating popup primitive. Focusable popups receive outside-pointer dismissal from
 * Compose, which keeps drag/select overlays consistent with dropdown menus and future popups.
 */
@Composable
fun DsFloatingPopup(
    onDismiss: () -> Unit,
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset.Zero,
    content: @Composable () -> Unit,
) {
    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = true,
        ),
        content = content,
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DsMenuPreview() {
    DshTheme {
        DsMenu(
            anchor = { DsButton("Menu", onClick = {}) },
            items = listOf(
                MenuItem("Open", icon = FeatherIcons.Edit3, onClick = {}),
                MenuItem("Delete", danger = true, onClick = {}),
            ),
        )
    }
}
