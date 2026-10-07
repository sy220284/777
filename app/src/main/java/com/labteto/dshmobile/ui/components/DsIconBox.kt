package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * Compatibility labels kept for callers that already classify a capability.
 *
 * Kimi's navigation language is monochrome: families no longer paint colored containers.
 * Accent/semantic color is reserved for selected or live state elsewhere in the row.
 */
enum class DsIconFamily {
    Accent,
    Purple,
    Cyan,
    Amber,
    Green,
    Neutral,
}

/** Compact monochrome 24-grid glyph holder used by grouped rows. */
@Composable
fun DsIconBox(
    icon: ImageVector,
    family: DsIconFamily = DsIconFamily.Neutral,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    active: Boolean = false,
) {
    val colors = DsTheme.colors
    val iconAlpha = if (active) {
        val transition = rememberInfiniteTransition(label = "activeIcon")
        val pulse by transition.animateFloat(
            initialValue = 0.52f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = DsAnimations.semanticPulse,
                repeatMode = RepeatMode.Reverse,
            ),
            label = "activeIconPulse",
        )
        pulse
    } else {
        1f
    }
    Box(
        modifier = modifier.size(30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.labelSecondary,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { alpha = iconAlpha },
        )
    }
}
