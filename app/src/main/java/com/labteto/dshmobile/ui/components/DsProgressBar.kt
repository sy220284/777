package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsTheme

/** Explicit visual states from the reference; never infer success from a number alone. */
enum class DsProgressStatus { Idle, Running, Done, Failed }

/** Reference: 7dp/5dp track; running amber, done mint, failed clay (not destructive red). */
@Composable
fun DsProgressBar(
    value: Float,
    modifier: Modifier = Modifier,
    color: Color = DsTheme.colors.accent,
    status: DsProgressStatus = DsProgressStatus.Running,
) {
    val colors = DsTheme.colors
    val bounded = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    val target = if (status == DsProgressStatus.Idle) 0f else bounded
    val width by animateFloatAsState(
        targetValue = target,
        animationSpec = if (status == DsProgressStatus.Failed) snap() else DsAnimations.progress,
        label = "designProgressWidth",
    )
    val brush = when (status) {
        DsProgressStatus.Running -> Brush.horizontalGradient(
            listOf(color, if (color == colors.accent) colors.brandPrimary else color.copy(alpha = .85f)))
        DsProgressStatus.Done -> Brush.horizontalGradient(listOf(colors.successSecondary, colors.success))
        DsProgressStatus.Failed -> Brush.horizontalGradient(
            listOf(Color(0xFFC98B6B), Color(0xFF9C6247)))
        DsProgressStatus.Idle -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    Box(
        modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(5.dp))
            .background(colors.borderL2)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(width).background(brush))
    }
}
