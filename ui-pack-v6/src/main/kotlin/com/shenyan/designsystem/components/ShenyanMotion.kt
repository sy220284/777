package com.shenyan.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.shenyan.designsystem.foundation.*

/** Finite entrance, keyed by real content identity, never by streaming text/revision. */
@Composable
fun SyEntrance(
    identity: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    order: Int = 0,
    durationMs: Int = SyMotion.entranceMs,
    content: @Composable () -> Unit,
) {
    val reduced = LocalSyReduceMotion.current
    val tempo = LocalSyMotionTempo.current
    var appeared by rememberSaveable(identity) { mutableStateOf(!enabled) }
    val progress = remember(identity) { Animatable(if (appeared || reduced) 1f else 0f) }
    LaunchedEffect(identity, reduced, tempo) {
        if (reduced) progress.snapTo(1f)
        else if (progress.value < 1f) {
            // Only the opening group is staggered; long lists never accumulate delay.
            progress.animateTo(1f, tween(SyMotion.duration(durationMs, false, tempo),
                delayMillis = SyMotion.duration(SyMotion.cascadeStaggerMs * order.coerceIn(0, 4), false, tempo),
                easing = SyMotion.ease))
        }
        appeared = true
    }
    val distance = with(LocalDensity.current) { 12.dp.toPx() }
    // Position-only offset preserves the visual rise, and makes the actual motion
    // observable to Android accessibility/Compose semantics (graphicsLayer
    // translation alone can keep stale boundsInRoot on some Compose versions).
    // Offset wraps the caller modifier so its semantics/testTag bounds follow
    // the actual moving child, including reduced-motion transitions.
    Box(Modifier.offset {
        IntOffset(0, if (reduced) 0 else (distance * (1f - progress.value)).roundToInt())
    }.then(modifier).graphicsLayer {
        alpha = if (reduced) 1f else progress.value
    }) { content() }
}

/** Local disclosure/attachment geometry. No whole-composer or keyboard size animation. */
@Composable
fun SyAnimatedReveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalSyReduceMotion.current
    val tempo = LocalSyMotionTempo.current
    val duration = SyMotion.duration(SyMotion.toolExpandMs, reduced, tempo)
    AnimatedVisibility(visible, modifier,
        enter = expandVertically(tween(duration, easing = SyMotion.ease)) + fadeIn(tween(duration)),
        exit = shrinkVertically(tween(duration, easing = SyMotion.ease)) + fadeOut(tween(duration))) {
        content()
    }
}

/** A true supplied value, with finite entry and smooth updates; never invented progress. */
@Composable
fun SyAnimatedBar(value: Float, modifier: Modifier = Modifier) {
    val reduced = LocalSyReduceMotion.current
    val tempo = LocalSyMotionTempo.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(if (entered || reduced) value else 0f,
        animationSpec = tween(SyMotion.duration(SyMotion.chartRevealMs, reduced, tempo), easing = SyMotion.ease),
        label = "syDataBar")
    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth())
}
