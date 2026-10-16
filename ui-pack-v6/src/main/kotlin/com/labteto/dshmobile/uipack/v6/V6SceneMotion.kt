package com.labteto.dshmobile.uipack.v6

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uihost.UiSceneNavigationState
import com.shenyan.designsystem.foundation.*

internal enum class V6NavigationMotion { FORWARD, BACK, PEER }

internal fun v6NavigationMotion(previousDepth: Int, nextDepth: Int): V6NavigationMotion = when {
    nextDepth > previousDepth -> V6NavigationMotion.FORWARD
    nextDepth < previousDepth -> V6NavigationMotion.BACK
    else -> V6NavigationMotion.PEER
}

/** Only the current renderer is alive: transitions cannot replay Owner effects or old actions. */
@Composable
internal fun V6SceneMotionFrame(state: UiSceneNavigationState, content: @Composable () -> Unit) {
    var previousDepth by remember { mutableIntStateOf(state.stack.size) }
    val direction = remember(state) { v6NavigationMotion(previousDepth, state.stack.size) }
    SideEffect { previousDepth = state.stack.size }
    val reduced = LocalSyReduceMotion.current
    val tempo = LocalSyMotionTempo.current
    val progress = remember(state) { Animatable(if (reduced) 1f else 0f) }
    val duration = when (direction) {
        V6NavigationMotion.FORWARD -> SyMotion.pushMs
        V6NavigationMotion.BACK -> SyMotion.pagePopMs
        V6NavigationMotion.PEER -> SyMotion.pagePeerMs
    }
    LaunchedEffect(state, reduced, tempo) {
        if (reduced) progress.snapTo(1f)
        else progress.animateTo(1f, tween(SyMotion.duration(duration, false, tempo), easing = SyMotion.ease))
    }
    val distance = with(LocalDensity.current) { if (direction == V6NavigationMotion.PEER) 10.dp.toPx() else 40.dp.toPx() }
    val sign = (if (direction == V6NavigationMotion.BACK) -1f else 1f) *
        (if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f)
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = if (reduced) 1f else progress.value
        translationX = if (reduced) 0f else sign * distance * (1f - progress.value)
    }) {
        key(state.stack.last()) { content() }
    }
}
