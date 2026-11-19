package com.shenyan.designsystem.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.LocalSyMotionTempo
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.foundation.SyMotion
import com.shenyan.designsystem.foundation.SyOption
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 神言全场景交互补齐：继续使用 ShenyanFoundation 的主题、尺寸与动效，
 * 不再创建第二套 UI tokens；业务状态、撤销和媒体播放由调用方持有。
 *
 * 覆盖：Action Sheet、Snackbar、通知横幅、加载骨架、滑动操作、引导/页码、
 * 日期范围、多选、数字步进、媒体播放与聊天输入。基础组件、对话框、
 * 长按菜单和响应式面板由原有 ShenyanBasics/SyOverlays/SyResponsiveSheet 提供。
 */
data class SyActionEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyActionSheet(
    title: String,
    entries: List<SyActionEntry>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    confirmDestructive: Boolean = true,
) {
    val palette = LocalSyColors.current
    var destructiveChoice by remember { mutableStateOf<SyActionEntry?>(null) }
    SyModalSheet(
        onDismiss = onDismiss, title = title, subtitle = subtitle, scrollable = true,
        modifier = modifier,
        footer = {
            SyButton("取消", onDismiss, modifier = Modifier.fillMaxWidth(),
                style = SyButtonStyle.SECONDARY)
        },
    ) {
        entries.forEach { entry ->
            SyMaterialSurface(
                onClick = {
                    if (entry.destructive && confirmDestructive) destructiveChoice = entry
                    else { onDismiss(); onChoose(entry.id) }
                },
                enabled = entry.enabled,
                shape = RoundedCornerShape(SyMetric.radiusControl),
                tier = SyMaterialTier.L3_CARD,
                modifier = Modifier.fillMaxWidth().heightIn(min = SyMetric.touch)
                    .testTag("sy-action:" + entry.id),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    entry.icon?.let {
                        Icon(it, null, Modifier.size(22.dp),
                            tint = if (entry.destructive) palette.dangerInk else palette.textSecondary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(entry.title,
                            color = if (entry.destructive) palette.dangerInk else palette.text,
                            style = MaterialTheme.typography.bodyLarge)
                        entry.subtitle?.let {
                            Text(it, color = palette.textSecondary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    destructiveChoice?.let { entry ->
        SyConfirmDialog("确认" + entry.title, entry.subtitle ?: "执行后可能无法恢复。",
            dangerous = true,
            onConfirm = {
                destructiveChoice = null
                onDismiss()
                onChoose(entry.id)
            },
            onDismiss = { destructiveChoice = null })
    }
}

@Composable
fun SyUndoSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val p = LocalSyColors.current
    SnackbarHost(hostState = hostState, modifier = modifier, snackbar = { data ->
        SyMaterialSurface(
            tier = SyMaterialTier.L4_OVERLAY,
            shape = RoundedCornerShape(SyMetric.radiusControl),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(data.visuals.message, color = p.text, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                data.visuals.actionLabel?.let { action ->
                    TextButton(onClick = data::performAction) {
                        Text(action, color = p.linkInk)
                    }
                }
                if (data.visuals.withDismissAction) {
                    SyIconButton(icon = Icons.Default.Close, contentDescription = "关闭提示",
                        onClick = data::dismiss, bare = true)
                }
            }
        }
    })
}

enum class SyBannerKind { INFO, SUCCESS, WARNING, ERROR }

@Composable
fun SyInlineBanner(
    message: String,
    kind: SyBannerKind,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val p = LocalSyColors.current
    val accent = when (kind) {
        SyBannerKind.INFO -> p.primary
        SyBannerKind.SUCCESS -> p.successInk
        SyBannerKind.WARNING -> p.warningInk
        SyBannerKind.ERROR -> p.dangerInk
    }
    SyMaterialSurface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        tier = SyMaterialTier.L3_CARD,
        shape = RoundedCornerShape(SyMetric.radiusControl),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .36f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
            Text(message, modifier = Modifier.weight(1f), color = p.text,
                style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = p.linkInk) }
            }
            onClose?.let {
                SyIconButton(Icons.Default.Close, "关闭提示", it, bare = true)
            }
        }
    }
}

@Composable
fun SySkeletonBlock(
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = SyMetric.radiusSm,
) {
    val p = LocalSyColors.current
    val reduced = LocalSyReduceMotion.current
    val pulse = if (reduced) 1f else {
        val motion = rememberInfiniteTransition(label = "sySkeleton")
        val animated by motion.animateFloat(
            initialValue = .55f, targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                tween(SyMotion.skeletonPulseMs, easing = SyMotion.ease),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
            label = "sySkeletonAlpha")
        animated
    }
    Box(modifier.clip(RoundedCornerShape(radius))
        .background(p.textTertiary.copy(alpha = if (reduced) .10f else .08f + .07f * pulse)))
}

@Composable
fun SySkeletonCard(modifier: Modifier = Modifier, includeImage: Boolean = true) {
    Column(modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (includeImage) SySkeletonBlock(Modifier.fillMaxWidth().height(94.dp),
            SyMetric.radiusCard)
        SySkeletonBlock(Modifier.fillMaxWidth(.74f).height(15.dp))
        SySkeletonBlock(Modifier.fillMaxWidth(.92f).height(11.dp))
        SySkeletonBlock(Modifier.fillMaxWidth(.48f).height(11.dp))
    }
}

@Composable
fun SyPageDots(
    count: Int,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: ((Int) -> Unit)? = null,
) {
    if (count <= 0) return
    val p = LocalSyColors.current
    val reduced = LocalSyReduceMotion.current
    Row(modifier.semantics {
        stateDescription = "第${selectedIndex.coerceIn(0, count - 1) + 1}页，共${count}页"
    }, horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val selected = index == selectedIndex
            val fill by animateColorAsState(
                if (selected) p.primary else p.textTertiary.copy(alpha = .35f),
                tween(SyMotion.duration(SyMotion.selectMs, reduced, LocalSyMotionTempo.current)),
                label = "syPageDot")
            val dot = Modifier.size(if (selected) 10.dp else 7.dp)
                .clip(CircleShape).background(fill)
            if (onSelect == null) Box(dot)
            else Surface(
                onClick = { onSelect(index) },
                modifier = Modifier.size(SyMetric.touch),
                color = Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) { Box(dot) }
            }
        }
    }
}

