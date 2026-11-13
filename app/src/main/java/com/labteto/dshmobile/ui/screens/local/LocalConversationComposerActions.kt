package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.labteto.dshmobile.ui.theme.DsAnimations

/** Keeps one context-sensitive send/stop button pinned to the right. */
@Composable
internal fun LocalConversationComposerExpandedRow(
    visible: Boolean,
    menuControl: @Composable () -> Unit,
    modelControl: @Composable () -> Unit,
    replySuggestionsControl: @Composable () -> Unit,
    planControl: @Composable () -> Unit,
    capabilityControls: @Composable (showLabels: Boolean) -> Unit,
    primaryAction: @Composable () -> Unit,
) {
    val motion = com.shenyan.designsystem.foundation.SyMotion
    val reduced = com.shenyan.designsystem.foundation.LocalSyReduceMotion.current
    val tempo = com.shenyan.designsystem.foundation.LocalSyMotionTempo.current
    val reveal = tween<IntSize>(motion.duration(motion.composerRevealMs, reduced, tempo), easing = motion.ease)
    val fade = tween<Float>(motion.duration(motion.composerFadeMs, reduced, tempo), easing = motion.ease)
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = reveal,
            expandFrom = Alignment.Top,
        ) + fadeIn(fade),
        exit = shrinkVertically(
            animationSpec = reveal,
            shrinkTowards = Alignment.Top,
        ) + fadeOut(fade),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
                ) {
                    menuControl()
                    modelControl()
                    capabilityControls(false)
                    planControl()
                    replySuggestionsControl()
                }
                primaryAction()
            }
        }
    }
}
