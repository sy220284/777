package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsComposerField
import com.labteto.dshmobile.ui.components.DsPageEmptyState
import com.labteto.dshmobile.ui.components.DsTopBar
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun LocalWorkUiState.hasRunCenterContent(): Boolean =
    running ||
        goal != null ||
        workflowProgress != null ||
        todos.isNotEmpty() ||
        jobs.isNotEmpty() ||
        pendingApproval != null ||
        pendingQuestion != null ||
        plan.isNotEmpty() ||
        queuedInputCount > 0 ||
        handoffSummary != null

@Composable
internal fun LocalRunCenterScreen(
    state: LocalWorkUiState,
    onJobOutput: (String) -> String,
    onArtifacts: (String) -> List<LocalArtifactUiItem>,
    onToolActivities: (String) -> List<LocalToolActivityUiItem> = { emptyList() },
    onStopJob: (String) -> String,
    onStartBackgroundAgent: suspend (String) -> LocalWorkUiActionResult,
    onStartResearchAgent: suspend (String) -> LocalWorkUiActionResult = onStartBackgroundAgent,
    onSendAgentMessage: suspend (String, String) -> LocalWorkUiActionResult,
    onOpenResults: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    var showAgentLauncher by remember(state.sessionId) { mutableStateOf(false) }
    var agentTask by remember(state.sessionId) { mutableStateOf("") }
    var researchPreset by remember(state.sessionId) { mutableStateOf(false) }
    var agentFeedback by remember(state.sessionId) { mutableStateOf("") }
    var startingAgent by remember(state.sessionId) { mutableStateOf(false) }
    var artifacts by remember(state.sessionId) { mutableStateOf(emptyList<LocalArtifactUiItem>()) }
    var toolActivities by remember(state.sessionId) { mutableStateOf(emptyList<LocalToolActivityUiItem>()) }
    LaunchedEffect(state.sessionId, state.running, state.jobs, state.todos) {
        val (recentArtifacts, recentTools) = withContext(Dispatchers.IO) {
            onArtifacts(state.sessionId) to onToolActivities(state.sessionId)
        }
        artifacts = recentArtifacts
        toolActivities = recentTools
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.rootSurface(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            DsTopBar(
                title = stringResource(R.string.local_run_center),
                onBack = onDismiss,
                backContentDescription = stringResource(R.string.common_back),
                largeTitle = false,
                actionIcon = null,
                actionPainter = painterResource(R.drawable.ic_ui_create_subagent),
                actionContentDescription = stringResource(R.string.local_run_agent_start),
                onAction = { showAgentLauncher = true },
                modifier = Modifier.padding(horizontal = DsSpacing.medium),
            )
            if (!state.hasRunCenterContent() && artifacts.isEmpty() && toolActivities.isEmpty()) {
                DsPageEmptyState(
                    icon = FeatherIcons.Activity,
                    title = stringResource(R.string.local_run_center_empty_title),
                    body = stringResource(R.string.local_run_center_empty_body),
                    modifier = Modifier.fillMaxSize(),
                    actionText = stringResource(R.string.local_run_agent_start),
                    onAction = { showAgentLauncher = true },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                ) {
                    ExecutionStatusCard(
                        state = state,
                        onJobOutput = onJobOutput,
                        onStopJob = onStopJob,
                        onSendAgentMessage = onSendAgentMessage,
                        onOpenResults = onOpenResults,
                        showHeader = false,
                    )
                    if (toolActivities.isNotEmpty()) {
                        Text(
                            stringResource(R.string.local_tool_activity_title),
                            style = DsType.base16Strong.withReadingWeight(),
                            color = colors.labelPrimary,
                        )
                        toolActivities.forEach { activity ->
                            val phase = when (activity.phase) {
                                LocalToolUiPhase.DECLARED -> R.string.local_tool_phase_declared
                                LocalToolUiPhase.RUNNING -> R.string.local_tool_phase_running
                                LocalToolUiPhase.COMPLETED -> R.string.local_tool_phase_completed
                                LocalToolUiPhase.FAILED -> R.string.local_tool_phase_failed
                                LocalToolUiPhase.OUTCOME_UNKNOWN -> R.string.local_tool_phase_unknown
                                LocalToolUiPhase.CANCELLED -> R.string.local_tool_phase_cancelled
                            }
                            Text(
                                text = activity.name + " · " + stringResource(phase),
                                style = DsType.small13.withReadingWeight(),
                                color = colors.labelSecondary,
                            )
                        }
                    }
                    if (artifacts.isNotEmpty()) {
                        Text(
                            stringResource(R.string.local_artifacts_title),
                            style = DsType.base16Strong.withReadingWeight(),
                            color = colors.labelPrimary,
                        )
                        artifacts.forEach { artifact ->
                            Text(
                                artifact.reference,
                                style = DsType.small13.withReadingWeight(),
                                color = colors.labelSecondary,
                            )
                        }
                        DsButton(
                            text = stringResource(R.string.local_artifacts_open_files),
                            onClick = onOpenResults,
                            variant = DsButtonVariant.Ghost,
                        )
                    }
                }
            }
        }
    }

    if (showAgentLauncher) {
        DsBottomSheet(
            title = stringResource(R.string.local_run_agent_direct_title),
            subtitle = stringResource(R.string.local_run_agent_direct_body),
            scrollable = true,
            dismissEnabled = !startingAgent,
            footer = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DsButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = {
                            showAgentLauncher = false
                            agentFeedback = ""
                        },
                        enabled = !startingAgent,
                        variant = DsButtonVariant.Ghost,
                        size = DsButtonSize.Large,
                        modifier = Modifier.weight(1f),
                    )
                    DsButton(
                        text = stringResource(R.string.local_run_agent_start),
                        onClick = {
                            val task = agentTask.trim()
                            if (task.isNotEmpty() && !startingAgent) {
                                scope.launch {
                                    startingAgent = true
                                    try {
                                        val result = if (researchPreset) onStartResearchAgent(task) else onStartBackgroundAgent(task)
                                        agentFeedback = result.message
                                        if (result.accepted) {
                                            agentTask = ""
                                            showAgentLauncher = false
                                        }
                                    } finally {
                                        startingAgent = false
                                    }
                                }
                            }
                        },
                        enabled = agentTask.isNotBlank() && !startingAgent,
                        loading = startingAgent,
                        size = DsButtonSize.Large,
                        modifier = Modifier.weight(1f),
                    )
                }
            },
            onDismiss = {
                if (!startingAgent) {
                    showAgentLauncher = false
                    agentFeedback = ""
                }
            },
        ) {
            DsComposerField(
                value = agentTask,
                onValueChange = {
                    agentTask = it
                    agentFeedback = ""
                },
                placeholder = stringResource(R.string.local_run_agent_task_hint),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                enabled = !startingAgent,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                DsButton(
                    text = stringResource(R.string.local_research_agent_general),
                    onClick = { researchPreset = false },
                    enabled = !startingAgent,
                    variant = if (researchPreset) DsButtonVariant.Ghost else DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                )
                DsButton(
                    text = stringResource(R.string.local_research_agent_research),
                    onClick = { researchPreset = true },
                    enabled = !startingAgent,
                    variant = if (researchPreset) DsButtonVariant.Outline else DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
            if (researchPreset) {
                Text(
                    stringResource(R.string.local_research_agent_readonly_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                )
            }
            Text(
                stringResource(R.string.local_run_agent_inherits_context),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelTertiary,
            )
            agentFeedback.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = DsType.small13.withReadingWeight(),
                    color = colors.labelSecondary,
                )
            }

        }
    }
}
