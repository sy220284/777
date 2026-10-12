package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import com.labteto.dshmobile.ui.shenyan.LocalSyReducedMotion
import com.labteto.dshmobile.ui.shenyan.SyDurations
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.labteto.dshmobile.ui.theme.DsAnimations

/**
 * Owns the spatial grammar for the local feature stack.
 *
 * Screens provide the semantic page; this host alone decides how push/pop/peer swaps should move
 * so feature pages cannot drift into different transition directions or timings.
 *
 * Predictive back is intentionally a small direct-manipulation cue. Both system edges are Back;
 * opening the drawer is a separate content-area gesture owned by ModalNavigationDrawer.
 */
@Composable
internal fun LocalFeatureAnimatedHost(
    stack: List<String>,
    predictiveBackProgress: Float = 0f,
    modifier: Modifier = Modifier,
    content: @Composable (LocalFeaturePage) -> Unit,
) {
    val predictiveBackMaxOffsetPx = with(LocalDensity.current) {
        DsAnimations.predictiveBackMaxOffset.toPx()
    }
    val pushOffset = with(LocalDensity.current) { DsAnimations.pushOffset.roundToPx() }
    val settledBackProgress = predictiveBackProgress.coerceIn(0f, 1f)
    val reduced = LocalSyReducedMotion.current
    val ease = CubicBezierEasing(.22f, 1f, .36f, 1f)
    val push = tween<IntOffset>(if (reduced) 80 else SyDurations.page, easing = ease)
    val pop = tween<IntOffset>(if (reduced) 80 else 320, easing = ease)
    val peer = tween<IntOffset>(if (reduced) 80 else 280, easing = ease)
    val fadeEnter = tween<Float>(if (reduced) 80 else 150,
        delayMillis = if (reduced) 0 else SyDurations.sidebarDelay, easing = ease)
    val fadeExit = tween<Float>(if (reduced) 80 else 110, easing = ease)

    AnimatedContent(
        targetState = stack,
        modifier = modifier.graphicsLayer {
            translationX = predictiveBackMaxOffsetPx * settledBackProgress
            alpha = 1f - (0.02f * settledBackProgress)
        },
        transitionSpec = {
            when {
                targetState.size > initialState.size -> {
                    (
                        slideInHorizontally(push) { pushOffset } +
                            fadeIn(fadeEnter)
                        ).togetherWith(
                        slideOutHorizontally(push) { -pushOffset / 2 } +
                            fadeOut(fadeExit),
                    )
                }

                targetState.size < initialState.size -> {
                    (
                        slideInHorizontally(pop) { -pushOffset / 2 } +
                            fadeIn(fadeEnter)
                        ).togetherWith(
                        slideOutHorizontally(pop) { pushOffset } +
                            fadeOut(fadeExit),
                    )
                }

                else -> {
                    (
                        slideInHorizontally(peer) { pushOffset / 4 } +
                            fadeIn(fadeEnter)
                        ).togetherWith(
                        slideOutHorizontally(peer) { -pushOffset / 4 } +
                            fadeOut(fadeExit),
                    )
                }
            }
        },
        label = "localFeaturePage",
    ) { renderedStack ->
        content(localFeatureCurrent(renderedStack))
    }
}
