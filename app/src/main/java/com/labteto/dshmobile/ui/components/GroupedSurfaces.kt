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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            .padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
        content = content,
    )
}

/** Compact disclosure row used across settings, tools and feature indexes. */
@Composable
fun DsCategoryRow(
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
    val colors = DsTheme.colors
    // Preserve full category titles beside their values at large accessibility font scales.
    val compactWithValue = value != null && LocalDensity.current.fontScale >= 1.2f
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
            .heightIn(min = 64.dp)
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
            .padding(
                horizontal = if (compactWithValue) DsSpacing.xsmall else DsSpacing.small,
                vertical = DsSpacing.medium,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DsIconBox(icon = icon, iconPainter = iconPainter)
        Spacer(Modifier.width(if (compactWithValue) DsSpacing.tiny else DsSpacing.medium))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            Text(
                text = title,
                style = (titleTextStyle ?: DsType.std14).withReadingWeight(),
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = DsType.small13.withReadingWeight(),
                    color = colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (compactWithValue) {
                // Keep the entire title column wide at accessibility font scales.
                // The count and navigation affordance share the secondary row.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = requireNotNull(value),
                        style = DsType.small13.withReadingWeight().copy(fontFamily = DsType.contentFont),
                        color = colors.labelSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
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
        }
        if (!compactWithValue) {
            value?.let {
                Spacer(Modifier.width(DsSpacing.small))
                Text(
                    text = it,
                    style = DsType.small13.withReadingWeight().copy(fontFamily = DsType.contentFont),
                    color = colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
        }
        if (!compactWithValue) {
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
                maxLines = 2,
            )
        }
    }
}
