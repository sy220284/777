package com.labteto.dshmobile.ui.shenyan

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class SyButtonStyle { PRIMARY, GHOST, OUTLINE, DANGER }

@Composable
fun SyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    style: SyButtonStyle = SyButtonStyle.PRIMARY,
    icon: ImageVector? = null,
    height: Dp = SyDimensions.minimumTouch,
    onHoldRepeat: (() -> Unit)? = null,
) {
    val p = LocalSyPalette.current
    val reduceMotion = LocalSyReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val latestHold by rememberUpdatedState(onHoldRepeat)
    LaunchedEffect(pressed, enabled, loading) {
        if (!pressed || !enabled || loading || latestHold == null) return@LaunchedEffect
        delay(500L)
        while (true) {
            latestHold?.invoke() ?: break
            delay(115L)
        }
    }
    val scale by animateFloatAsState(
        if (pressed && enabled) .97f else 1f,
        animationSpec = tween(if (reduceMotion) 0 else SyDurations.press),
        label = "syButtonScale",
    )
    val bg = when(style) {
        SyButtonStyle.PRIMARY -> p.peach
        SyButtonStyle.GHOST, SyButtonStyle.OUTLINE -> Color.Transparent
        SyButtonStyle.DANGER -> p.danger
    }
    val ink = when(style) {
        SyButtonStyle.PRIMARY -> p.onPeach
        SyButtonStyle.DANGER -> Color.White
        else -> p.text
    }
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(height.coerceAtLeast(SyDimensions.minimumTouch)).scale(scale),
        color = bg,
        contentColor = ink,
        shape = RoundedCornerShape(SyDimensions.cornerControl),
        border = if (style == SyButtonStyle.OUTLINE) BorderStroke(1.dp, p.border) else null,
        interactionSource = interaction,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp), color = ink, strokeWidth = 2.dp
                )
                Spacer(Modifier.size(8.dp))
            }
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
            }
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

data class SyTab(val id: String, val title: String, val enabled: Boolean = true)

/** One 48dp target per tab, with independent spoken selected state. */
@Composable
fun SyTabs(
    items: List<SyTab>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalSyPalette.current
    val reduceMotion = LocalSyReducedMotion.current
    Row(modifier.fillMaxWidth().background(p.l2, RoundedCornerShape(14.dp))
        .padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { tab ->
            val active = selected == tab.id
            val bg by animateColorAsState(
                if (active) p.l3 else Color.Transparent,
                animationSpec = tween(if (reduceMotion) 0 else SyDurations.select),
                label = "syTabBackground",
            )
            Box(Modifier.weight(1f).height(SyDimensions.minimumTouch)
                .background(bg, RoundedCornerShape(12.dp))
                .selectable(selected = active, enabled = tab.enabled, role = Role.Tab) {
                    onSelect(tab.id)
                }, contentAlignment = Alignment.Center) {
                Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (active) p.text else p.secondaryText,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

enum class SyPhase { IDLE, WAITING, RUNNING, DONE, FAILED, CANCELLED, BLOCKED }

fun SyPhase.label(): String = when(this) {
    SyPhase.IDLE -> "未开始"
    SyPhase.WAITING -> "等待中"
    SyPhase.RUNNING -> "执行中"
    SyPhase.DONE -> "已完成"
    SyPhase.FAILED -> "执行失败"
    SyPhase.CANCELLED -> "已取消"
    SyPhase.BLOCKED -> "等待批准"
}

/** Muted clay is a recoverable failure; red is reserved for destructive actions. */
@Composable
fun SyStatusLine(phase: SyPhase, message: String, modifier: Modifier = Modifier) {
    val p = LocalSyPalette.current
    val tint = when (phase) {
        SyPhase.DONE -> p.mint
        SyPhase.FAILED, SyPhase.CANCELLED -> p.clay
        SyPhase.BLOCKED, SyPhase.WAITING -> p.warning
        SyPhase.RUNNING -> p.mint
        SyPhase.IDLE -> p.tertiaryText
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(phase.label(), color = tint, style = MaterialTheme.typography.labelSmall)
        Text(message, color = p.secondaryText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SyMachineText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = LocalSyPalette.current.secondaryText,
        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

/** Each page renders genuine business content; this is purely the visual plate. */
@Composable
fun SyGlassCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SyMaterialFrame(modifier = modifier,
        tier = if (elevated) SyMaterialTier.L4_OVERLAY else SyMaterialTier.L3_CARD,
        onClick = onClick, content = content)
}