data class SyOnboardingPage(
    val title: String,
    val description: String,
    val illustration: (@Composable () -> Unit)? = null,
)

/**
 * The real first-launch flow and the design laboratory use the same gesture-driven pager.
 * Optional slots preserve feature-owned choices, navigation and permission transitions.
 */
@Composable
fun SyOnboardingCarousel(
    pages: List<SyOnboardingPage>,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    onSkip: (() -> Unit)? = onComplete,
    selectedPage: Int? = null,
    onPageChanged: ((Int) -> Unit)? = null,
    extraPageContent: (@Composable (Int) -> Unit)? = null,
    actions: (@Composable (Int, () -> Unit, () -> Unit) -> Unit)? = null,
) {
    if (pages.isEmpty()) return
    val p = LocalSyColors.current
    val reduced = LocalSyReduceMotion.current
    val state = rememberPagerState(
        initialPage = (selectedPage ?: 0).coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    val scope = rememberCoroutineScope()
    LaunchedEffect(selectedPage) {
        if (selectedPage != null && selectedPage in pages.indices &&
            state.currentPage != selectedPage
        ) state.scrollToPage(selectedPage)
    }
    LaunchedEffect(state.currentPage) { onPageChanged?.invoke(state.currentPage) }
    val goNext: () -> Unit = {
        if (state.currentPage == pages.lastIndex) onComplete()
        else scope.launch {
            if (reduced) state.scrollToPage(state.currentPage + 1)
            else state.animateScrollToPage(state.currentPage + 1)
        }
    }
    val goBack: () -> Unit = {
        if (state.currentPage > 0) scope.launch {
            if (reduced) state.scrollToPage(state.currentPage - 1)
            else state.animateScrollToPage(state.currentPage - 1)
        }
    }
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = state,
            modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 420.dp)
                .testTag("sy-onboarding-pages"),
        ) { index ->
            val page = pages[index]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = SyMetric.gapLg, vertical = SyMetric.gapMd),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                page.illustration?.invoke()
                Text(page.title, color = p.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall)
                Text(page.description, color = p.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium)
                extraPageContent?.invoke(index)
            }
        }
        SyPageDots(pages.size, state.currentPage,
            onSelect = { page ->
                scope.launch {
                    if (reduced) state.scrollToPage(page)
                    else state.animateScrollToPage(page)
                }
            },
        )
        if (actions != null) {
            actions(state.currentPage, goNext, goBack)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = SyMetric.gapLg),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                onSkip?.let {
                    SyButton("跳过", it, modifier = Modifier.weight(1f),
                        style = SyButtonStyle.SECONDARY)
                }
                SyButton(
                    if (state.currentPage == pages.lastIndex) "开始使用" else "下一步",
                    onClick = goNext,
                    modifier = Modifier.weight(1f),
                    icon = if (state.currentPage == pages.lastIndex) Icons.Default.Check
                        else Icons.AutoMirrored.Filled.ArrowForward,
                )
            }
        }
    }
}

