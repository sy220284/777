package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

/**
 * 统一 grouped surface: quiet white sheet on the neutral page background.
 * Rows own interaction feedback; the group itself carries no decorative border or shadow.
 */
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
            .background(colors.wallpaperSurface(WallpaperSurfaceLevel.CARD))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        content = content,
    )
}

/**
 * Compact disclosure row used across settings, tools and feature indexes.
 *
 * [iconFamily] stays in the signature for source compatibility, but the 当前视觉规范 keeps
 * function icons monochrome. Selection and semantic state are expressed by row/background/status,
 * never by assigning a different color family to each function.
 */
@Composable
fun DsCategoryRow(
    icon: ImageVector? = null,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    iconFamily: DsIconFamily = DsIconFamily.Neutral,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    iconPainter: Painter? = null,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val feedbackColor by animateColorAsState(
        targetValue = when {
            onClick == null -> Color.Transparent
            pressed -> colors.sidebarNavActive
            hovered -> colors.sidebarNavHover
            else -> Color.Transparent
        },
        animationSpec = DsAnimations.interactionColor,
        label = "categoryRowFeedback",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(DsShapes.row)
            .background(feedbackColor)
            .then(
                if (onClick != null) {
                    Modifier
                        .hoverable(interaction)
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DsIconBox(icon = icon, iconPainter = iconPainter, family = iconFamily)
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = DsType.std14.withReadingWeight(),
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        value?.let {
            Spacer(Modifier.width(DsSpacing.small))
            Text(
                text = it,
                style = DsType.small13.withReadingWeight(),
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 136.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(DsSpacing.small))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = colors.labelCaption,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Compact add-panel tile: monochrome icon, neutral fill, no function-family color coding. */
@Composable
fun DsQuickActionTile(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    iconPainter: Painter? = null,
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
            modifier = Modifier.heightIn(min = 92.dp).padding(horizontal = DsSpacing.medium, vertical = DsSpacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                iconPainter != null -> Icon(
                    painter = iconPainter,
                    contentDescription = null,
                    tint = if (enabled) colors.labelSecondary else colors.labelCaption,
                    modifier = Modifier.size(22.dp),
                )
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) colors.labelSecondary else colors.labelCaption,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = label,
                style = DsType.small13.withReadingWeight(),
                color = if (enabled) colors.labelPrimary else colors.labelCaption,
                maxLines = 1,
            )
        }
    }
}
