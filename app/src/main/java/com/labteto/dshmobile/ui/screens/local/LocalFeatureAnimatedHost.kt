package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.labteto.dshmobile.ui.theme.DsAnimations

/**
 * Owns the spatial grammar for the local feature stack.
 *
 * Kimi 3.1.3 uses a restrained 180ms view transition: small hierarchy displacement, fast
 * opacity settlement, and peer pages that cross-fade without artificial travel.
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
    val settledBackProgress = predictiveBackProgress.coerceIn(0f, 1f)

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
                        slideInHorizontally(DsAnimations.featurePageForward) { width -> width / 16 } +
                            fadeIn(DsAnimations.featurePageEnterFade)
                        ).togetherWith(
                        slideOutHorizontally(DsAnimations.featurePageForward) { width -> -width / 32 } +
                            fadeOut(DsAnimations.featurePageExitFade),
                    )
                }

                targetState.size < initialState.size -> {
                    (
                        slideInHorizontally(DsAnimations.featurePageBackward) { width -> -width / 32 } +
                            fadeIn(DsAnimations.featurePageEnterFade)
                        ).togetherWith(
                        slideOutHorizontally(DsAnimations.featurePageBackward) { width -> width / 16 } +
                            fadeOut(DsAnimations.featurePageExitFade),
                    )
                }

                else -> {
                    (
                        slideInHorizontally(DsAnimations.featurePagePeer) { 0 } +
                            fadeIn(DsAnimations.featurePageEnterFade)
                        ).togetherWith(
                        slideOutHorizontally(DsAnimations.featurePagePeer) { 0 } +
                            fadeOut(DsAnimations.featurePageExitFade),
                    )
                }
            }
        },
        label = "localFeaturePage",
    ) { renderedStack ->
        content(localFeatureCurrent(renderedStack))
    }
}
