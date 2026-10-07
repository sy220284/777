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
 * Screens provide the semantic page; this host alone decides how push/pop/peer swaps should move
 * so feature pages cannot drift into different transition directions or timings.
 *
 * Predictive back is intentionally a small direct-manipulation cue. It never claims the left-edge
 * product gesture, which is reserved for opening the drawer by [localFeatureProductBackAction].
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
                        slideInHorizontally(DsAnimations.featurePageForward) { width -> width / 9 } +
                            fadeIn(DsAnimations.featurePageEnterFade)
                        ).togetherWith(
                        slideOutHorizontally(DsAnimations.featurePageForward) { width -> -width / 18 } +
                            fadeOut(DsAnimations.featurePageExitFade),
                    )
                }

                targetState.size < initialState.size -> {
                    (
                        slideInHorizontally(DsAnimations.featurePageBackward) { width -> -width / 18 } +
                            fadeIn(DsAnimations.featurePageEnterFade)
                        ).togetherWith(
                        slideOutHorizontally(DsAnimations.featurePageBackward) { width -> width / 9 } +
                            fadeOut(DsAnimations.featurePageExitFade),
                    )
                }

                else -> {
                    (
                        slideInHorizontally(DsAnimations.featurePagePeer) { width -> width / 64 } +
                            fadeIn(DsAnimations.featurePageEnterFade)
                        ).togetherWith(
                        slideOutHorizontally(DsAnimations.featurePagePeer) { width -> -width / 96 } +
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
