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
 * Compatibility enum retained for existing feature call sites.
 *
 * Kimi 3.1.3 navigation/function icons use one monochrome language; family no longer paints a
 * colored tile behind the glyph. Semantic success/warning/error stays in status dots/text.
 */
enum class DsIconFamily {
    Accent,
    Purple,
    Cyan,
    Amber,
    Green,
    Neutral,
}

@Composable
fun DsIconBox(
    icon: ImageVector,
    family: DsIconFamily = DsIconFamily.Neutral,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    active: Boolean = false,
) {
    val colors = DsTheme.colors
    // Read [family] to keep source compatibility explicit while intentionally resolving every
    // navigation family to the same monochrome Kimi icon tint.
    val tint = when (family) {
        DsIconFamily.Accent,
        DsIconFamily.Purple,
        DsIconFamily.Cyan,
        DsIconFamily.Amber,
        DsIconFamily.Green,
        DsIconFamily.Neutral -> colors.labelPrimary
    }
    val iconAlpha = if (active) {
        val transition = rememberInfiniteTransition(label = "activeIcon")
        val pulse by transition.animateFloat(
            initialValue = 0.45f,
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
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { alpha = iconAlpha },
        )
    }
}
