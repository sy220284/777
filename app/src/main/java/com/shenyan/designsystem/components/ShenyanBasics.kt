package com.shenyan.designsystem.components

import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import com.shenyan.designsystem.foundation.*

/** 基础 18 组件：所有业务动作由调用者注入，完全不依赖旧版 Ds 控件。 */
enum class SyButtonStyle { PRIMARY, SECONDARY, INFO, GHOST, OUTLINE, DANGER }

/**
 * Single V5 button implementation. Supports legacy business handlers during migration.
 * Never uses a separate fallback button renderer.
 */
@Composable
fun SyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SyButtonStyle = SyButtonStyle.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = SyMetric.touch,
    onHoldRepeat: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    val c = LocalSyColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val repeatAction by rememberUpdatedState(onHoldRepeat)
    LaunchedEffect(pressed, enabled, loading) {
        if (!pressed || !enabled || loading || repeatAction == null) return@LaunchedEffect
        delay(SyMotion.holdStartMs)
        while (true) {
            val action = repeatAction ?: break
            action()
            delay(SyMotion.holdRepeatMs)
        }
    }
    val reduceMotion = LocalSyReduceMotion.current
    val tempo = LocalSyMotionTempo.current
    val scale by animateFloatAsState(
        if (pressed && enabled && !reduceMotion) SyMotion.pressedScale else 1f,
        animationSpec = tween(SyMotion.duration(SyMotion.pressMs, reduceMotion, tempo)),
        label = "syButtonPress",
    )
    val targetFill = if (!enabled) syMaterialColor(SyMaterialTier.L2_BAR) else when (style) {
        SyButtonStyle.PRIMARY -> if (pressed) c.primaryPressed else c.primary
        SyButtonStyle.DANGER -> if (pressed) c.danger.copy(alpha = .88f) else c.danger
        SyButtonStyle.INFO -> if (pressed) c.primary.copy(alpha = .18f) else c.selected
        SyButtonStyle.SECONDARY -> syMaterialColor(SyMaterialTier.L2_BAR)
        SyButtonStyle.GHOST -> Color.Transparent
        SyButtonStyle.OUTLINE -> syMaterialColor(SyMaterialTier.L1_SECTION)
    }
    val fill by animateColorAsState(
        targetValue = targetFill,
        animationSpec = tween(SyMotion.duration(SyMotion.interactionColorMs, reduceMotion, tempo),
            easing = SyMotion.ease),
        label = "syButtonFeedback",
    )
    val foreground = if (!enabled) c.textTertiary else when (style) {
        SyButtonStyle.PRIMARY -> com.shenyan.designsystem.foundation.syReadableInk(c.onPrimary, fill)
        SyButtonStyle.DANGER -> com.shenyan.designsystem.foundation.syReadableInk(c.text, fill)
        else -> c.text
    }
    val buttonBody: @Composable () -> Unit = {
        Row(
            Modifier.padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                SyActivityIndicator(Modifier.size(16.dp), color = foreground)
                Spacer(Modifier.width(8.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            if (text.isNotEmpty()) {
                Text(text, textAlign = TextAlign.Center,
                    style = if (compact) MaterialTheme.typography.labelMedium else LocalTextStyle.current)
            }
        }
    }
    val buttonShape = RoundedCornerShape(SyMetric.radiusControl)
    val buttonBorder = when {
        focused && enabled && !loading -> BorderStroke(1.dp, c.linkInk)
        style == SyButtonStyle.SECONDARY || style == SyButtonStyle.OUTLINE || style == SyButtonStyle.INFO ->
            BorderStroke(1.dp, c.border)
        else -> null
    }
    val buttonModifier = modifier.sizeIn(
        minWidth = SyMetric.touch, minHeight = height.coerceAtLeast(SyMetric.touch),
    ).semantics { if (loading) stateDescription = "正在处理" }
    if (style == SyButtonStyle.SECONDARY || style == SyButtonStyle.INFO || style == SyButtonStyle.OUTLINE) {
        SyMaterialSurface(
            onClick = onClick, enabled = enabled && !loading,
            modifier = buttonModifier, interactionSource = interaction,
            tier = SyMaterialTier.L2_BAR,
            tint = if (style == SyButtonStyle.INFO) c.primary.copy(alpha = .16f) else Color.Unspecified,
            contentColor = foreground, shape = buttonShape,
            border = buttonBorder,
        ) { buttonBody() }
    } else {
        Surface(
            onClick = onClick,
            enabled = enabled && !loading,
            interactionSource = interaction,
            modifier = buttonModifier.scale(scale),
            color = fill,
            contentColor = foreground,
            shape = buttonShape,
            border = buttonBorder,
        ) { buttonBody() }
    }
}

@Composable
fun SyGlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
                tier: SyMaterialTier = SyMaterialTier.L3_CARD,
                content: @Composable ColumnScope.() -> Unit) {
    SyMaterialFrame(modifier = modifier, tier = tier, onClick = onClick, content = content)
}

