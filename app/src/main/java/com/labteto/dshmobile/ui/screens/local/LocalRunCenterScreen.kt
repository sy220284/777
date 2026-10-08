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
    onStopJob: (String) -> String,
    onStartBackgroundAgent: suspend (String) -> LocalWorkUiActionResult,
    onSendAgentMessage: suspend (String, String) -> LocalWorkUiActionResult,
    onOpenResults: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    var showAgentLauncher by remember(state.sessionId) { mutableStateOf(false) }
    var agentTask by remember(state.sessionId) { mutableStateOf("") }
    var agentFeedback by remember(state.sessionId) { mutableStateOf("") }
    var startingAgent by remember(state.sessionId) { mutableStateOf(false) }

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
            if (!state.hasRunCenterContent()) {
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
                }
            }
        }
    }

    if (showAgentLauncher) {
        DsBottomSheet(
            title = stringResource(R.string.local_run_agent_direct_title),
            subtitle = stringResource(R.string.local_run_agent_direct_body),
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
            Text(
                stringResource(R.string.local_run_agent_inherits_context),
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelTertiary,
            )
            agentFeedback.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                )
            }
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
                    size = DsButtonSize.Small,
                )
                DsButton(
                    text = stringResource(R.string.local_run_agent_start),
                    onClick = {
                        val task = agentTask.trim()
                        if (task.isNotEmpty() && !startingAgent) {
                            scope.launch {
                                startingAgent = true
                                try {
                                    val result = onStartBackgroundAgent(task)
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
                    size = DsButtonSize.Small,
                )
            }
        }
    }
}
