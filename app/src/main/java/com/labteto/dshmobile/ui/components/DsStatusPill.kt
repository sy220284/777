package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Semantic state of an automation task / run. */
enum class DsStatus { Running, Done, Warning, Failed, Neutral }

/**
 * Kimi-style status label: the chip stays neutral and semantic color is confined to the 6dp dot.
 * Running owns the only motion; settled history is static.
 */
@Composable
fun DsStatusPill(
    state: DsStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val dot = when (state) {
        DsStatus.Running -> colors.accent
        DsStatus.Done -> colors.success
        DsStatus.Warning -> colors.warnLabel
        DsStatus.Failed -> colors.error
        DsStatus.Neutral -> colors.labelTertiary
    }
    Row(
        modifier = modifier
            .background(colors.bgModulePlatform, DsShapes.pillFull)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DsStatusDot(color = dot, running = state == DsStatus.Running)
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = DsType.caption11Strong.withReadingWeight(),
            color = colors.labelSecondary,
        )
    }
}

@Composable
private fun DsStatusDot(
    color: Color,
    running: Boolean,
) {
    val alpha = if (running) {
        val transition = rememberInfiniteTransition(label = "statusRunning")
        val pulse by transition.animateFloat(
            initialValue = 0.42f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = DsAnimations.semanticPulse,
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusRunningPulse",
        )
        pulse
    } else {
        1f
    }
    Box(
        Modifier
            .size(6.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape),
    )
}