@Composable
fun SyInput(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
            label: String = "", error: String? = null, enabled: Boolean = true, singleLine: Boolean = true) {
    SyTextField(value = value, onValueChange = onValueChange, modifier = modifier.fillMaxWidth().heightIn(min = SyMetric.touch),
        enabled = enabled, singleLine = singleLine, isError = error != null,
        label = if (label.isEmpty()) null else ({ Text(label) }),
        supportingText = if (error == null) null else ({ Text(error) }),
        shape = RoundedCornerShape(SyMetric.radiusControl))
}

@Composable
fun SyTabs(tabs: List<SyOption>, selectedId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalSyColors.current
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEach { tab ->
            val fill by animateColorAsState(
                if (tab.id == selectedId) c.selected else syMaterialColor(SyMaterialTier.L2_BAR),
                tween(SyMotion.duration(SyMotion.selectMs, LocalSyReduceMotion.current, LocalSyMotionTempo.current)), label = "tabSelection",
            )
            SyMaterialSurface(onClick = { onSelect(tab.id) }, enabled = tab.enabled,
                modifier = Modifier.heightIn(min = SyMetric.touch).semantics {
                    selected = tab.id == selectedId
                    role = Role.Tab
                },
                tier = SyMaterialTier.L3_CARD,
                shape = RoundedCornerShape(SyMetric.radiusControl),
                tint = if (tab.id == selectedId) fill.copy(alpha = .16f) else Color.Unspecified) {
                Box(Modifier.padding(horizontal = 15.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text(tab.label, color = c.text)
                }
            }
        }
    }
}

/**
 * Shared sheet structure for the entire Shenyan family. Only the outer container changes
 * between compact bottom sheets and wide-screen side panels.
 * The scroll body and pinned actions have one implementation.
 */
@Composable
fun SySheetScaffold(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onDismiss: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showDragHandle: Boolean = true,
    scrollable: Boolean = false,
    maxBodyHeight: Dp = 440.dp,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    bodyScrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    bodyViewport: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalSyColors.current
    Column(
        modifier.fillMaxWidth().navigationBarsPadding().imePadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showDragHandle) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Spacer(Modifier.width(44.dp).height(4.dp)
                    .background(palette.border, RoundedCornerShape(3.dp)))
            }
        }
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = palette.text)
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary)
                    }
                }
                if (trailing != null) trailing()
                else if (onDismiss != null) {
                    SyIconButton(Icons.Default.Close, "关闭面板", onDismiss, bare = true)
                }
            }
        }
        if (scrollable) {
            Column(bodyViewport.fillMaxWidth().heightIn(max = maxBodyHeight)
                .verticalScroll(bodyScrollState)
                .testTag("bottomSheetScrollBody"),
                verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        } else {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        if (footer != null) Column(Modifier.fillMaxWidth(), content = footer)
        Spacer(Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyModalSheet(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    scrollable: Boolean = false,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val height = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val sheetShape = RoundedCornerShape(topStart = SyMetric.radiusSheet, topEnd = SyMetric.radiusSheet)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The single L4 renderer owns the glass colour, rim and aligned backdrop.
        containerColor = Color.Transparent,
        contentColor = LocalSyColors.current.text,
        modifier = modifier,
        shape = sheetShape,
        dragHandle = null,
        tonalElevation = 0.dp,
    ) {
        SyMaterialSurface(
            modifier = Modifier.fillMaxWidth().testTag("sy-modal-sheet-surface"),
            tier = SyMaterialTier.L4_OVERLAY,
            shape = sheetShape,
            shadowElevation = 0.dp,
        ) {
            SySheetScaffold(title, subtitle = subtitle, onDismiss = onDismiss,
                scrollable = scrollable, maxBodyHeight = height * .62f,
                footer = footer, content = content)
        }
    }
}

@Composable
fun SyConfirmDialog(title: String, body: String, dangerous: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalSyColors.current
    val maxBodyHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .50f
    Dialog(
        onDismissRequest = { if (!dangerous) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !dangerous,
            dismissOnClickOutside = !dangerous,
        ),
    ) {
        SyMaterialSurface(
            modifier = Modifier.fillMaxWidth(),
            tier = SyMaterialTier.L4_OVERLAY,
            shape = RoundedCornerShape(SyMetric.radiusSheet),
        ) {
            Column(
                Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = palette.text)
                Text(body,
                    modifier = Modifier.heightIn(max = maxBodyHeight).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary,
                )
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SyButton("取消", onDismiss, style = SyButtonStyle.GHOST)
                    Spacer(Modifier.width(SyMetric.gapSm))
                    SyButton(if (dangerous) "确认执行" else "确认", onConfirm,
                        style = if (dangerous) SyButtonStyle.DANGER else SyButtonStyle.PRIMARY)
                }
            }
        }
    }
}

