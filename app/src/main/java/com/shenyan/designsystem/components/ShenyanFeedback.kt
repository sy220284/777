package com.shenyan.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.foundation.SyMotion

/** Unlike an icon's 20dp glyph, its semantic/touch target is never less than 48dp. */
@Composable
fun SyIconButton(
    icon: ImageVector?, contentDescription: String?, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    tint: Color? = null, iconSize: Dp = 20.dp,
    containerColor: Color = Color.Transparent,
    shadowElevation: Dp = 0.dp,
    selected: Boolean = false,
    iconPainter: Painter? = null,
) {
    val p = LocalSyColors.current
    val motion = LocalSyReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) .96f else 1f,
        animationSpec = tween(if (motion) 0 else SyMotion.pressMs),
        label = "syIconPress",
    )
    val resolved = when {
        selected -> p.materialL3
        pressed -> p.materialL2
        hovered -> p.materialL1
        else -> containerColor
    }
    val fill by animateColorAsState(
        resolved, animationSpec = tween(if (motion) 0 else SyMotion.selectMs),
        label = "syIconSurface",
    )
    val foreground = if (selected) p.primaryPressed else tint ?: p.textSecondary
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.size(SyMetric.touch),
        interactionSource = interaction, color = fill, shadowElevation = shadowElevation,
        shape = CircleShape,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val glyphModifier = Modifier.requiredSize(iconSize).scale(scale)
            when {
                iconPainter != null -> Icon(
                    painter = iconPainter, contentDescription = contentDescription,
                    tint = if (enabled) foreground else foreground.copy(alpha = .4f),
                    modifier = glyphModifier)
                icon != null -> Icon(
                    imageVector = icon, contentDescription = contentDescription,
                    tint = if (enabled) foreground else foreground.copy(alpha = .4f),
                    modifier = glyphModifier)
            }
        }
    }
}

/** Empty state is never a fake loading skeleton; recovery retains the real caller's callback. */
@Composable
fun SyEmptyState(
    icon: ImageVector, title: String, body: String,
    modifier: Modifier = Modifier, actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val p = LocalSyColors.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(SyMetric.radiusCard),
            color = p.materialL3,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = p.textSecondary,
                    modifier = Modifier.size(24.dp))
            }
        }
        Text(title, color = p.text, style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center)
        Text(body, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center)
        if (onAction != null && !actionText.isNullOrBlank()) {
            SyButton(actionText, onClick = onAction)
        }
    }
}

@Composable
fun SyLoadingState(
    icon: ImageVector, label: String, modifier: Modifier = Modifier,
) {
    val p = LocalSyColors.current
    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = p.primary, modifier = Modifier.size(24.dp))
        CircularProgressIndicator(color = p.primary, strokeWidth = 2.dp,
            modifier = Modifier.size(22.dp))
        Text(label, color = p.textSecondary, textAlign = TextAlign.Center)
    }
}

/** Passive badges can be 24dp. Interactive filters are 48dp with correct semantics. */
@Composable
fun SyPill(
    text: String, modifier: Modifier = Modifier,
    selected: Boolean = false, warn: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalSyColors.current
    val shape = RoundedCornerShape(999.dp)
    val fill = when {
        warn -> p.warning.copy(alpha = .15f)
        selected -> p.materialL3
        else -> p.materialL2
    }
    val ink = when {
        warn -> p.warning
        selected -> p.primaryPressed
        else -> p.textSecondary
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.heightIn(min = SyMetric.touch),
            shape = shape, color = fill, border = BorderStroke(1.dp, p.border)) {
            Box(Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center) {
                Text(text, color = ink, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = fill) {
            Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                color = ink, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun SyFilterChip(text: String, selected: Boolean, onClick: () -> Unit) =
    SyPill(text = text, selected = selected, onClick = onClick)

/** V5 selectable/switch row preserves user actions and distinguishes destructive intent. */
@Composable
fun SySheetChoiceRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingText: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    switchChecked: Boolean? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val p = LocalSyColors.current
    val shape = RoundedCornerShape(SyMetric.radiusControl)
    val action = if (switchChecked == null) Modifier.clickable(
        enabled = enabled, onClick = onClick,
    ) else Modifier.toggleable(
        value = switchChecked, enabled = enabled, role = Role.Switch,
        onValueChange = { onClick() },
    )
    Row(
        modifier.fillMaxWidth().heightIn(min = if (subtitle == null) 56.dp else 72.dp)
            .background(if (selected) p.materialL3 else Color.Transparent, shape)
            .then(action).padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null,
            tint = if (danger) p.danger else p.textSecondary,
            modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                color = if (danger) p.danger else p.text)
            if (subtitle != null) Text(subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = p.textSecondary)
        }
        if (trailingText != null) Text(trailingText,
            style = MaterialTheme.typography.labelSmall, color = p.textTertiary)
        if (switchChecked != null) {
            SySwitchControl(switchChecked, onCheckedChange = null, enabled = enabled)
        }
    }
}
