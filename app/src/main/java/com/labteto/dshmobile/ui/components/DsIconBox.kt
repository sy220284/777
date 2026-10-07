package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsTheme

/**
 * Compatibility categories retained for existing call sites. Kimi parity intentionally keeps
 * navigation/tool glyphs monochrome; semantic status is expressed by text/dots instead of icon hue.
 */
enum class DsIconFamily {
    Accent,
    Purple,
    Cyan,
    Amber,
    Green,
    Neutral,
}

private data class DsIconFamilyColors(val container: Color, val content: Color)

@Composable
private fun colorsFor(family: DsIconFamily): DsIconFamilyColors {
    val c = DsTheme.colors
    val content = when (family) {
        DsIconFamily.Accent -> c.labelPrimary
        else -> c.labelSecondary
    }
    return DsIconFamilyColors(Color.Transparent, content)
}

/**
 * 30dp alignment box around a 17dp monochrome outlined icon.
 *
 * [active] is reserved for a genuinely live operation. Only the glyph breathes; container geometry
 * stays still, which keeps dense process lists readable and avoids relayout work.
 */
@Composable
fun DsIconBox(
    icon: ImageVector,
    family: DsIconFamily = DsIconFamily.Neutral,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    active: Boolean = false,
) {
    val familyColors = colorsFor(family)
    val iconAlpha = if (active) {
        val transition = rememberInfiniteTransition(label = "activeIcon")
        val pulse by transition.animateFloat(
            initialValue = 0.48f,
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
        modifier = modifier
            .size(30.dp)
            .background(familyColors.container, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = familyColors.content,
            modifier = Modifier
                .size(17.dp)
                .graphicsLayer { alpha = iconAlpha },
        )
    }
}
