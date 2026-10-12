package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalConfiguration
import com.labteto.dshmobile.ui.shenyan.LocalSyPalette
import com.labteto.dshmobile.ui.shenyan.SyDimensions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsMetrics
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Compatibility API. New visuals now live in canonical ShenyanChrome.kt. */
@Deprecated("V5: replace with com.shenyan.designsystem.components.SyTopBar")
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
    actionPainter: Painter? = null,
    backIcon: ImageVector = FeatherIcons.ChevronLeft,
    plainBackButton: Boolean = false,
) {
    com.shenyan.designsystem.components.SyTopBar(
        title = title,
        onBack = onBack,
        backContentDescription = backContentDescription,
        backIcon = backIcon,
        modifier = modifier,
        subtitle = subtitle,
        largeTitle = largeTitle,
        actionIcon = actionIcon,
        actionPainter = actionPainter,
        actionContentDescription = actionContentDescription,
        actionEnabled = actionEnabled,
        onAction = onAction,
    )
}

/** 统一 compact peer selector: neutral track, white selected segment, no chromatic category fill. */
@Composable
fun DsSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val sy = LocalSyPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .background(sy.l2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val segmentColor by animateColorAsState(
                targetValue = if (selected) sy.l3 else Color.Transparent,
                animationSpec = DsAnimations.interactionColor,
                label = "segmentedTabBackground",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = SyDimensions.minimumTouch)
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
                    color = if (selected) sy.text else sy.secondaryText,
                    maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
