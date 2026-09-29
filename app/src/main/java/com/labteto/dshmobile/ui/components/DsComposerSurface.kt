package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
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

const val DS_COMPOSER_SURFACE_TAG = "ds-composer-surface"

object DsComposerMetrics {
    val actionVisualSize = 32.dp
    val primaryActionVisualSize = 34.dp
    val actionIconSize = 18.dp
}

/** Shared conversation composer shell for local Chat, local Work and remote conversations. */
@Composable
fun DsComposerSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    Surface(
        modifier = modifier
            .testTag(DS_COMPOSER_SURFACE_TAG)
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)
            .animateContentSize(),
        shape = DsShapes.composer,
        color = colors.wallpaperSurface(
            WallpaperSurfaceLevel.INPUT,
            BackgroundRegion.BOTTOM,
            colors.composerCard,
        ),
        border = BorderStroke(1.dp, colors.borderL1),
        shadowElevation = 1.dp,
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
 * Composer-only action: compact visual size with a full 48dp touch target.
 * Visual chrome can shrink without weakening touch or accessibility behavior.
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
        enabled = enabled,
        modifier = modifier
            .size(DsSpacing.touchTarget)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(visualSize),
                shape = CircleShape,
                color = resolvedContainer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
}
