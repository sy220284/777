package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DshTheme

/** Semantic execution states shared by chat, work and utility surfaces. */
enum class StateDotState { Idle, Running, Done, Warning, Error }

/**
 * Clear Realm status dot.
 *
 * Only a genuinely live [Running] state keeps moving, using a restrained opacity breath.
 * Terminal states settle immediately so old history never competes with the current task.
 */
@Composable
fun StateDot(
    state: StateDotState,
    size: Dp = 8.dp,
) {
    val color = when (state) {
        StateDotState.Idle -> DsTheme.colors.labelCaption
        StateDotState.Running -> DsTheme.colors.accent
        StateDotState.Done -> DsTheme.colors.success
        StateDotState.Warning -> DsTheme.colors.warn
        StateDotState.Error -> DsTheme.colors.error
    }
    val alpha = if (state == StateDotState.Running) {
        val transition = rememberInfiniteTransition(label = "stateDot")
        val pulse by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        pulse
    } else {
        1f
    }

    Canvas(modifier = Modifier.size(size)) {
        drawHalo(color, alpha)
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = this.size.minDimension * 0.34f,
        )
    }
}

private fun DrawScope.drawHalo(color: Color, alpha: Float) {
    drawCircle(
        color.copy(alpha = 0.10f * alpha),
        radius = this.size.minDimension * 0.58f,
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun StateDotPreview() {
    DshTheme {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StateDot(StateDotState.Idle)
            StateDot(StateDotState.Running, size = 10.dp)
            StateDot(StateDotState.Done)
            StateDot(StateDotState.Warning)
            StateDot(StateDotState.Error)
        }
    }
}