/** Search glyph is generated with the same 24dp, 1.75 outline as V6.3 actions. */
private val SyV6SearchIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SyV6.Search", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        for (data in listOf(
            "M10.7 4.4 A6.4 6.4 0 1 0 10.7 17.2 A6.4 6.4 0 1 0 10.7 4.4",
            "M15.5 15.5 L20.5 20.5",
        )) {
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}

@Composable
fun SySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    shortcut: String? = null,
    searchIcon: ImageVector = SyV6SearchIcon,
) {
    val colors = LocalSyColors.current
    SyTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = if (placeholder.isBlank()) null else {
            { Text(placeholder, color = colors.textTertiary) }
        },
        leadingIcon = {
            Icon(searchIcon, contentDescription = null,
                tint = colors.textTertiary, modifier = Modifier.size(18.dp))
        },
        trailingIcon = if (shortcut.isNullOrBlank()) null else {
            { Text(shortcut, style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary) }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(14.dp),

    )
}

@Composable
fun SyToast(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, color = LocalSyColors.current.text, shape = RoundedCornerShape(SyMetric.radiusControl)) {
        Text(text, Modifier.padding(14.dp), color = LocalSyColors.current.canvas)
    }
}

@Composable
fun SyProgressBar(
    progress: Float?,
    phase: SyPhase,
    modifier: Modifier = Modifier,
    tintOverride: Color? = null,
) {
    val c = LocalSyColors.current
    val color = tintOverride ?: when (phase) {
        SyPhase.FAILED -> c.clay
        SyPhase.DONE, SyPhase.RUNNING -> c.mint
        SyPhase.WAITING -> c.lilac
        SyPhase.BLOCKED -> c.primary
        else -> c.textTertiary
    }
    // Unknown percentage is indeterminate ONLY when work is genuinely active.
    if (progress == null && phase == SyPhase.RUNNING) {
        val track = syMaterialColor(SyMaterialTier.L2_BAR)
        if (LocalSyReduceMotion.current) {
            LinearProgressIndicator(
                progress = { .25f },
                modifier = modifier.fillMaxWidth().clearAndSetSemantics {
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
                color = color, trackColor = track,
            )
        } else {
            LinearProgressIndicator(modifier.fillMaxWidth(), color = color, trackColor = track)
        }
    } else {
        val amount = progress?.coerceIn(0f, 1f) ?: if (phase == SyPhase.DONE) 1f else 0f
        LinearProgressIndicator(progress = { amount }, modifier = modifier.fillMaxWidth(),
            color = color, trackColor = syMaterialColor(SyMaterialTier.L2_BAR))
    }
}

@Composable
fun SyAvatar(name: String, modifier: Modifier = Modifier) {
    Surface(modifier.size(44.dp), shape = CircleShape, color = LocalSyColors.current.selected) {
        Box(contentAlignment = Alignment.Center) { Text(name.take(1), color = LocalSyColors.current.text) }
    }
}

@Composable
fun SySwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = SyMetric.touch), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = LocalSyColors.current.text)
        SySwitchControl(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * Canonical V5 switch paint and interaction. Used by both labelled rows and existing
 * 777 setting cells to avoid two implementations of checked/disabled colour semantics.
 */
@Composable
fun SySwitchControl(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = LocalSyColors.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = p.onPrimary,
            checkedTrackColor = p.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = p.textTertiary,
            uncheckedTrackColor = syMaterialColor(SyMaterialTier.L2_BAR),
            uncheckedBorderColor = p.border,
            disabledCheckedThumbColor = p.onPrimary.copy(alpha = .58f),
            disabledCheckedTrackColor = p.primary.copy(alpha = .36f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = p.textTertiary.copy(alpha = .55f),
            disabledUncheckedTrackColor = syMaterialColor(SyMaterialTier.L2_BAR).copy(alpha = .6f),
            disabledUncheckedBorderColor = p.border.copy(alpha = .5f),
        ),
    )
}


