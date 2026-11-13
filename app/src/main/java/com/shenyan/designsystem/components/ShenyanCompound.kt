package com.shenyan.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric

@Composable
fun SyGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = SyMaterialFrame(
    modifier = modifier.fillMaxWidth(),
    tier = SyMaterialTier.L3_CARD,
    horizontalPadding = 14.dp, verticalPadding = 8.dp,
    verticalArrangement = Arrangement.spacedBy(8.dp),
    content = content,
)

/** V5 navigation cell; respects image/icon, metadata, action slot and text sizing. */
@Composable
fun SyCategoryRow(
    icon: ImageVector? = null,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    iconPainter: Painter? = null,
    titleTextStyle: TextStyle? = null,
) {
    val p = LocalSyColors.current
    val shape = RoundedCornerShape(SyMetric.radiusControl)
    val textMeasurer = rememberTextMeasurer()
    val titleStyle = titleTextStyle ?: MaterialTheme.typography.bodyMedium
    val valueStyle = MaterialTheme.typography.bodySmall
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null || iconPainter != null) {
                if (iconPainter != null) Icon(iconPainter, contentDescription = null,
                    modifier = Modifier.size(20.dp), tint = p.textSecondary)
                else if (icon != null) {
                    if (syEntryTileTone(icon.name) != null) SyIconBox(icon = icon)
                    else Icon(icon, contentDescription = null,
                        modifier = Modifier.size(20.dp), tint = p.textSecondary)
                }
            }
            // The metadata must never steal the width required to display the title.
            // Measure the actual text at the user's font scale, rather than guessing
            // a cutoff from dp, character count or a specific translated label.
            BoxWithConstraints(Modifier.weight(1f)) {
                val requiredInlineWidth = if (value == null) 0.dp else with(LocalDensity.current) {
                    (textMeasurer.measure(title, style = titleStyle, softWrap = false, maxLines = 1).size.width +
                        textMeasurer.measure(value, style = valueStyle, softWrap = false, maxLines = 1).size.width).toDp() + 10.dp
                }
                // At accessibility font scales use a stable stacked layout, even if a
                // one-line text measurement suggests the inline row might fit.
                // Font fallback and paragraph shaping can exceed intrinsic width.
                val stackValue = value != null &&
                    (LocalDensity.current.fontScale >= 1.2f || requiredInlineWidth > maxWidth)
                if (stackValue) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, modifier = Modifier.fillMaxWidth(),
                            color = p.text, style = titleStyle)
                        if (!subtitle.isNullOrBlank()) Text(subtitle,
                            color = p.textSecondary, style = valueStyle)
                        Text(value ?: "", modifier = Modifier.fillMaxWidth(),
                            color = p.textTertiary, style = valueStyle, textAlign = TextAlign.End)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, color = p.text, style = titleStyle,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!subtitle.isNullOrBlank()) Text(subtitle,
                                color = p.textSecondary, style = valueStyle,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (value != null) Text(value, color = p.textTertiary,
                            style = valueStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            trailing?.invoke()
        }
    }
    if (onClick == null) {
        Surface(modifier, shape = shape, color = Color.Transparent) { row() }
    } else {
        Surface(onClick = onClick, modifier = modifier,
            shape = shape, color = Color.Transparent) { row() }
    }
}

/** Compact icon and label with a transparent background and a comfortable touch target. */
@Composable
fun SyQuickActionTile(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    iconPainter: Painter? = null,
) {
    val p = LocalSyColors.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(SyMetric.radiusControl),
        color = Color.Transparent,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .padding(horizontal = 2.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            when {
                iconPainter != null -> Icon(iconPainter, contentDescription = null,
                    tint = if (enabled) p.text else p.textTertiary,
                    modifier = Modifier.size(22.dp))
                icon != null -> Icon(icon, contentDescription = null,
                    tint = if (enabled) p.text else p.textTertiary,
                    modifier = Modifier.size(22.dp))
            }
            Text(label, color = if (enabled) p.text else p.textTertiary,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
