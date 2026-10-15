package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
    val peerOffset = with(LocalDensity.current) {
        com.shenyan.designsystem.foundation.SyMetric.navigationPeerOffset.roundToPx()
    }
    val settledBackProgress = predictiveBackProgress.coerceIn(0f, 1f)
    val reduced = LocalSyReducedMotion.current
    val ease = com.shenyan.designsystem.foundation.SyMotion.ease
    val motion = com.shenyan.designsystem.foundation.SyMotion
    val tempo = com.shenyan.designsystem.foundation.LocalSyMotionTempo.current
    val push = tween<IntOffset>(motion.duration(motion.pushMs, reduced, tempo), easing = ease)
    val pop = tween<IntOffset>(motion.duration(motion.pagePopMs, reduced, tempo), easing = ease)
    val peer = tween<IntOffset>(motion.duration(motion.pagePeerMs, reduced, tempo), easing = ease)
    val vertical = tween<IntOffset>(motion.duration(motion.pushMs, reduced, tempo), easing = ease)
    val pageScale = tween<Float>(motion.duration(motion.pushMs, reduced, tempo), easing = ease)
    val rise = with(LocalDensity.current) { 14.dp.roundToPx() }
    val fadeEnter = tween<Float>(motion.duration(motion.pageEnterFadeMs, reduced, tempo),
        delayMillis = motion.duration(motion.sidebarContentDelayMs, reduced, tempo), easing = ease)
    val fadeExit = tween<Float>(motion.duration(motion.pageExitFadeMs, reduced, tempo), easing = ease)

    SharedTransitionLayout(modifier = modifier) {
        val sharedScope = this
        AnimatedContent(
        targetState = stack,
        contentKey = { localFeatureCurrent(it) },
        modifier = Modifier.fillMaxSize().graphicsLayer {
            translationX = predictiveBackMaxOffsetPx * settledBackProgress
            alpha = 1f - (0.02f * settledBackProgress)
        },
        transitionSpec = {
            // Each product area has a recognizable but quiet entrance. Leaving content
            // stays stable: no full-screen bouncing or overlapping long transitions.
            val nextPage = localFeatureCurrent(targetState)
            when {
                reduced -> fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
                targetState.size < initialState.size -> {
                    (slideInHorizontally(pop) { -pushOffset / 2 } + fadeIn(fadeEnter))
                        .togetherWith(slideOutHorizontally(pop) { pushOffset } + fadeOut(fadeExit))
                }
                targetState.size > initialState.size -> {
                    val enter = when (nextPage) {
                        LocalFeaturePage.PERSONA_GALLERY ->
                            slideInVertically(vertical) { rise } + fadeIn(fadeEnter)
                        LocalFeaturePage.DIARY ->
                            slideInVertically(vertical) { -rise / 2 } + fadeIn(fadeEnter)
                        LocalFeaturePage.WORKSPACE ->
                            scaleIn(initialScale = .985f, animationSpec = pageScale) + fadeIn(fadeEnter)
                        LocalFeaturePage.TASKS ->
                            slideInVertically(vertical) { rise / 2 } + fadeIn(fadeEnter)
                        LocalFeaturePage.TOOLS ->
                            scaleIn(initialScale = .975f, animationSpec = pageScale) + fadeIn(fadeEnter)
                        LocalFeaturePage.SETTINGS, LocalFeaturePage.HOME ->
                            slideInHorizontally(push) { pushOffset } + fadeIn(fadeEnter)
                    }
                    enter.togetherWith(fadeOut(fadeExit))
                }
                else -> {
                    (slideInHorizontally(peer) { peerOffset } + fadeIn(fadeEnter))
                        .togetherWith(slideOutHorizontally(peer) { -peerOffset } + fadeOut(fadeExit))
                }
            }
        },
        label = "localFeaturePage",
    ) { renderedStack ->
            CompositionLocalProvider(
                LocalFeatureSharedTransitionScope provides sharedScope,
                LocalFeatureVisibilityScope provides this,
            ) {
                content(localFeatureCurrent(renderedStack))
            }
        }
    }
}

/** Shared layout/visibility scopes are owned by this single navigation host. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalFeatureSharedTransitionScope =
    staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalFeatureVisibilityScope =
    staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Path-qualified bounds key avoids morphing two unrelated workspace files. */
internal fun localArtifactTransitionKey(workspace: String, file: String): String =
    "workspace-file:$workspace:$file"

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.localArtifactSharedBounds(workspace: String, file: String): Modifier {
    val scope = LocalFeatureSharedTransitionScope.current ?: return this
    val visibility = LocalFeatureVisibilityScope.current ?: return this
    val motion = com.shenyan.designsystem.foundation.SyMotion
    val reduced = LocalSyReducedMotion.current
    val tempo = com.shenyan.designsystem.foundation.LocalSyMotionTempo.current
    if (reduced || file.isBlank()) return this
    return with(scope) {
        this@localArtifactSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(localArtifactTransitionKey(workspace, file)),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> tween(motion.duration(motion.morphMs, reduced, tempo)) },
        )
    }
}
