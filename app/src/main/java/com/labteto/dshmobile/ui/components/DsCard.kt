package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface

/** Quiet Kimi-style content plate used for status and dense tool content. */
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(DsSpacing.tiny),
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val base = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD)
    val targetBackground = when {
        onClick == null -> base
        pressed -> colors.sidebarNavActive.compositeOver(base)
        hovered -> colors.sidebarNavHover.compositeOver(base)
        else -> base
    }
    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = DsAnimations.interactionColor,
        label = "cardBackground",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (elevated) Modifier.shadow(1.dp, DsShapes.block, clip = false) else Modifier)
            .clip(DsShapes.block)
            .background(background)
            .then(
                if (elevated) Modifier.border(1.dp, colors.borderL2, DsShapes.block)
                else Modifier
            )
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
            .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
