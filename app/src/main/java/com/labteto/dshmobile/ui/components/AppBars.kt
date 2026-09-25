package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface

/** Shared full-page top bar aligned across local utility screens. */
@Composable
fun DsTopBar(
    title: String,
    onBack: () -> Unit,
    backContentDescription: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionIcon: ImageVector? = null,
    actionContentDescription: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val floating = colors.wallpaperSurface(
        WallpaperSurfaceLevel.FLOATING,
        BackgroundRegion.TOP,
    )
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = DsMetrics.topBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DsIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = backContentDescription,
            onClick = onBack,
            containerColor = floating,
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = DsType.large20, color = colors.labelPrimary, maxLines = 1)
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(it, style = DsType.caption11, color = colors.labelTertiary, maxLines = 1)
            }
        }
        if (actionIcon != null && onAction != null) {
            DsIconButton(
                icon = actionIcon,
                contentDescription = actionContentDescription,
                onClick = onAction,
                enabled = actionEnabled,
                containerColor = floating,
            )
        } else {
            Box(Modifier.size(DsSpacing.touchTarget))
        }
    }
}

/** Compact peer-section selector that keeps wallpaper glass hierarchy intact. */
@Composable
fun DsSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val background = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD)
    val selectedBackground = colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .background(background)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(DsShapes.row)
                    .background(if (selected) selectedBackground else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = if (selected) DsType.small13Strong else DsType.small13,
                    color = if (selected) colors.labelPrimary else colors.labelSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
