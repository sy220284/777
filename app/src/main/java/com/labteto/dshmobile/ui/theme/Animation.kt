package com.labteto.dshmobile.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Motion vocabulary aligned to Kimi 3.1.3.
 *
 * APK foundation truth:
 * micro 60ms, fast 120ms, normal 200ms, slow 300ms;
 * dialog 180/135ms, view 180ms, panel 240ms, list 180ms.
 */
object DsAnimations {
    private val easeOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    private val easeIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    private val standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    private val motionOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    private val panelEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    val fastSpring: FiniteAnimationSpec<Float> = tween(FAST_MS, easing = easeOut)
    val normalSpring: FiniteAnimationSpec<Float> = tween(NORMAL_MS, easing = standard)
    val pressScale: FiniteAnimationSpec<Float> = tween(FAST_MS, easing = standard)

    val expand: FiniteAnimationSpec<IntSize> = tween(VIEW_MS, easing = standard)
    val composerReveal: FiniteAnimationSpec<IntSize> = tween(VIEW_MS, easing = standard)
    val composerFade: FiniteAnimationSpec<Float> = tween(FAST_MS, easing = easeOut)
    val chevron: AnimationSpec<Float> = tween(FAST_MS, easing = standard)
    val tabSwap: FiniteAnimationSpec<Float> = tween(LIST_MS, easing = standard)
    val segmentSlide: FiniteAnimationSpec<Dp> = tween(LIST_MS, easing = standard)
    val interactionColor: FiniteAnimationSpec<Color> = tween(FAST_MS, easing = standard)
    val listItem: FiniteAnimationSpec<IntOffset> = tween(LIST_MS, easing = standard)
    val fade: FiniteAnimationSpec<Float> = tween(FAST_MS, easing = easeOut)

    val popover: FiniteAnimationSpec<Float> = tween(140, easing = motionOut)
    val dialogEnter: FiniteAnimationSpec<Float> = tween(180, easing = motionOut)
    val dialogExit: FiniteAnimationSpec<Float> = tween(135, easing = easeIn)
    val panelSlide: FiniteAnimationSpec<IntOffset> = tween(PANEL_MS, easing = panelEasing)
    val pageSlide: FiniteAnimationSpec<IntOffset> = tween(VIEW_MS, easing = standard)
    val pageFade: FiniteAnimationSpec<Float> = tween(FAST_MS, easing = easeOut)

    val featurePageForward: FiniteAnimationSpec<IntOffset> = tween(VIEW_MS, easing = standard)
    val featurePageBackward: FiniteAnimationSpec<IntOffset> = tween(VIEW_MS, easing = standard)
    val featurePagePeer: FiniteAnimationSpec<IntOffset> = tween(VIEW_MS, easing = standard)
    val featurePageEnterFade: FiniteAnimationSpec<Float> = tween(FAST_MS, easing = easeOut)
    val featurePageExitFade: FiniteAnimationSpec<Float> = tween(FAST_MS, easing = easeIn)

    val predictiveBackSettle: FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )
    val predictiveBackMaxOffset: Dp = 28.dp

    // Rive owns most Kimi live-tool motion. 777 keeps one restrained semantic pulse for native rows.
    val semanticPulse = tween<Float>(900, easing = standard)

    const val MICRO_MS = 60
    const val FAST_MS = 120
    const val NORMAL_MS = 200
    const val SLOW_MS = 300
    const val VIEW_MS = 180
    const val PANEL_MS = 240
    const val LIST_MS = 180

    const val scaleDuration = FAST_MS
    const val fadeDuration = FAST_MS
    const val transitionDuration = NORMAL_MS

    object Scale {
        const val normal = 1f
        const val pressed = 0.98f
    }
}
