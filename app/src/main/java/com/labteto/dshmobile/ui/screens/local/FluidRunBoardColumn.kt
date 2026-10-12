package com.labteto.dshmobile.ui.screens.local

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** Design 03's three board columns become three independent views on a compact display. */
@Composable
internal fun FluidRunBoardColumn(
    state: LocalWorkUiState,
    section: Int,
    onJobOutput: (String) -> String,
    onStopJob: (String) -> String,
    onSendAgentMessage: suspend (String, String) -> LocalWorkUiActionResult,
    onOpenDecision: () -> Unit,
) {
    val jobs = state.jobs.filter { job ->
        if (section == 1) job.needsUserAttention() else job.status == "running" && !job.needsUserAttention()
    }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(500)),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        if (section == 0 && (state.running || state.goal != null)) DsCard {
            FluidPanelTitle(stringResource(R.string.local_run_current_goal))
            state.goal?.description?.takeIf(String::isNotBlank)?.let { goal ->
                Text(goal, style = DsType.std14, color = DsTheme.colors.labelPrimary)
            }
            state.goal?.let { goal ->
                Text(localGoalStatusLabel(goal.status), style = DsType.caption11,
                    color = DsTheme.colors.labelSecondary)
            }
            if (state.running) {
                state.workflowProgress?.let { WorkflowProgressSection(it) }
                    ?: Text(stringResource(R.string.local_tool_phase_running),
                        style = DsType.small13, color = DsTheme.colors.success)
            }
        }
        if (section == 1 && (state.pendingQuestion != null || state.pendingApproval != null)) DsCard {
            FluidPanelTitle(stringResource(R.string.fluid_decision_checkpoint))
            Text(state.pendingQuestion?.question ?: state.pendingApproval?.summary.orEmpty(),
                style = DsType.std14, color = DsTheme.colors.labelPrimary)
            DsButton(stringResource(R.string.fluid_board_open_decision), onOpenDecision)
        }
        if (jobs.isNotEmpty()) RunCenterJobsSection(state.sessionId, jobs, section == 1,
            showAll = true, onJobOutput, onStopJob, onSendAgentMessage)
        else if (!(section == 0 && (state.running || state.goal != null)) &&
            !(section == 1 && (state.pendingQuestion != null || state.pendingApproval != null))) {
            DsCard { Text(stringResource(R.string.fluid_board_empty), color = DsTheme.colors.labelSecondary) }
        }
        if (section == 1) state.goal?.let { goal -> DsCard {
            FluidPanelTitle(stringResource(R.string.local_run_current_goal))
            Text(goal.description, style = DsType.std14, color = DsTheme.colors.labelPrimary)
        } }

    }
}
