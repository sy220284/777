package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface

/**
 * Shared geometry for every conversation composer.
 *
 * Chat, group chat, work and remote sessions all use this shell so their idle/focused height,
 * outer margins, radius, border and action sizing cannot drift independently.
 */
const val DS_CONVERSATION_COMPOSER_TAG = "ds-conversation-composer"

object DsComposerMetrics {
    val actionTouchTarget = 48.dp
    val actionVisualSize = 32.dp
    val primaryActionVisualSize = 34.dp
    val actionIconSize = 18.dp
}

@Composable
fun DsConversationComposer(
    modifier: Modifier = Modifier,
    surfaceColor: Color = DsTheme.colors.wallpaperSurface(
        WallpaperSurfaceLevel.INPUT,
        BackgroundRegion.BOTTOM,
        DsTheme.colors.composerCard,
    ),
    shadowElevation: Dp = 1.dp,
    animateSize: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = modifier
            .testTag(DS_CONVERSATION_COMPOSER_TAG)
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)
            .then(
                if (animateSize) Modifier.animateContentSize(DsAnimations.expand)
                else Modifier,
            ),
        shape = DsShapes.composer,
        color = surfaceColor,
        border = BorderStroke(1.dp, colors.borderL1),
        shadowElevation = shadowElevation,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = DsSpacing.small,
                vertical = DsSpacing.tiny,
            ),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            content = content,
        )
    }
}

/**
 * Compact composer-only action. The visual circle stays small while the surrounding target keeps a
 * full 48dp hit area; visual chrome stays compact without shrinking touch accessibility.
 */
@Composable
fun DsComposerAction(
    icon: ImageVector?,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = DsTheme.colors.labelSecondary,
    containerColor: Color = Color.Transparent,
    visualSize: Dp = DsComposerMetrics.actionVisualSize,
    iconSize: Dp = DsComposerMetrics.actionIconSize,
    content: (@Composable () -> Unit)? = null,
) {
    val resolvedContainer = if (enabled) containerColor else containerColor.copy(alpha = 0.38f)
    val resolvedTint = if (enabled) tint else tint.copy(alpha = 0.38f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) DsAnimations.Scale.pressed else DsAnimations.Scale.normal,
        animationSpec = DsAnimations.pressScale,
        label = "composerActionScale",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(DsComposerMetrics.actionTouchTarget)
            .semantics { this.contentDescription = contentDescription },
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        interactionSource = interaction,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(visualSize)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(resolvedContainer),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    content != null -> content()
                    icon != null -> Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = resolvedTint,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}
