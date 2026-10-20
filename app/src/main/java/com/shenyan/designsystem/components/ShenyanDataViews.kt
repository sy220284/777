package com.shenyan.designsystem.components

import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.*

/** Real running states animate; history and reduced motion remain still. */
@Composable
fun SyStateDot(phase: SyPhase, label: String, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val p = LocalSyColors.current
    val color = when (phase) {
        SyPhase.RUNNING, SyPhase.DONE -> p.mint
        SyPhase.FAILED, SyPhase.CANCELLED -> p.clay
        SyPhase.BLOCKED, SyPhase.WAITING -> p.warning
        SyPhase.IDLE -> p.textTertiary
    }
    val alpha = syLiveAlpha(phase == SyPhase.RUNNING)
    Canvas(modifier.size(size).semantics { contentDescription = label }) {
        drawCircle(color.copy(alpha = .10f * alpha), radius = this.size.minDimension * .58f)
        drawCircle(color.copy(alpha = alpha), radius = this.size.minDimension * .34f)
    }
}

@Composable
private fun syLiveAlpha(active: Boolean): Float {
    if (!active || LocalSyReduceMotion.current) return 1f
    val transition = rememberInfiniteTransition(label = "syLiveStatus")
    val alpha by transition.animateFloat(.55f, 1f,
        infiniteRepeatable(tween(SyMotion.livePulseMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "syLiveAlpha")
    return alpha
}

data class SyTimelineEntry(val text: String, val phase: SyPhase, val statusLabel: String, val detail: String? = null)

/** Connector uses measured row height; long receipts do not break the line. */
@Composable
fun SyTimeline(items: List<SyTimelineEntry>, modifier: Modifier = Modifier) {
    val p = LocalSyColors.current
    Column(modifier) {
        items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth().drawBehind {
                val x = 9.dp.toPx(); val y = 12.5.dp.toPx(); val stroke = 1.5.dp.toPx()
                if (index > 0) drawLine(p.border, Offset(x, 0f), Offset(x, y), stroke)
                if (index < items.lastIndex) drawLine(p.border, Offset(x, y), Offset(x, size.height), stroke)
            }.semantics(mergeDescendants = true) { stateDescription = item.statusLabel }, verticalAlignment = Alignment.Top) {
                Box(Modifier.width(18.dp).height(25.dp).padding(top = 8.dp)) {
                    SyStateDot(item.phase, item.statusLabel, size = 9.dp)
                }
                Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.text, color = p.text, style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(item.detail?.takeIf(String::isNotBlank), item.statusLabel).joinToString(" · "),
                        color = p.textTertiary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Masked data stays masked; this row never reads credentials or persists configuration. */
@Composable
fun SyValueRow(label: String, value: String?, modifier: Modifier = Modifier, hint: String? = null,
    masked: Boolean = false, configured: Boolean? = null, onClick: (() -> Unit)? = null,
    trailingIcon: ImageVector? = null) {
    val p = LocalSyColors.current
    val display = when {
        value.isNullOrBlank() -> "—"
        masked && value.length > 4 -> "••••••••${value.takeLast(4)}"
        else -> value
    }
    Row(modifier.fillMaxWidth().heightIn(min = SyMetric.touch)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            Text(display, color = if (value.isNullOrBlank()) p.textTertiary else p.text,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (masked) androidx.compose.ui.text.font.FontFamily.Monospace else MaterialTheme.typography.bodyMedium.fontFamily,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, color = p.textTertiary, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
        }
        configured?.let {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(8.dp).background(if (it) p.mint else p.clay, CircleShape))
        }
        if (onClick != null && trailingIcon != null) {
            Spacer(Modifier.width(12.dp))
            Icon(trailingIcon, contentDescription = null, tint = p.textTertiary)
        }
    }
}

/** One shared icon palette for the expanded list and the collapsed rail. */
internal fun syEntryTileTone(name: String): Color? {
    if (!name.startsWith("SyEntry.")) return null
    return when (name.removePrefix("SyEntry.")) {
"Persona", "Search", "Account", "Gallery" -> Color(0xFFE2E7E9)
        "NewSession", "WorkspaceFiles", "Quota", "Tools" -> Color(0xFFEEE4D8)
        "Tasks", "Permissions", "Memory", "Connections" -> Color(0xFFDFE6DA)
        "Settings", "Models", "Diary", "Plugins" -> Color(0xFFE8E2E9)
        "GroupChat", "Appearance", "Notifications", "Skills" -> Color(0xFFF1DFD3)
        else -> Color(0xFFE4E8E9)
    }
}

/**
 * 现行入口采用参考稿的低饱和色块和墨线；动作图标不添加色块。
 * 深/哑光主题继续使用当前主题材质，避免浅色卡片与暗背景割裂。
 */
@Composable
fun SyIconBox(icon: ImageVector? = null, iconPainter: Painter? = null, modifier: Modifier = Modifier,
    contentDescription: String? = null, active: Boolean = false) {
    val p = LocalSyColors.current
    val alpha = syLiveAlpha(active)
    val tileTone = syEntryTileTone(icon?.name.orEmpty())
    val tile = tileTone?.let { if (p.text.luminance() > .5f) p.materialL4 else it }

    Box(
        modifier.size(32.dp).then(
            if (tile != null) Modifier.background(tile, RoundedCornerShape(9.dp)) else Modifier
        ),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = Modifier.size(20.dp).graphicsLayer { this.alpha = alpha }
        val ink = if (tile != null) p.text else p.textSecondary
        if (iconPainter != null) Icon(iconPainter, contentDescription, glyph, tint = ink)
        else if (icon != null) Icon(icon, contentDescription, glyph, tint = ink)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyEmptyHero(headline: String, subtitle: String?, chips: List<String> = emptyList(),
    onChipClick: (String) -> Unit = {}) {
    val p = LocalSyColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(headline, color = p.text, style = MaterialTheme.typography.headlineLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        subtitle?.let { Text(it, color = p.textSecondary, style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        if (chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { chip -> SyPill(chip, onClick = { onChipClick(chip) }) }
        }
    }
}

/** Tool details stay controlled; passive status rows do not pretend to be clickable. */
@Composable
fun SyDisclosureRow(title: String, summary: String?, icon: ImageVector?, phase: SyPhase,
    expanded: Boolean, onToggle: (() -> Unit)?, modifier: Modifier,
    chevron: ImageVector, stateLabel: String, disclosureLabel: String,
    content: (@Composable () -> Unit)?) {
    val p = LocalSyColors.current
    val ms = SyMotion.duration(SyMotion.toolExpandMs, LocalSyReduceMotion.current,
        com.shenyan.designsystem.foundation.LocalSyMotionTempo.current)
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, tween(ms), label = "syDisclosureChevron")
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (onToggle != null) SyMetric.touch else 24.dp)
            .then(if (onToggle != null) Modifier.clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = disclosureLabel } else Modifier)
            .padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onToggle != null) {
                Icon(chevron, null, Modifier.size(14.dp).graphicsLayer { rotationZ = rotation }, tint = p.textTertiary)
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                if (phase != SyPhase.IDLE) SyStateDot(phase, stateLabel)
                else if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = p.textSecondary)
            }
            Spacer(Modifier.width(8.dp))
            Text(title, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (summary != null) {
                Spacer(Modifier.width(6.dp))
                Text(summary, color = p.textTertiary, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        AnimatedVisibility(expanded && content != null,
            enter = expandVertically(tween(ms)) + fadeIn(tween(ms)),
            exit = shrinkVertically(tween(ms)) + fadeOut(tween(ms))) { Column { content?.invoke() } }
    }
}
