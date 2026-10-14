package com.labteto.dshmobile.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
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
import com.shenyan.designsystem.foundation.SyMotion
import com.shenyan.designsystem.foundation.SyMetric

/**
 * The app's motion vocabulary. Screens reach for these instead of inlining `tween(…)` so that
 * everything expanding, swapping, or settling shares one feel — and so a timing can be retuned in
 * one place rather than hunted across twenty call sites.
 *
 * The house rule: motion explains a change, it does not decorate one. Continuous motion is reserved
 * for genuine loading or a genuinely live running state; completed and failed states settle immediately.
 */
/** Specific animation specs retained for screen-level semantics; shared timings come from V6 SyMotion. */
object DsAnimations {
    private val fluidEase = SyMotion.ease
    val modal: FiniteAnimationSpec<Float> = tween(SyMotion.dialogMs, easing = fluidEase)
    val progress: FiniteAnimationSpec<Float> = tween(SyMotion.progressMs, easing = fluidEase)
    val pushOffset: Dp = SyMetric.navigationPushOffset
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
    val pressScale: AnimationSpec<Float> = tween(SyMotion.pressMs, easing = fluidEase)

    /**
     * Disclosure and dock expansion. Deliberately springy-but-unbouncy: content growing downward
     * with an overshoot reads as a glitch, and a linear tween reads as mechanical.
     */
    val expand: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /**
     * Composer single-row ↔ two-row reveal. Short and unbouncy so it follows the platform IME
     * instead of finishing with a delayed spring settle.
     */
    val composerReveal: FiniteAnimationSpec<IntSize> = tween(SyMotion.composerRevealMs, easing = FastOutSlowInEasing)

    /** Controls fade slightly faster than the geometry changes. */
    val composerFade: FiniteAnimationSpec<Float> = tween(SyMotion.composerFadeMs, easing = FastOutSlowInEasing)

    /** Chevron rotation on a disclosure row. */
    val chevron: AnimationSpec<Float> = tween(SyMotion.chevronMs, easing = FastOutSlowInEasing)

    /** Tab and content swaps. Short — a long swap on a large list costs a full relayout. */
    val tabSwap: FiniteAnimationSpec<Float> = tween(SyMotion.sheetMs, easing = fluidEase)

    /** Reference component spec: glass tab thumb and 18dp underline settle in 200ms. */
    val tabBackground: FiniteAnimationSpec<Color> = tween(SyMotion.selectMs, easing = fluidEase)
    val tabIndicator: FiniteAnimationSpec<Dp> = tween(SyMotion.selectMs, easing = fluidEase)

    /** Reference list entry: 360ms reveal; 80ms between items, capped at ten cards. */
    val cascade: FiniteAnimationSpec<Float> = tween(SyMotion.pushMs, easing = fluidEase)
    const val cascadeStaggerMs = 80L
    const val cascadeMaxItems = 10

    /** Sliding indicator used by compact segmented controls. It settles instead of gliding mechanically. */
    val segmentSlide: FiniteAnimationSpec<Dp> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Subtle surface feedback for selectable rows and tabs. */
    val interactionColor: FiniteAnimationSpec<Color> = tween(SyMotion.interactionColorMs, easing = fluidEase)

    /** Item placement inside a lazy list when rows are inserted, removed, or reordered. */
    val listItem: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Generic opacity change. */
    val fade: FiniteAnimationSpec<Float> = tween(fadeDuration, easing = FastOutSlowInEasing)

    /** Panel slide-in, faster than the platform default so it keeps up with a drag. */
    val panelSlide: FiniteAnimationSpec<IntOffset> = tween(SyMotion.sheetMs, easing = fluidEase)

    /** Full-page hierarchy transition. Short enough to preserve direct manipulation on a phone. */
    val pageSlide: FiniteAnimationSpec<IntOffset> = tween(SyMotion.pushMs, easing = fluidEase)

    /** Page opacity settles slightly before geometry so navigation never feels floaty. */
    val pageFade: FiniteAnimationSpec<Float> = tween(SyMotion.interactionColorMs, easing = FastOutSlowInEasing)

    /**
     * Feature hierarchy push. Kept separate from [pageSlide] because overlays still need the
     * slightly broader generic page motion while navigation pages use a calmer spatial grammar.
     */
    val featurePageForward: FiniteAnimationSpec<IntOffset> =
        tween(SyMotion.pushMs, easing = fluidEase)

    /** Feature hierarchy pop mirrors push but settles a touch faster. */
    val featurePageBackward: FiniteAnimationSpec<IntOffset> =
        tween(SyMotion.sheetMs, easing = fluidEase)

    /** Switching between drawer peers is mostly a cross-fade with only a tiny positional cue. */
    val featurePagePeer: FiniteAnimationSpec<IntOffset> =
        tween(SyMotion.pagePeerMs, easing = fluidEase)

    /** Incoming page opacity: finishes before the larger geometry motion. */
    val featurePageEnterFade: FiniteAnimationSpec<Float> =
        tween(SyMotion.pageEnterFadeMs, easing = FastOutSlowInEasing)

    /** Outgoing page opacity leaves first so navigation feels crisp instead of floaty. */
    val featurePageExitFade: FiniteAnimationSpec<Float> =
        tween(SyMotion.pageExitFadeMs, easing = FastOutSlowInEasing)

    /** Cancelled predictive-back gestures return without overshoot. */
    val predictiveBackSettle: FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Maximum direct-manipulation offset while a predictive-back gesture is in progress. */
    val predictiveBackMaxOffset: Dp = 32.dp

    /** One half of the restrained breathing loop used only by live loading/running semantics. */
    val semanticPulse = tween<Float>(SyMotion.livePulseMs, easing = FastOutSlowInEasing)

    /** Scale animation duration for press effects */
    const val scaleDuration = SyMotion.pressMs

    /** Fade animation duration for opacity changes */
    const val fadeDuration = SyMotion.fadeMs

    /** Standard transition duration for layout changes */
    const val transitionDuration = SyMotion.toolExpandMs

    /** Scale values for interactive elements */
    object Scale {
        const val normal = 1f
        const val pressed = 0.975f
    }
}
