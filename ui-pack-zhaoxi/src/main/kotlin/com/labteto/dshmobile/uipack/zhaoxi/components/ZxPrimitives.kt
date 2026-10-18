package com.labteto.dshmobile.uipack.zhaoxi.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiDuration
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiMotion
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme

/**
 * Zx 基础组件（E8.14 实装）。规格：compose/朝夕-Compose实施规格.md §1.1。
 * 只引用 ZhaoxiTheme 令牌，禁颜色字面量；动画只经 ZhaoxiMotion.spec()。
 */

enum class ZxButtonVariant { Ink, Outline, Ghost, Danger }

@Composable
fun ZxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ZxButtonVariant = ZxButtonVariant.Ink,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = ZhaoxiTheme.colors
    val spec = ZhaoxiMotion.spec<androidx.compose.ui.graphics.Color>(ZhaoxiDuration.FAST)
    val bg by animateColorAsState(
        when {
            !enabled -> c.bg2
            variant == ZxButtonVariant.Ink -> c.ink
            variant == ZxButtonVariant.Danger -> c.err
            else -> c.card
        }, spec, label = "zxBtnBg",
    )
    val fg = when {
        !enabled -> c.ink3
        variant == ZxButtonVariant.Ink || variant == ZxButtonVariant.Danger -> c.onInk
        variant == ZxButtonVariant.Danger -> c.err
        else -> c.ink
    }
    Box(
        modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(
                if (variant == ZxButtonVariant.Outline)
                    Modifier.background(bg) // 描边以 line 色呈现（Outline 态）
                else Modifier,
            )
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // loading 宽度锁定：标签与指示同槽位，不换布局
        Text(if (loading) "…" else text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ZxIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = ZhaoxiTheme.colors
    Box(
        modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .semantics { this.contentDescription = contentDescription }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) { androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides c.ink,
    ) { content() } }
}

@Composable
fun ZxField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: Boolean = false,
    textStyle: TextStyle = TextStyle(fontSize = 14.sp),
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val c = ZhaoxiTheme.colors
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(ZhaoxiTheme.shapes.field))
            .background(c.bg2)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = textStyle.copy(color = c.ink),
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.widthIn(min = 120.dp).semantics {
                if (placeholder.isNotEmpty()) contentDescription = placeholder
            },
        ) { inner ->
            if (value.isEmpty()) Text(placeholder, color = c.ink3, fontSize = textStyle.fontSize)
            inner()
        }
    }
}

@Composable
fun ZxSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val c = ZhaoxiTheme.colors
    val spec = ZhaoxiMotion.spec<androidx.compose.ui.graphics.Color>(ZhaoxiDuration.FAST)
    val track by animateColorAsState(if (checked) c.ok else c.bg2, spec, label = "zxSwTrack")
    Box(
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription?.let { this.contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp)).background(track).padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(c.card))
        }
    }
}

/** 档位滑杆（推理/温度共用，5 档）：过档触感 TICK 由调用方经 ZhaoxiHaptic 触发。 */
@Composable
fun ZxDetentSlider(
    value: Int,
    steps: Int,
    labels: List<String>,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = ZhaoxiTheme.colors
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.take(steps).forEachIndexed { i, label ->
            val selected = i == value
            val spec = ZhaoxiMotion.spec<androidx.compose.ui.graphics.Color>(ZhaoxiDuration.FAST)
            val bg by animateColorAsState(if (selected) c.tint else c.bg2, spec, label = "zxDetent$i")
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onChange(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 12.sp, color = if (selected) c.ink else c.ink2,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun ZxChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    onClick: () -> Unit,
) {
    val c = ZhaoxiTheme.colors
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) c.ink else c.bg2)
            .selectable(selected = selected, onClick = onClick, role = role)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp,
            color = if (selected) c.onInk else c.ink2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun ZxTabRow(
    tabs: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit = {},
    onSelect: (Int) -> Unit,
) {
    Row(modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEachIndexed { i, t -> ZxChip(t, i == selected, role = Role.Tab, onClick = { onSelect(i) }) }
        content()
    }
}

@Composable
fun ZxDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(modifier.padding(start = inset).height(1.dp).background(ZhaoxiTheme.colors.line))
}

enum class ZxBadgeTone { Neutral, Accent, Warn, Err }

@Composable
fun ZxBadge(text: String, tone: ZxBadgeTone = ZxBadgeTone.Neutral, modifier: Modifier = Modifier) {
    val c = ZhaoxiTheme.colors
    val (bg, fg) = when (tone) {
        ZxBadgeTone.Neutral -> c.bg2 to c.ink2
        ZxBadgeTone.Accent -> c.tint to c.accent
        ZxBadgeTone.Warn -> c.warnBg to c.warn
        ZxBadgeTone.Err -> c.errBg to c.err
    }
    Box(
        modifier.clip(RoundedCornerShape(999.dp)).background(bg)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) { Text(text, fontSize = 9.5.sp, color = fg, fontWeight = FontWeight.SemiBold) }
}
