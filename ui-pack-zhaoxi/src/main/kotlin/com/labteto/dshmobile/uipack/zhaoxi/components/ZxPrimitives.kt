package com.labteto.dshmobile.uipack.zhaoxi.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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

/** E8.18 六态按钮；边框、焦点、按压和加载均可见且保留 48dp 触控范围。 */
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f,
        ZhaoxiMotion.spec(ZhaoxiDuration.INSTANT), label = "zxBtnPress")
    val spec = ZhaoxiMotion.spec<androidx.compose.ui.graphics.Color>(ZhaoxiDuration.FAST)
    val bg by animateColorAsState(
        when {
            !enabled -> c.bg2
            variant == ZxButtonVariant.Ink -> c.ink
            variant == ZxButtonVariant.Danger -> c.err
            variant == ZxButtonVariant.Outline -> c.card
            else -> c.bg2
        }, spec, label = "zxBtnBg",
    )
    val fg = when {
        !enabled -> c.ink3
        variant == ZxButtonVariant.Danger -> if (c.bg.luminance() < .5f) c.ink else c.onInk
        variant == ZxButtonVariant.Ink -> c.onInk
        else -> c.ink
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(bg)
            .border(if (focused) 2.dp else 1.dp,
                if (focused) c.accentInk else if (variant == ZxButtonVariant.Outline) c.line else bg,
                shape)
            .clickable(
                enabled = enabled && !loading,
                role = Role.Button,
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
            )
            .semantics {
                contentDescription = text
                if (loading) stateDescription = "处理中"
            }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, modifier = Modifier.alpha(if (loading) 0f else 1f),
            color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, textAlign = TextAlign.Center)
        if (loading) CircularProgressIndicator(
            modifier = Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f,
        ZhaoxiMotion.spec(ZhaoxiDuration.INSTANT), label = "zxIconPress")
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .border(if (focused) 2.dp else 0.dp, c.accentInk, shape)
            .clickable(
                enabled = enabled, onClick = onClick, role = Role.Button,
                interactionSource = interaction,
                indication = androidx.compose.foundation.LocalIndication.current)
            .semantics { this.contentDescription = contentDescription }
            .alpha(if (enabled) 1f else .45f)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides c.ink,
        ) { content() }
    }
}

@Composable
fun ZxField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: Boolean = false,
    errorMessage: String? = null,
    textStyle: TextStyle = TextStyle(fontSize = 14.sp),
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val c = ZhaoxiTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(ZhaoxiTheme.shapes.field)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(c.bg2)
            .border(if (focused) 2.dp else 1.dp,
                if (error) c.err else if (focused) c.accentInk else c.line, shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = textStyle.copy(color = c.ink),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            interactionSource = source,
            cursorBrush = SolidColor(c.accentInk),
            modifier = Modifier.fillMaxWidth().semantics {
                if (placeholder.isNotEmpty()) contentDescription = placeholder
                if (error) this.error(errorMessage ?: "输入值不正确")
            },
        ) { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = c.ink3,
                    fontSize = textStyle.fontSize)
                inner()
            }
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
    val track by animateColorAsState(if (checked) c.accent else c.bg2, spec, label = "zxSwTrack")
    val thumbX by animateDpAsState(
        if (checked) 18.dp else 0.dp,
        ZhaoxiMotion.spec(ZhaoxiDuration.FAST), label = "zxSwThumb")
    Box(
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { contentDescription?.let { this.contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp)).background(track).padding(3.dp),
            contentAlignment = Alignment.CenterStart) {
            Box(Modifier.offset(x = thumbX).size(20.dp).clip(CircleShape).background(c.card))
        }
    }
}

/** Reusable press feedback for clickable rows and chips. Animation duration respects reduced motion. */
@Composable
fun Modifier.zxPressSurface(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f,
        ZhaoxiMotion.spec(ZhaoxiDuration.INSTANT), label = "zxRowPress")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
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
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .zxPressSurface(source)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) c.ink else c.bg2)
            .selectable(
                selected = selected, onClick = onClick, role = role,
                interactionSource = source,
                indication = androidx.compose.foundation.LocalIndication.current)
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
    Row(modifier.horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
        ZxBadgeTone.Accent -> c.tint to c.accentInk
        ZxBadgeTone.Warn -> c.warnBg to c.warn
        ZxBadgeTone.Err -> c.errBg to c.err
    }
    Box(
        modifier.clip(RoundedCornerShape(999.dp)).background(bg)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) { Text(text, fontSize = 9.5.sp, color = fg, fontWeight = FontWeight.SemiBold) }
}
