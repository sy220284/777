package com.labteto.dshmobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * Icon button with guaranteed 48dp touch target for accessibility.
 * Shows visual feedback on hover and press.
 *
 * The glyph is centred in its own [Box] and sized with `requiredSize`, not `size`: Material's
 * `Surface` lays its content out with `propagateMinConstraints = true`, so an `Icon` placed
 * directly inside it inherits a 48dp *minimum* and an ordinary `size` modifier is clamped straight
 * back up to the touch target. That is what made every icon in the app render at 48dp.
 */
@Composable
fun DsIconButton(
    icon: ImageVector?,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = DsTheme.colors.labelSecondary,
    iconSize: Dp = 20.dp,
    containerColor: Color = Color.Transparent,
    shadowElevation: Dp = 0.dp,
    selected: Boolean = false,
    iconPainter: Painter? = null,
) {
    val colors = DsTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) DsAnimations.Scale.pressed else DsAnimations.Scale.normal,
        animationSpec = DsAnimations.pressScale,
        label = "iconButtonScale"
    )
    
    val restingBackground = if (selected) colors.accentTertiary else containerColor
    val resolvedTint = if (selected) colors.accent else tint
    val targetBackground = when {
        !enabled -> Color.Transparent
        isPressed -> colors.hover.compositeOver(restingBackground)
        isHovered -> colors.hover.copy(alpha = 0.5f).compositeOver(restingBackground)
        else -> restingBackground
    }
    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = DsAnimations.interactionColor,
        label = "iconButtonBackground",
    )
    
    Surface(
        onClick = onClick,
        modifier = modifier.size(DsSpacing.touchTarget),
        enabled = enabled,
        color = background,
        shape = androidx.compose.foundation.shape.CircleShape,
        interactionSource = interactionSource,
        shadowElevation = shadowElevation,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val iconModifier = Modifier
                .requiredSize(iconSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
            when {
                iconPainter != null -> Icon(
                    painter = iconPainter,
                    contentDescription = contentDescription,
                    tint = if (enabled) resolvedTint else resolvedTint.copy(alpha = 0.4f),
                    modifier = iconModifier,
                )
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (enabled) resolvedTint else resolvedTint.copy(alpha = 0.4f),
                    modifier = iconModifier,
                )
            }
        }
    }
}
