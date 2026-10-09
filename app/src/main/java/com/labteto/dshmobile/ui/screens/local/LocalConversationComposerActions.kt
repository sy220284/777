package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing

/** Keeps send/stop pinned to the right on narrow devices without clipping capability controls. */
@Composable
internal fun LocalConversationComposerExpandedRow(
    visible: Boolean,
    state: LocalConversationSurfaceState,
    menuControl: @Composable () -> Unit,
    replySuggestionsControl: @Composable () -> Unit,
    planControl: @Composable () -> Unit,
    capabilityControls: @Composable (showLabels: Boolean) -> Unit,
    stopControl: @Composable () -> Unit,
    sendControl: @Composable (queue: Boolean) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = DsAnimations.composerReveal,
            expandFrom = Alignment.Top,
        ) + fadeIn(DsAnimations.composerFade),
        exit = shrinkVertically(
            animationSpec = DsAnimations.composerReveal,
            shrinkTowards = Alignment.Top,
        ) + fadeOut(DsAnimations.composerFade),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val showLabels = maxWidth >= 290.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    menuControl()
                    capabilityControls(showLabels)
                    planControl()
                    replySuggestionsControl()
                }
                if (state.running) {
                    stopControl()
                    if (state.usageMode == LocalUsageMode.WORK) sendControl(true)
                } else {
                    sendControl(false)
                }
            }
        }
    }
}
