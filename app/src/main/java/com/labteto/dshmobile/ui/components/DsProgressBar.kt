package com.labteto.dshmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import com.labteto.dshmobile.ui.theme.DsTheme

/** Supplied ProgressBar geometry: 7dp track, rounded ends and 340ms width changes. */
@Composable
fun DsProgressBar(value: Float, modifier: Modifier = Modifier,
    color: Color = DsTheme.colors.accent) {
    val bounded = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    val width by animateFloatAsState(bounded, tween(340), label = "designProgressWidth")
    Box(modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(5.dp))
        .background(DsTheme.colors.borderL2)
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(bounded, 0f..1f) }) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(width)
            .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.85f)))))
    }
}
