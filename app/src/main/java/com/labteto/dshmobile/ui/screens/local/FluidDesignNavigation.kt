package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.rememberHapticPulse
import com.labteto.dshmobile.ui.shenyan.LocalSyReducedMotion
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.components.SyMaterialFrame
import com.shenyan.designsystem.components.SyMaterialTier
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric
import com.shenyan.designsystem.foundation.SyMotion

/** Shared navigation for real nested views (persona, media and files), not a Work stage meter. */
@Composable
internal fun FluidSectionNavigation(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String = "fluidSection",
) {
    val colors = LocalSyColors.current
    val pulse = rememberHapticPulse()
    val reduced = LocalSyReducedMotion.current
    val duration = if (reduced) 0 else SyMotion.selectMs
    SyMaterialFrame(
        modifier = modifier.fillMaxWidth(),
        tier = SyMaterialTier.L2_BAR,
        shape = RoundedCornerShape(SyMetric.radiusControl),
        horizontalPadding = 2.dp,
        verticalPadding = 2.dp,
        verticalArrangement = Arrangement.Top,
    ) {
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                val fill by animateColorAsState(
                    if (active) colors.selected else Color.Transparent,
                    tween(duration),
                    label = "sectionFill",
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
                ) {
                    Column(
                        Modifier.padding(horizontal = 7.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(label, style = DsType.small13Strong.withReadingWeight(),
                            textAlign = TextAlign.Center,
                            color = if (active) colors.text else colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** Other nested views keep their original peer transition and reduced-motion behavior. */
@Composable
internal fun FluidSectionSwap(
    section: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val offset = with(LocalDensity.current) { 60.dp.roundToPx() }
    val reduceMotion = LocalSyReducedMotion.current
    AnimatedContent(
        targetState = section,
        modifier = modifier,
        label = "fluidSectionSwap",
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(tween(80)).togetherWith(fadeOut(tween(80)))
            } else {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally(DsAnimations.featurePagePeer) { direction * offset } + fadeIn(tween(380)))
                    .togetherWith(slideOutHorizontally(DsAnimations.featurePagePeer) { -direction * offset } + fadeOut(tween(140)))
            }
        },
    ) { selectedSection ->
        content(selectedSection)
    }
}
