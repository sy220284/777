package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.ui.theme.DsAnimations

/**
 * Owns the spatial grammar for the local feature stack.
 *
 * Screens provide the semantic page; this host alone decides how push/pop should move so feature
 * pages cannot drift into different transition directions or timings.
 */
@Composable
internal fun LocalFeatureAnimatedHost(
    stack: List<LocalFeaturePage>,
    modifier: Modifier = Modifier,
    content: @Composable (LocalFeaturePage) -> Unit,
) {
    AnimatedContent(
        targetState = stack,
        modifier = modifier,
        transitionSpec = {
            val forward = targetState.size > initialState.size
            if (forward) {
                (
                    slideInHorizontally(DsAnimations.pageSlide) { width -> width / 5 } +
                        fadeIn(DsAnimations.pageFade)
                    ).togetherWith(
                    slideOutHorizontally(DsAnimations.pageSlide) { width -> -width / 8 } +
                        fadeOut(DsAnimations.pageFade),
                )
            } else {
                (
                    slideInHorizontally(DsAnimations.pageSlide) { width -> -width / 8 } +
                        fadeIn(DsAnimations.pageFade)
                    ).togetherWith(
                    slideOutHorizontally(DsAnimations.pageSlide) { width -> width / 5 } +
                        fadeOut(DsAnimations.pageFade),
                )
            }
        },
        label = "localFeaturePage",
    ) { renderedStack ->
        content(localFeatureCurrent(renderedStack))
    }
}
