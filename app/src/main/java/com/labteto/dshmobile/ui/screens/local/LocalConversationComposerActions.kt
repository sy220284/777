package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing

/**
 * Owns the focused/expanded composer action row.
 *
 * Keeping this row outside [LocalConversationComposer] prevents the input/focus coordinator from
 * accumulating Work-specific controls and gives the row one place to own its reveal animation.
 */
@Composable
internal fun LocalConversationComposerExpandedRow(
    visible: Boolean,
    state: LocalConversationSurfaceState,
    menuControl: @Composable () -> Unit,
    replySuggestionsControl: @Composable () -> Unit,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            menuControl()
            replySuggestionsControl()
            Spacer(Modifier.weight(1f))
            if (state.running) {
                stopControl()
                if (state.usageMode == LocalUsageMode.WORK) sendControl(true)
            } else {
                sendControl(false)
            }
        }
    }
}