@Composable
fun SyListItem(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SyMaterialSurface(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = SyMetric.touch),
        tier = SyMaterialTier.L3_CARD, shape = RoundedCornerShape(SyMetric.radiusControl)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = LocalSyColors.current.text)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = LocalSyColors.current.textSecondary)
        }
    }
}

@Composable
fun SyStatusTag(label: String, phase: SyPhase, modifier: Modifier = Modifier) {
    val c = LocalSyColors.current
    val tint = when (phase) {
        SyPhase.DONE -> c.successInk
        SyPhase.RUNNING -> c.linkInk
        SyPhase.FAILED -> c.dangerInk
        SyPhase.BLOCKED -> c.warningInk
        SyPhase.WAITING -> c.textSecondary
        SyPhase.CANCELLED, SyPhase.IDLE -> c.textTertiary
    }
    Surface(modifier, color = tint.copy(alpha = .18f), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(tint, CircleShape))
            Text(label, color = c.text, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.semantics { contentDescription = "${phase.asChineseLabel()} · $label" })
        }
    }
}

@Composable
fun SyFileRow(fileName: String, detail: String, onOpen: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    SyMaterialSurface(onClick = onOpen, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = SyMetric.touch),
        tier = SyMaterialTier.L3_CARD, shape = RoundedCornerShape(SyMetric.radiusControl)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("▤", Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(fileName, color = LocalSyColors.current.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = LocalSyColors.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SyNote(text: String, modifier: Modifier = Modifier) {
    SyMaterialSurface(modifier, tier = SyMaterialTier.L2_BAR, shape = RoundedCornerShape(SyMetric.radiusControl)) {
        Text(text, Modifier.padding(13.dp), color = LocalSyColors.current.textSecondary)
    }
}

@Composable
fun SyLoadingStates(phase: SyPhase, message: String, onRetry: (() -> Unit)? = null,
                    modifier: Modifier = Modifier, elapsedLabel: String? = null,
                    onCancel: (() -> Unit)? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // WAITING is not an active spinner; only a genuinely running operation animates.
        if (phase == SyPhase.RUNNING) {
            SyActivityIndicator(color = LocalSyColors.current.mint)
        }
        Text(message, color = LocalSyColors.current.text)
        if (phase == SyPhase.RUNNING && elapsedLabel != null) {
            Text("已用时 $elapsedLabel", color = LocalSyColors.current.textSecondary)
            if (onCancel != null) SyButton("停止等待", onCancel, style = SyButtonStyle.SECONDARY)
        }
        if (phase == SyPhase.FAILED && onRetry != null) SyButton("重试", onRetry)
    }
}

/** Approval teaser: opening the live approval flow is owned by the caller. */
@Composable
fun SyApproveCard(title: String, reason: String,
                  onReviewApproval: () -> Unit, onPostpone: () -> Unit,
                  modifier: Modifier = Modifier, enabled: Boolean = true,
                  scope: String = "仅当前操作", impact: String = "待核实") {
    SyGlassCard(modifier) {
        SyStatusTag("等待审批", SyPhase.BLOCKED)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(reason, color = LocalSyColors.current.textSecondary)
        SyNote("作用范围：$scope\n预期影响：$impact")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SyButton("查看并审批", onReviewApproval, modifier = Modifier.fillMaxWidth(), enabled = enabled)
            SyButton("稍后处理", onPostpone, modifier = Modifier.fillMaxWidth(), style = SyButtonStyle.SECONDARY, enabled = enabled)
        }
    }
}
