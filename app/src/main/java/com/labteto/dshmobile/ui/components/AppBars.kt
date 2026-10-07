package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

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
    largeTitle: Boolean = false,
) {
    val colors = DsTheme.colors
    // Kimi title bars float directly on the page; icon buttons provide interaction feedback.
    if (largeTitle) {
        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsIconButton(
                    icon = FeatherIcons.ArrowLeft,
                    contentDescription = backContentDescription,
                    onClick = onBack,
                )
                Spacer(Modifier.weight(1f))
                if (actionIcon != null && onAction != null) {
                    DsIconButton(
                        icon = actionIcon,
                        contentDescription = actionContentDescription,
                        onClick = onAction,
                        enabled = actionEnabled,
                    )
                } else {
                    Box(Modifier.size(DsSpacing.touchTarget))
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    start = DsSpacing.small,
                    end = DsSpacing.small,
                    bottom = DsSpacing.small,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    style = DsType.largeTitle28.withReadingWeight(),
                    color = colors.labelPrimary,
                    maxLines = 2,
                )
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = DsType.small13.withReadingWeight(),
                        color = colors.labelTertiary,
                        maxLines = 2,
                    )
                }
            }
        }
    } else {
        Box(
            modifier = modifier.fillMaxWidth().heightIn(min = DsMetrics.topBarHeight),
        ) {
            DsIconButton(
                icon = FeatherIcons.ArrowLeft,
                contentDescription = backContentDescription,
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    title,
                    style = DsType.base16Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                    maxLines = 1,
                )
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = DsType.caption11.withReadingWeight(),
                        color = colors.labelTertiary,
                        maxLines = 1,
                    )
                }
            }
            if (actionIcon != null && onAction != null) {
                DsIconButton(
                    icon = actionIcon,
                    contentDescription = actionContentDescription,
                    onClick = onAction,
                    enabled = actionEnabled,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            } else {
                Box(Modifier.size(DsSpacing.touchTarget).align(Alignment.CenterEnd))
            }
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
    val selectedBackground = colors.bgLayer1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .background(colors.bgModulePlatform)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val segmentColor by animateColorAsState(
                targetValue = if (selected) selectedBackground else Color.Transparent,
                animationSpec = DsAnimations.interactionColor,
                label = "segmentedTabBackground",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = DsSpacing.touchTarget)
                    .clip(DsShapes.row)
                    .background(segmentColor)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = (if (selected) DsType.small13Strong else DsType.small13).withReadingWeight(),
                    color = if (selected) colors.labelPrimary else colors.labelSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
