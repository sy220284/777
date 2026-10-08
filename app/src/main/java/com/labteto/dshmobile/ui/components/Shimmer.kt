package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Placeholder plate for content that has not arrived yet.
 *
 * Paints a placeholder surface and animates a glare band while content is loading.
 */
fun Modifier.skeleton(
    base: Color,
    highlight: Color,
    shape: Shape = RoundedCornerShape(8.dp),
    bandWidth: Dp = 220.dp,
    duration: Int = 1400,
): Modifier = composed {
    val density = LocalDensity.current
    val bandPx = with(density) { bandWidth.toPx() }
    val transition = rememberInfiniteTransition(label = "skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Restart),
        label = "skeletonProgress",
    )
    val band = Brush.horizontalGradient(
        0f to Color.Transparent,
        0.5f to highlight,
        1f to Color.Transparent,
    )
    clip(shape).drawBehind {
        drawRect(color = base)
        val sweep = size.width + bandPx
        drawRect(
            brush = band,
            topLeft = Offset(-bandPx + progress * sweep, 0f),
            size = Size(bandPx, size.height),
        )
    }
}
