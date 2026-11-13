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
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.foundation.SyMotion
import com.shenyan.designsystem.foundation.LocalSyMotionTempo

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
    bare: Boolean = false,
) {
    val p = LocalSyColors.current
    val motion = LocalSyReduceMotion.current
    val tempo = LocalSyMotionTempo.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled && !motion) SyMotion.pressedScale else 1f,
        animationSpec = tween(SyMotion.duration(SyMotion.pressMs, motion, tempo)),
        label = "syIconPress",
    )
    val resolved = when {
        bare -> Color.Transparent
        selected -> syMaterialColor(SyMaterialTier.L3_CARD)
        pressed -> syMaterialColor(SyMaterialTier.L2_BAR)
        hovered -> syMaterialColor(SyMaterialTier.L1_SECTION)
        else -> containerColor
    }
    val fill by animateColorAsState(
        resolved, animationSpec = tween(SyMotion.duration(SyMotion.selectMs, motion, tempo)),
        label = "syIconSurface",
    )
    val entryTone = if (!bare && iconPainter == null) syEntryTileTone(icon?.name.orEmpty()) else null
    val tile = entryTone?.let { if (p.text.luminance() > .5f) p.materialL4 else it }
    val foreground = if (selected) p.text else tint ?: if (tile != null) p.text else p.textSecondary
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.size(SyMetric.touch),
        interactionSource = interaction, color = fill,
        shadowElevation = if (bare) 0.dp else shadowElevation,
        shape = if (tile != null) RoundedCornerShape(12.dp) else CircleShape,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = if (tile != null) Modifier.requiredSize(32.dp)
                    .background(tile, RoundedCornerShape(9.dp)) else Modifier,
                contentAlignment = Alignment.Center,
            ) {
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
}

/** Empty state is never a fake loading skeleton; recovery retains the real caller's callback. */
@Composable
fun SyEmptyState(
    icon: ImageVector?, title: String, body: String,
    modifier: Modifier = Modifier, actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val p = LocalSyColors.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        if (icon != null) {
            SyMaterialSurface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(SyMetric.radiusCard),
                tier = SyMaterialTier.L3_CARD,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = p.textSecondary,
                        modifier = Modifier.size(24.dp))
                }
            }
        }
        Text(title, modifier = Modifier.widthIn(max = 340.dp).semantics { heading() },
            color = p.text, style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center)
        if (body.isNotBlank()) Text(body, modifier = Modifier.widthIn(max = 340.dp),
            color = p.textSecondary, style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center)
        if (onAction != null && !actionText.isNullOrBlank()) {
            SyButton(actionText, onClick = onAction, modifier = Modifier.widthIn(min = 180.dp, max = 340.dp))
        }
    }
}

@Composable
fun SyLoadingState(
    icon: ImageVector, label: String, modifier: Modifier = Modifier,
) {
    val p = LocalSyColors.current
    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 30.dp)
        .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
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
    val tint = when {
        warn -> p.warning.copy(alpha = .15f)
        selected -> p.selected.copy(alpha = .22f)
        else -> Color.Unspecified
    }
    val ink = when {
        warn -> p.warningInk
        selected -> p.text
        else -> p.textSecondary
    }
    if (onClick != null) {
        SyMaterialSurface(onClick = onClick, modifier = modifier.sizeIn(minWidth = SyMetric.touch, minHeight = SyMetric.touch).semantics { this.selected = selected },
            shape = shape, tier = SyMaterialTier.L1_SECTION, tint = tint, shadowElevation = 0.dp, border = BorderStroke(1.dp, if (selected) p.primary else p.border)) {
            Box(Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center) {
                Text(text, color = ink, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else {
        SyMaterialSurface(modifier = modifier, shape = shape, tier = SyMaterialTier.L1_SECTION, tint = tint, shadowElevation = 0.dp) {
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
            .background(if (selected) syMaterialColor(SyMaterialTier.L3_CARD) else Color.Transparent, shape)
            .then(action).padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null,
            tint = if (danger) p.dangerInk else p.textSecondary,
            modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                color = if (danger) p.dangerInk else p.text)
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
