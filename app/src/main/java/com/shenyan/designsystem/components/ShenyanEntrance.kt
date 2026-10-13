package com.shenyan.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.LocalSyReduceMotion
import com.shenyan.designsystem.foundation.SyMotion
import kotlinx.coroutines.delay

/** Bounded first-entrance cascade; content stays present and interactive during the reveal. */
@Composable
fun SyEntranceCascade(visible: Boolean, index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalSyReduceMotion.current
    val animate = visible && !reduced && index in 0 until 10
    var appeared by remember(visible, reduced) { mutableStateOf(!animate) }
    LaunchedEffect(visible, reduced) {
        if (animate) delay(index * 80L)
        appeared = true
    }
    val reveal by animateFloatAsState(if (appeared) 1f else 0f,
        tween(if (reduced) 0 else SyMotion.pushMs), label = "entryCascade")
    val offset = with(LocalDensity.current) { 8.dp.toPx() }
    Box(modifier.graphicsLayer {
        alpha = .55f + .45f * reveal
        translationY = (1f - reveal) * offset
    }) { content() }
}
