package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** Large borderless surface used for mobile setting groups and capability panels. */
@Composable
fun DsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            .background(colors.bgLayer1)
            .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small),
        content = content,
    )
}

/**
 * One mobile-first category row. The icon establishes the function family, the subtitle explains
 * scope, and the trailing value keeps current state visible before the row is opened.
 */
@Composable
fun DsCategoryRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = DsTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DsMetrics.rowHeight)
            .clip(DsShapes.row)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.labelSecondary, modifier = Modifier.size(DsMetrics.icon))
        }
        Spacer(Modifier.width(DsSpacing.small))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = DsType.base16,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        value?.let {
            Spacer(Modifier.width(DsSpacing.small))
            Text(it, style = DsType.std14, color = colors.labelTertiary, maxLines = 1)
        }
        if (trailing != null) {
            Spacer(Modifier.width(DsSpacing.small))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(DsSpacing.small))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.labelCaption,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Square shortcut used by the composer's add panel. */
@Composable
fun DsQuickActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        color = colors.bgModulePlatform,
        shape = DsShapes.block,
    ) {
        Column(
            Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.comfortable),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) colors.labelPrimary else colors.labelCaption,
                modifier = Modifier.size(24.dp),
            )
            Text(
                label,
                style = DsType.small13,
                color = if (enabled) colors.labelPrimary else colors.labelCaption,
                maxLines = 1,
            )
        }
    }
}