/**
 * A true guided spotlight. Anchor geometry is measured in the window, then the
 * dimmed overlay is punched through at precisely that rectangle. Dismissal is
 * explicit; the underlying feature action is never invoked by dismissing.
 */
@Composable
fun SyCoachMark(
    title: String,
    description: String,
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchor: @Composable () -> Unit,
) {
    var target by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.onGloballyPositioned { coords ->
        val point = coords.positionInWindow()
        target = Rect(point.x, point.y,
            point.x + coords.size.width, point.y + coords.size.height)
    }) {
        anchor()
    }
    if (expanded && target.width > 0f) {
        val palette = LocalSyColors.current
        val density = LocalDensity.current
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val screenWidth = with(density) { maxWidth.toPx() }
                val screenHeight = with(density) { maxHeight.toPx() }
                val margin = with(density) { 16.dp.toPx() }
                val gap = with(density) { 12.dp.toPx() }
                val halo = with(density) { 6.dp.toPx() }
                val width = minOf(screenWidth - margin * 2,
                    with(density) { 292.dp.toPx() })
                val left = (target.left - halo).coerceIn(
                    margin, (screenWidth - width - margin).coerceAtLeast(margin))
                val expectedHeight = with(density) { 178.dp.toPx() }
                val top = if (target.bottom + gap + expectedHeight < screenHeight - margin)
                    target.bottom + gap
                else (target.top - gap - expectedHeight).coerceAtLeast(margin)
                val haloRect = Rect(
                    (target.left - halo).coerceAtLeast(0f),
                    (target.top - halo).coerceAtLeast(0f),
                    (target.right + halo).coerceAtMost(screenWidth),
                    (target.bottom + halo).coerceAtMost(screenHeight),
                )
                Canvas(
                    Modifier.fillMaxSize().clickable(onClick = onDismiss)
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }.testTag("sy-coach-spotlight"),
                ) {
                    drawRect(Color.Black.copy(alpha = .56f))
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(haloRect.left, haloRect.top),
                        size = Size(haloRect.width, haloRect.height),
                        cornerRadius = CornerRadius(14.dp.toPx()),
                        blendMode = BlendMode.Clear,
                    )
                    drawRoundRect(
                        color = palette.primary,
                        topLeft = Offset(haloRect.left, haloRect.top),
                        size = Size(haloRect.width, haloRect.height),
                        cornerRadius = CornerRadius(14.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                SyMaterialSurface(
                    modifier = Modifier.offset {
                        IntOffset(left.roundToInt(), top.roundToInt())
                    }.width(with(density) { width.toDp() })
                        .testTag("sy-coach-card"),
                    tier = SyMaterialTier.L4_OVERLAY,
                    shape = RoundedCornerShape(SyMetric.radiusCard),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(title, color = palette.text,
                            style = MaterialTheme.typography.titleMedium)
                        Text(description, color = palette.textSecondary,
                            style = MaterialTheme.typography.bodyMedium)
                        SyButton("知道了", onDismiss,
                            modifier = Modifier.align(Alignment.End),
                            style = SyButtonStyle.SECONDARY)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyDatePickerField(
    label: String,
    displayValue: String,
    onDateSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selectedDateMillis: Long? = null,
    pickerContent: @Composable ((onSelect: (Long) -> Unit, onDismiss: () -> Unit) -> Unit)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val p = LocalSyColors.current
    Surface(onClick = { open = true }, enabled = enabled, modifier = modifier,
        color = p.materialL2,
        shape = RoundedCornerShape(SyMetric.radiusControl),
        border = androidx.compose.foundation.BorderStroke(1.dp, p.border)) {
        Row(Modifier.fillMaxWidth().heightIn(min = SyMetric.touch)
            .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = p.textSecondary,
                    style = MaterialTheme.typography.labelSmall)
                Text(displayValue, color = p.text,
                    style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Default.MoreHoriz, contentDescription = "选择日期",
                tint = p.textSecondary)
        }
    }
    if (open) {
        val dismiss = { open = false }
        val select: (Long) -> Unit = { day ->
            dismiss()
            onDateSelected(day)
        }
        if (pickerContent != null) {
            pickerContent(select, dismiss)
        } else {
            val state = rememberDatePickerState(initialSelectedDateMillis = selectedDateMillis)
            DatePickerDialog(onDismissRequest = dismiss, confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null, onClick = {
                    state.selectedDateMillis?.let(select)
                }) { Text("确定") }
            }, dismissButton = {
                TextButton(onClick = dismiss) { Text("取消") }
            }) { SyDateCalendar(state) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyDateCalendar(state: DatePickerState) {
    DatePicker(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        title = null,
        headline = null,
        showModeToggle = false,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyTimeInput(state: TimePickerState) {
    val colors = LocalSyColors.current
    val scheme = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = scheme.copy(
        primary = colors.linkInk,
        onPrimary = colors.materialL5,
        primaryContainer = colors.selected,
        onPrimaryContainer = colors.text,
        surfaceVariant = colors.materialL3,
        onSurfaceVariant = colors.textSecondary,
        outline = colors.textTertiary,
    )) {
        TimeInput(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyDateRangePickerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Long, Long) -> Unit,
    initialStartMillis: Long? = null,
    initialEndMillis: Long? = null,
    selectableDates: SelectableDates = DatePickerDefaults.AllDates,
    yearRange: IntRange = DatePickerDefaults.YearRange,
) {
    if (!visible) return
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStartMillis,
        initialSelectedEndDateMillis = initialEndMillis,
        yearRange = yearRange,
        selectableDates = selectableDates,
    )
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
        TextButton(
            enabled = state.selectedStartDateMillis != null &&
                state.selectedEndDateMillis != null,
            onClick = {
                val start = state.selectedStartDateMillis
                val end = state.selectedEndDateMillis
                if (start != null && end != null) onConfirm(start, end)
            }) { Text("应用") }
    }, dismissButton = {
        TextButton(onClick = onDismiss) { Text("取消") }
    }) {
        DateRangePicker(
            state = state,
            modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
        )
    }
}

@Composable
fun SyNumericStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minimum: Int = Int.MIN_VALUE,
    maximum: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
    valueContent: (@Composable RowScope.() -> Unit)? = null,
) {
    require(minimum <= maximum) { "最小值不得大于最大值" }
    val p = LocalSyColors.current
    Row(modifier.heightIn(min = SyMetric.touch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SyIconButton(Icons.Default.Remove, "减少",
            onClick = { if (value > minimum) onValueChange(value - 1) },
            enabled = enabled && value > minimum)
        if (valueContent == null) {
            Text(value.toString(), modifier = Modifier.widthIn(min = 46.dp),
                color = p.text, style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        } else {
            valueContent()
        }
        SyIconButton(Icons.Default.Add, "增加",
            onClick = { if (value < maximum) onValueChange(value + 1) },
            enabled = enabled && value < maximum)
    }
}

@Composable
fun SyMultiSelectSheet(
    title: String,
    options: List<SyOption>,
    selectedIds: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    minSelected: Int = 0,
    maxSelected: Int = Int.MAX_VALUE,
    confirmLabel: String = "应用",
    header: (@Composable (Set<String>) -> Unit)? = null,
    summary: (@Composable (Set<String>) -> Unit)? = null,
    itemContent: (@Composable (SyOption, Boolean) -> Unit)? = null,
) {
    val palette = LocalSyColors.current
    // Draft is retained even while search filters options; dismissal never writes to feature data.
    var draft by remember(selectedIds) { mutableStateOf(selectedIds) }
    SyModalSheet(
        onDismiss = onDismiss, title = title,
        footer = {
            summary?.invoke(draft)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SyButton("取消", onDismiss, modifier = Modifier.weight(1f),
                    style = SyButtonStyle.SECONDARY)
                SyButton(if (confirmLabel == "应用") "应用（${draft.size}）" else confirmLabel,
                    { onConfirm(draft) },
                    enabled = enabled && draft.size in minSelected..maxSelected,
                    modifier = Modifier.weight(1f))
            }
        },
    ) {
        header?.invoke(draft)
        if (options.isEmpty()) {
            Text("暂无可选项", color = palette.textSecondary,
                style = MaterialTheme.typography.bodySmall)
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    .testTag("sy-multi-select-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(options.size, key = { index -> options[index].id }) { index ->
                    val option = options[index]
                    val checked = option.id in draft
                    val canToggle = enabled && option.enabled &&
                        (checked || draft.size < maxSelected)
                    SyMaterialSurface(
                        onClick = {
                            draft = if (checked) draft - option.id else draft + option.id
                        },
                        enabled = canToggle,
                        modifier = Modifier.fillMaxWidth().heightIn(min = SyMetric.touch)
                            .testTag("sy-multi-option:" + option.id),
                        shape = RoundedCornerShape(SyMetric.radiusControl),
                        tier = SyMaterialTier.L2_BAR,
                        tint = if (checked) palette.selected else Color.Unspecified,
                    ) {
                        Row(Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (itemContent != null) {
                                Box(Modifier.weight(1f)) { itemContent(option, checked) }
                            } else {
                                Text(option.label, color = palette.text,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                            SyCheckboxControl(checked = checked,
                                onCheckedChange = null, enabled = canToggle)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Swipe only REVEALS an action. Never execute a destructive action merely
 * because the gesture crossed a threshold; confirm execution in onAction.
 */
@Composable
fun SySwipeActionRow(
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    content: @Composable () -> Unit,
) {
    val p = LocalSyColors.current
    val haptic = LocalHapticFeedback.current
    var confirmAction by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val travel = with(density) { 104.dp.toPx() }
    var open by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val settled by animateFloatAsState(
        if (open) -travel else 0f,
        tween(SyMotion.duration(SyMotion.sheetMs, LocalSyReduceMotion.current,
            LocalSyMotionTempo.current), easing = SyMotion.ease),
        label = "sySwipeSettle")
    val actual = if (dragging) drag else settled
    LaunchedEffect(open) {
        if (open) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(SyMetric.radiusControl))
        .semantics {
            stateDescription = if (open) "操作已展开" else "左滑展开操作"
            customActions = listOf(CustomAccessibilityAction(
                if (open) "收起" + actionLabel else "展开" + actionLabel,
            ) {
                open = !open
                true
            })
        }) {
        if (open || dragging) {
            Surface(onClick = {
                    if (destructive) confirmAction = true
                    else { onAction(); open = false }
                },
                modifier = Modifier.align(Alignment.CenterEnd).width(104.dp)
                    .heightIn(min = SyMetric.touch),
                color = if (destructive) p.danger.copy(alpha = .14f) else p.selected) {
                Box(contentAlignment = Alignment.Center) {
                    Text(actionLabel, style = MaterialTheme.typography.labelMedium,
                        color = if (destructive) p.dangerInk else p.text)
                }
            }
        }
        SyMaterialSurface(
            modifier = Modifier.fillMaxWidth()
            .offset { IntOffset(actual.roundToInt(), 0) }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    drag = (drag + delta).coerceIn(-travel, 0f)
                },
                onDragStarted = { drag = settled; dragging = true },
                onDragStopped = { velocity ->
                    open = drag < -travel * .5f || velocity < -650f
                    dragging = false
                },
            ),
            tier = SyMaterialTier.L3_CARD,
            shape = RoundedCornerShape(SyMetric.radiusControl),
        ) {
            content()
        }
    }
    if (confirmAction) {
        SyConfirmDialog("确认" + actionLabel, "执行后可能无法恢复。", dangerous = true,
            onConfirm = { confirmAction = false; onAction(); open = false },
            onDismiss = { confirmAction = false })
    }
}

@Composable
fun SyMediaTransport(
    title: String,
    playing: Boolean,
    progress: Float,
    elapsedLabel: String,
    durationLabel: String,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    enabled: Boolean = true,
    seekable: Boolean = true,
    onCollapse: (() -> Unit)? = null,
) {
    val p = LocalSyColors.current
    SyMaterialSurface(
        modifier = modifier,
        tier = SyMaterialTier.L3_CARD,
        shape = RoundedCornerShape(SyMetric.radiusCard),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = p.text, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f), maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                onCollapse?.let {
                    SyIconButton(Icons.Default.KeyboardArrowDown, "收起播放器", it, bare = true)
                }
            }
            Slider(value = progress.coerceIn(0f, 1f), onValueChange = onSeek,
                enabled = seekable,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = p.primary,
                    activeTrackColor = p.primary,
                    inactiveTrackColor = p.textTertiary.copy(alpha = .2f)))
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(elapsedLabel, color = p.textSecondary,
                    style = MaterialTheme.typography.labelSmall)
                Text(durationLabel, color = p.textSecondary,
                    style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                onPrevious?.let {
                    SyIconButton(Icons.Default.SkipPrevious, "上一项", it, bare = true)
                }
                SyIconButton(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "暂停" else "播放", onPlayPause,
                    enabled = enabled, selected = true, iconSize = 24.dp)
                onNext?.let {
                    SyIconButton(Icons.Default.SkipNext, "下一项", it, bare = true)
                }
            }
        }
    }
}


/** 由调用方提供真实完成比例；null 表示进行中且进度未知。 */
@Composable
fun SyProgressRing(
    progress: Float?,
    label: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSyColors.current
    val amount = progress?.coerceIn(0f, 1f)
    Row(
        modifier.heightIn(min = SyMetric.touch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SyActivityIndicator(modifier = Modifier.size(30.dp), progress = amount)
        Text(
            if (amount == null) label else "$label · ${(amount * 100f).roundToInt()}%",
            color = palette.text,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** 紧凑播放条仅负责交互展示；播放和关闭状态始终由上层持有。 */
@Composable
fun SyMiniPlayer(
    title: String,
    subtitle: String,
    playing: Boolean,
    onPlayPause: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSyColors.current
    SyMaterialSurface(
        modifier = modifier.fillMaxWidth(),
        tier = SyMaterialTier.L3_CARD,
        shape = RoundedCornerShape(SyMetric.radiusCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
    ) {
        Row(
            Modifier.padding(8.dp).heightIn(min = SyMetric.touch),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                onClick = onExpand,
                modifier = Modifier.weight(1f).heightIn(min = SyMetric.touch),
                color = Color.Transparent,
            ) {
                Column(
                    Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(title, color = palette.text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, color = palette.textSecondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            SyIconButton(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (playing) "暂停播放" else "继续播放",
                onPlayPause,
                selected = playing,
                bare = true,
            )
            SyIconButton(Icons.Default.Close, "关闭迷你播放器", onClose, bare = true)
        }
    }
}

/**
 * Full-featured Chat and Work input reuse this shell while retaining feature-owned
 * attachments, voice recording, model selection, queue, keyboard and send actions.
 */
@Composable
fun SyChatComposer(
    modifier: Modifier = Modifier,
    surfaceColor: Color = LocalSyColors.current.materialL3,
    shadowElevation: Dp = 0.dp,
    animateSize: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalSyReduceMotion.current
    SyComposerSurface(
        modifier = modifier.then(if (animateSize)
            Modifier.animateContentSize(tween(
                SyMotion.duration(SyMotion.toolExpandMs, reduced, LocalSyMotionTempo.current),
                easing = SyMotion.ease,
            )) else Modifier),
        tint = surfaceColor.copy(alpha = surfaceColor.alpha.coerceAtMost(.06f)),
        shadowElevation = shadowElevation,
        content = content,
    )
}

@Composable
fun SyChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "输入消息…",
    enabled: Boolean = true,
    sending: Boolean = false,
    onAttachment: (() -> Unit)? = null,
    onVoice: (() -> Unit)? = null,
) {
    SyChatComposer(
        modifier = modifier.fillMaxWidth().imePadding().testTag("sy-atlas-composer"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            onAttachment?.let {
                SyIconButton(Icons.Default.Attachment, "添加附件", it,
                    enabled = enabled, bare = true)
            }
            SyTextField(
                value = value, onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled, placeholder = { Text(placeholder) },
                minLines = 1, maxLines = 5, flat = true,
            )
            onVoice?.let {
                SyIconButton(Icons.Default.KeyboardVoice, "语音输入", it,
                    enabled = enabled && !sending, bare = true)
            }
            SyIconButton(Icons.Default.Send, "发送消息", onSend,
                enabled = enabled && !sending && value.isNotBlank(),
                selected = value.isNotBlank() && enabled,
                iconSize = 21.dp)
        }
    }
}
