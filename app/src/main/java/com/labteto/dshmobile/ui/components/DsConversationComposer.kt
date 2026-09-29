package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.BackgroundRegion
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
object DsComposerMetrics {
    val actionTouchTarget = 40.dp
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)
            .animateContentSize(),
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
 * comfortable hit area; using this instead of the global 48dp icon button prevents the composer
 * from becoming taller than the reference compact input.
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
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(DsComposerMetrics.actionTouchTarget)
            .semantics { this.contentDescription = contentDescription },
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(visualSize)
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
