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
 * Full-size status badge (运行中 / 已完成 / 失败).
 *
 * Running owns the only motion: a restrained breathing dot. Terminal states render fully static so
 * history remains calm even when a page contains many completed rows.
 */
@Composable
fun DsStatusPill(
    state: DsStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val (dot, tint) = when (state) {
        DsStatus.Running -> colors.accent to colors.accentTertiary
        DsStatus.Done -> colors.success to colors.successTertiary
        DsStatus.Warning -> colors.warnLabel to colors.warnTertiary
        DsStatus.Failed -> colors.error to colors.errorTertiary
        DsStatus.Neutral -> colors.labelTertiary to colors.hover
    }
    Row(
        modifier = modifier
            .background(tint, DsShapes.pillFull)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DsStatusDot(color = dot, running = state == DsStatus.Running)
        Spacer(Modifier.width(5.dp))
        Text(label, style = DsType.caption11Strong.withReadingWeight(), color = dot)
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
