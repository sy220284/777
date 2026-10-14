package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.labteto.dshmobile.ui.shenyan.LocalSyReducedMotion
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.components.SyMaterialFrame
import com.shenyan.designsystem.components.SyMaterialTier
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.foundation.SyMotion
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.rememberHapticPulse
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** Design 02: five navigable areas, not a progress estimate or five fake execution stages. */
@Composable
internal fun FluidSectionNavigation(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    counts: List<String> = emptyList(),
    numbered: Boolean = false,
    enabled: Boolean = true,
    attention: Int? = null,
    tag: String = "fluidSection",
) {
        val sy = LocalSyColors.current
    val pulse = rememberHapticPulse()
    val reduced = LocalSyReducedMotion.current
    val duration = if (reduced) 0 else SyMotion.selectMs
    val tabs: @Composable () -> Unit = {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(if (numbered) 5.dp else 2.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val fill by animateColorAsState(
                if (active) sy.selected
                else if (numbered) sy.materialL1
                else androidx.compose.ui.graphics.Color.Transparent,
                tween(duration), label = "sectionFill",
            )
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("$tag:$index")
                    .selectable(active, enabled = enabled, role = Role.Tab, onClick = {
                        if (!active) pulse()
                        onSelect(index)
                    }),
                shape = RoundedCornerShape(SyMetric.radiusControl),
                color = fill,
                border = if (numbered) BorderStroke(1.dp, when {
                    active -> sy.primary
                    attention == index -> sy.warningInk
                    else -> sy.border
                }) else null,
            ) {
                Column(Modifier.padding(horizontal = 7.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    horizontalAlignment = if (numbered) Alignment.Start else Alignment.CenterHorizontally) {
                    if (numbered) Text((index + 1).toString(), style = DsType.caption11,
                        color = sy.textTertiary)
                    Text(label, style = DsType.small13Strong.withReadingWeight(),
                        textAlign = if (numbered) TextAlign.Start else TextAlign.Center,
                        color = if (active) sy.text else sy.textSecondary)
                    counts.getOrNull(index)?.let { Text(it, style = DsType.caption11,
                        color = sy.textSecondary) }
                }
            }
        }
    }
    }
    if (numbered) Column(modifier) { tabs() } else SyMaterialFrame(
        modifier = modifier.fillMaxWidth(), tier = SyMaterialTier.L2_BAR,
        shape = RoundedCornerShape(SyMetric.radiusControl),
        horizontalPadding = 2.dp, verticalPadding = 2.dp,
        verticalArrangement = Arrangement.Top,
    ) { tabs() }
}

/** Swap preserves section identity and the 380 ms peer motion.
 * Work can opt into a shared transition scope without forcing other callers to pay its cost.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun FluidSectionSwap(
    section: Int,
    modifier: Modifier = Modifier,
    enableSharedBounds: Boolean = false,
    content: @Composable (Int) -> Unit,
) {
    val offset = with(LocalDensity.current) { SyMetric.navigationPeerOffset.roundToPx() }
    val reduceMotion = LocalSyReducedMotion.current
    val host: @Composable (Modifier, SharedTransitionScope?) -> Unit = { surfaceModifier, sharedScope ->
        AnimatedContent(targetState = section, modifier = surfaceModifier, label = "fluidSectionSwap",
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
                } else {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(DsAnimations.featurePagePeer) { direction * offset } + fadeIn(tween(SyMotion.pagePeerMs)))
                        .togetherWith(slideOutHorizontally(DsAnimations.featurePagePeer) { -direction * offset } + fadeOut(tween(SyMotion.pagePeerMs)))
                }
            },
        ) { selectedSection ->
            if (sharedScope != null) {
                CompositionLocalProvider(
                    LocalFluidSectionSharedScope provides sharedScope,
                    LocalFluidSectionVisibilityScope provides this,
                ) { content(selectedSection) }
            } else {
                content(selectedSection)
            }
        }
    }
    if (enableSharedBounds && !reduceMotion) {
        SharedTransitionLayout(modifier = modifier) {
            host(Modifier.fillMaxSize(), this)
        }
    } else {
        host(modifier, null)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val LocalFluidSectionSharedScope =
    staticCompositionLocalOf<SharedTransitionScope?> { null }
private val LocalFluidSectionVisibilityScope =
    staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Only same-session completed work may morph from its goal/progress card to outputs. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.localWorkCompletionSharedBounds(key: String?): Modifier {
    if (key == null || LocalSyReducedMotion.current) return this
    val shared = LocalFluidSectionSharedScope.current ?: return this
    val visibility = LocalFluidSectionVisibilityScope.current ?: return this
    return with(shared) {
        this@localWorkCompletionSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> tween(SyMotion.morphMs) },
        )
    }
}
