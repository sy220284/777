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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.ui.components.DsComposerAction
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme

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
    onPlanModeChange: (Boolean) -> Unit,
    onAutoApprove: () -> Unit,
    onDisableAutoApprove: () -> Unit,
) {
    val colors = DsTheme.colors
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
            if (state.usageMode == LocalUsageMode.WORK) {
                DsComposerAction(
                    icon = FeatherIcons.List,
                    contentDescription = stringResource(
                        if (state.planMode) R.string.local_plan_button_on
                        else R.string.local_plan_button_off,
                    ),
                    onClick = { onPlanModeChange(!state.planMode) },
                    enabled = !state.running,
                    tint = colors.labelPrimary,
                    containerColor = if (state.planMode) colors.hoverSolid else Color.Transparent,
                )
                DsComposerAction(
                    icon = FeatherIcons.Shield,
                    contentDescription = stringResource(R.string.local_auto_approve_short),
                    onClick = if (state.safeAutoApprovalEnabled) onDisableAutoApprove else onAutoApprove,
                    tint = colors.labelPrimary,
                    containerColor =
                        if (state.safeAutoApprovalEnabled) colors.hoverSolid else Color.Transparent,
                )
            }
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
