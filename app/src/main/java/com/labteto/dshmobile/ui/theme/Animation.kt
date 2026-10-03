package com.labteto.dshmobile.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The app's motion vocabulary. Screens reach for these instead of inlining `tween(…)` so that
 * everything expanding, swapping, or settling shares one feel — and so a timing can be retuned in
 * one place rather than hunted across twenty call sites.
 *
 * The house rule: motion explains a change, it does not decorate one. Nothing loops forever except
 * a loading skeleton, and only while something is genuinely loading.
 */
object DsAnimations {
    /** Quick and settled — direct manipulation should feel tactile without visible rebound. */
    val fastSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    /** The default for general transitions: settles without overshooting. */
    val normalSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Press feedback on buttons, pills and icon buttons. */
    val pressScale = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    /**
     * Disclosure and dock expansion. Deliberately springy-but-unbouncy: content growing downward
     * with an overshoot reads as a glitch, and a linear tween reads as mechanical.
     */
    val expand: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * Composer single-row ↔ two-row reveal. Short and unbouncy so it follows the platform IME
     * instead of finishing with a delayed spring settle.
     */
    val composerReveal: FiniteAnimationSpec<Int> = tween(220, easing = FastOutSlowInEasing)

    /** Controls fade slightly faster than the geometry changes. */
    val composerFade: FiniteAnimationSpec<Float> = tween(120, easing = FastOutSlowInEasing)

    /** Chevron rotation on a disclosure row. */
    val chevron: AnimationSpec<Float> = tween(160, easing = FastOutSlowInEasing)

    /** Tab and content swaps. Short — a long swap on a large list costs a full relayout. */
    val tabSwap: FiniteAnimationSpec<Float> = tween(150, easing = FastOutSlowInEasing)

    /** Sliding indicator used by compact segmented controls. It settles instead of gliding mechanically. */
    val segmentSlide: FiniteAnimationSpec<Dp> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Subtle surface feedback for selectable rows and tabs. */
    val interactionColor: FiniteAnimationSpec<Color> = tween(120, easing = FastOutSlowInEasing)

    /** Item placement inside a lazy list when rows are inserted, removed, or reordered. */
    val listItem: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Generic opacity change. */
    val fade: FiniteAnimationSpec<Float> = tween(fadeDuration, easing = FastOutSlowInEasing)

    /** Panel slide-in, faster than the platform default so it keeps up with a drag. */
    val panelSlide: FiniteAnimationSpec<IntOffset> = tween(200, easing = FastOutSlowInEasing)

    /** Scale animation duration for press effects */
    const val scaleDuration = 100

    /** Fade animation duration for opacity changes */
    const val fadeDuration = 150

    /** Standard transition duration for layout changes */
    const val transitionDuration = 200

    /** Scale values for interactive elements */
    object Scale {
        const val normal = 1f
        const val pressed = 0.975f
    }
}
