package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
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

/**
 * Kimi-style mobile page bar.
 *
 * The title stays optically centered while Back and the optional action keep fixed 48dp hit areas.
 * Root pages may request [largeTitle], but it only raises typographic weight/size instead of creating
 * a second stacked toolbar; this keeps every secondary page on the same vertical rhythm.
 */
@Composable
fun DsTopBar(
    title: String,
    onBack: () -> Unit,
    backContentDescription: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionIcon: ImageVector? = null,
    actionPainter: Painter? = null,
    actionContentDescription: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    largeTitle: Boolean = false,
) {
    val colors = DsTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DsMetrics.topBarHeight),
    ) {
        DsIconButton(
            icon = FeatherIcons.ArrowLeft,
            contentDescription = backContentDescription,
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
            tint = colors.labelPrimary,
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 58.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                title,
                style = (if (largeTitle) DsType.large20 else DsType.headline17).withReadingWeight(),
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
        if ((actionIcon != null || actionPainter != null) && onAction != null) {
            DsIconButton(
                icon = actionIcon,
                iconPainter = actionPainter,
                contentDescription = actionContentDescription,
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier.align(Alignment.CenterEnd),
                tint = colors.labelPrimary,
            )
        } else {
            Box(Modifier.align(Alignment.CenterEnd).size(DsSpacing.touchTarget))
        }
    }
}

/** Kimi-style compact peer selector: neutral track, white selected segment, no chromatic category fill. */
@Composable
fun DsSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
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
                targetValue = if (selected) colors.bgLayer1 else Color.Transparent,
                animationSpec = DsAnimations.interactionColor,
                label = "segmentedTabBackground",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(DsShapes.row)
                    .background(segmentColor)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = (if (selected) DsType.small13Strong else DsType.small13).withReadingWeight(),
                    color = if (selected) colors.labelPrimary else colors.labelTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}
