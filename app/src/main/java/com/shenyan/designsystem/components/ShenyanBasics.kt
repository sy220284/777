package com.shenyan.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import com.shenyan.designsystem.foundation.*

/** 基础 18 组件：所有业务动作由调用者注入，完全不依赖旧版 Ds 控件。 */
enum class SyButtonStyle { PRIMARY, SECONDARY, GHOST, OUTLINE, DANGER }

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
) {
    val c = LocalSyColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val repeatAction by rememberUpdatedState(onHoldRepeat)
    LaunchedEffect(pressed, enabled, loading) {
        if (!pressed || !enabled || loading || repeatAction == null) return@LaunchedEffect
        delay(420L)
        while (true) {
            val action = repeatAction ?: break
            action()
            delay(90L)
        }
    }
    val reduceMotion = LocalSyReduceMotion.current
    val scale by animateFloatAsState(
        if (pressed && enabled) .97f else 1f,
        animationSpec = tween(if (reduceMotion) 0 else SyMotion.pressMs),
        label = "syButtonPress",
    )
    val fill = if (!enabled) c.materialL2 else when (style) {
        SyButtonStyle.PRIMARY -> c.primary
        SyButtonStyle.DANGER -> c.danger
        SyButtonStyle.SECONDARY -> c.surfaceRaised
        SyButtonStyle.GHOST, SyButtonStyle.OUTLINE -> Color.Transparent
    }
    val foreground = if (!enabled) c.textTertiary else when (style) {
        SyButtonStyle.PRIMARY -> c.onPrimary
        SyButtonStyle.DANGER -> Color(0xFF241613)
        else -> c.text
    }
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        interactionSource = interaction,
        modifier = modifier.sizeIn(minWidth = SyMetric.touch, minHeight = height.coerceAtLeast(SyMetric.touch)).scale(scale),
        color = if (style == SyButtonStyle.PRIMARY && enabled) Color.Transparent else fill,
        contentColor = foreground,
        shape = RoundedCornerShape(SyMetric.radiusControl),
        border = if (style == SyButtonStyle.SECONDARY || style == SyButtonStyle.OUTLINE)
            BorderStroke(1.dp, c.border) else null,
    ) {
        Row(
            Modifier.background(
                Brush.horizontalGradient(if (style == SyButtonStyle.PRIMARY && enabled)
                    listOf(c.primary, c.primaryPressed) else listOf(fill, fill))
            ).padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp),
                    color = foreground, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            if (text.isNotEmpty()) {
                Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
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
    OutlinedTextField(value = value, onValueChange = onValueChange, modifier = modifier.fillMaxWidth().heightIn(min = SyMetric.touch),
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
            Surface(onClick = { onSelect(tab.id) }, enabled = tab.enabled,
                modifier = Modifier.heightIn(min = SyMetric.touch).semantics {
                    selected = tab.id == selectedId
                    role = Role.Tab
                },
                shape = RoundedCornerShape(13.dp),
                color = if (tab.id == selectedId) c.selected else c.surface,
                border = BorderStroke(1.dp, c.border)) {
                Box(Modifier.padding(horizontal = 15.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text(tab.label, color = c.text)
                }
            }
        }
    }
}

@Composable
fun SyTabBar(tabs: List<SyOption>, selectedId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier, containerColor = LocalSyColors.current.surfaceSolid) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = selectedId == tab.id,
                onClick = { onSelect(tab.id) },
                enabled = tab.enabled,
                icon = { Text(if (selectedId == tab.id) "●" else "○") },
                label = { Text(tab.label, maxLines = 1) },
                alwaysShowLabel = true
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyModalSheet(onDismiss: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalSyColors.current.surfaceSolid) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), content = {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            content()
            Spacer(Modifier.height(26.dp))
        })
    }
}

@Composable
fun SyConfirmDialog(title: String, body: String, dangerous: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!dangerous) onDismiss() }, title = { Text(title) }, text = { Text(body) },
        confirmButton = { SyButton(if (dangerous) "确认执行" else "确认", onConfirm, style = if (dangerous) SyButtonStyle.DANGER else SyButtonStyle.PRIMARY) },
        dismissButton = { SyButton("取消", onDismiss, style = SyButtonStyle.GHOST) },
        containerColor = LocalSyColors.current.surfaceSolid,
        shape = RoundedCornerShape(SyMetric.radiusSheet))
}

@Composable
fun SySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    shortcut: String? = null,
) {
    val colors = LocalSyColors.current
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().heightIn(min = SyMetric.touch)
            .onFocusChanged { focused = it.isFocused },
        placeholder = if (placeholder.isBlank()) null else {
            { Text(placeholder, color = colors.textTertiary) }
        },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null,
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
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceSolid,
            unfocusedContainerColor = colors.materialL3,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.border,
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            cursorColor = colors.primary,
        ),
    )
}

@Composable
fun SyToast(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = LocalSyColors.current.text, shape = RoundedCornerShape(13.dp)) {
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
        LinearProgressIndicator(modifier.fillMaxWidth(), color = color, trackColor = c.materialL2)
    } else {
        val amount = progress?.coerceIn(0f, 1f) ?: if (phase == SyPhase.DONE) 1f else 0f
        LinearProgressIndicator(progress = { amount }, modifier = modifier.fillMaxWidth(),
            color = color, trackColor = c.materialL2)
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
            uncheckedTrackColor = p.materialL2,
            uncheckedBorderColor = p.border,
            disabledCheckedThumbColor = p.onPrimary.copy(alpha = .58f),
            disabledCheckedTrackColor = p.primary.copy(alpha = .36f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = p.textTertiary.copy(alpha = .55f),
            disabledUncheckedTrackColor = p.materialL2.copy(alpha = .6f),
            disabledUncheckedBorderColor = p.border.copy(alpha = .5f),
        ),
    )
}


@Composable
fun SyListItem(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = SyMetric.touch), color = LocalSyColors.current.surface,
        shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.padding(13.dp)) { Text(title); Text(description, style = MaterialTheme.typography.bodySmall, color = LocalSyColors.current.textSecondary) }
    }
}

@Composable
fun SyStatusTag(label: String, phase: SyPhase, modifier: Modifier = Modifier) {
    val c = LocalSyColors.current
    val tint = when (phase) {
        SyPhase.DONE, SyPhase.RUNNING -> c.mint
        SyPhase.FAILED -> c.clay
        SyPhase.BLOCKED -> c.primary
        SyPhase.WAITING -> c.lilac
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
    Surface(onClick = onOpen, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = SyMetric.touch),
        color = LocalSyColors.current.surface, shape = RoundedCornerShape(13.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("▤", Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) { Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(detail, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun SyNote(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = LocalSyColors.current.surfaceRaised, shape = RoundedCornerShape(13.dp)) {
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
            CircularProgressIndicator(color = LocalSyColors.current.mint)
        }
        Text(message, color = LocalSyColors.current.text)
        if (phase == SyPhase.RUNNING && elapsedLabel != null) {
            Text("已用时 $elapsedLabel", color = LocalSyColors.current.textSecondary)
            if (onCancel != null) SyButton("停止等待", onCancel, style = SyButtonStyle.SECONDARY)
        }
        if (phase == SyPhase.FAILED && onRetry != null) SyButton("重试", onRetry)
    }
}

/** Approval teaser: it opens a blocking SyApprovalDetail dialog. Never execute a side effect here. */
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
