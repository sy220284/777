package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsComposerField
import com.labteto.dshmobile.ui.components.DsExpandableColumn
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@Composable
internal fun ExecutionStatusCard(
    state: LocalWorkUiState,
    onJobOutput: (String) -> String,
    onStopJob: (String) -> String,
    onSendAgentMessage: suspend (String, String) -> LocalWorkUiActionResult,
    onOpenResults: () -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    val colors = DsTheme.colors
    var technicalDetailsExpanded by remember(state.sessionId) { mutableStateOf(false) }
    var showAll by remember(state.sessionId) { mutableStateOf(false) }
    val completed = state.todos.count { it.status == "completed" }
    val total = state.todos.size
    val actionRequiredJobs = state.jobs.filter(LocalJobInfo::needsUserAttention)
    val backgroundJobs = state.jobs.filterNot(LocalJobInfo::needsUserAttention)
    val resourceSummary = stringResource(
        R.string.local_resource_summary,
        state.activeAgents,
        state.maxAgents,
        state.activeTerminals,
        state.maxTerminals,
        state.activeVirtualDisplays,
        state.maxVirtualDisplays,
        state.activeLanguageServers,
        state.maxLanguageServers,
    )
    val contextSummary = stringResource(
        R.string.local_context_summary,
        state.contextChars,
        state.contextBudgetChars,
    )
    val pressureSummary = stringResource(
        R.string.local_resource_pressure,
        localResourcePressureLabel(state.resourcePressure),
    )
    val contextSourceSummary = stringResource(
        R.string.local_context_source,
        localConversationModeLabel(state.conversationMode),
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = if (showHeader) {
            colors.wallpaperSurface(WallpaperSurfaceLevel.CARD)
        } else {
            colors.bgBase
        },
    ) {
        Column(
            modifier = Modifier.padding(
                if (showHeader) DsSpacing.comfortable else DsSpacing.small,
            ),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            if (showHeader) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    StateDot(if (state.running) StateDotState.Running else StateDotState.Idle)
                    Text(
                        stringResource(R.string.local_run_center),
                        style = DsType.base16Strong.withReadingWeight(),
                        color = colors.labelPrimary,
                    )
                }
            }



            if (actionRequiredJobs.isNotEmpty()) {
                RunCenterJobsSection(
                    sessionId = state.sessionId,
                    jobs = actionRequiredJobs,
                    attention = true,
                    showAll = showAll,
                    onJobOutput = onJobOutput,
                    onStopJob = onStopJob,
                    onSendAgentMessage = onSendAgentMessage,
                )
            }

            state.goal?.let { goal ->
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    Text(
                        stringResource(R.string.local_run_current_goal),
                        style = DsType.caption11Strong.withReadingWeight(),
                        color = colors.labelTertiary,
                    )
                    Text(goal.description, style = DsType.std14Strong.withReadingWeight().copy(fontFamily = DsType.contentFont), color = colors.labelPrimary)
                    Text(localGoalStatusLabel(goal.status), style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                }
            }

            state.workflowProgress?.takeIf { it.sessionId == state.sessionId }?.let { progress ->
                WorkflowProgressSection(progress)
            }

            if (state.pendingApproval != null || state.pendingQuestion != null) {
                Text(
                    stringResource(R.string.local_workflow_waiting_user),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.warnLabel,
                )
            }

            if (backgroundJobs.isNotEmpty()) {
                RunCenterJobsSection(
                    sessionId = state.sessionId,
                    jobs = backgroundJobs,
                    attention = false,
                    showAll = showAll,
                    onJobOutput = onJobOutput,
                    onStopJob = onStopJob,
                    onSendAgentMessage = onSendAgentMessage,
                )
            }



            if (state.plan.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    Text(
                        stringResource(R.string.local_run_plan),
                        style = DsType.caption11Strong.withReadingWeight(),
                        color = colors.labelTertiary,
                    )
                    (if (showAll) state.plan else state.plan.take(5)).forEachIndexed { index, step ->
                        Text(
                            (index + 1).toString().padStart(2, '0') + "  " + step,
                            style = DsType.small13.withReadingWeight().copy(fontFamily = DsType.contentFont),
                            color = colors.labelSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (state.todos.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    Text(
                        stringResource(R.string.local_run_tasks_progress, completed, total),
                        style = DsType.caption11Strong.withReadingWeight(),
                        color = colors.labelTertiary,
                    )
                    (if (showAll) state.todos else state.todos.sortedBy { it.status == "completed" }.take(5)).forEach { todo ->
                        val marker = when (todo.status) {
                            "completed" -> "✓"
                            "in_progress", "running" -> "●"
                            else -> "○"
                        }
                        Text(
                            marker + "  " + todo.content,
                            style = DsType.small13.withReadingWeight().copy(fontFamily = DsType.contentFont),
                            color = if (todo.status == "completed") {
                                colors.labelTertiary
                            } else {
                                colors.labelSecondary
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }



            if (
                state.plan.size > 5 ||
                state.todos.size > 5 ||
                actionRequiredJobs.size > 4 ||
                backgroundJobs.size > 4
            ) {
                DsButton(
                    text = stringResource(if (showAll) R.string.audit_show_summary else R.string.audit_show_all),
                    onClick = { showAll = !showAll },
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DsButton(
                text = stringResource(R.string.local_run_open_results),
                onClick = onOpenResults,
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.queuedInputCount > 0) {
                Text(
                    stringResource(R.string.local_queue_count, state.queuedInputCount),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                )
            }

            DsButton(
                text = stringResource(R.string.local_run_resources),
                onClick = { technicalDetailsExpanded = !technicalDetailsExpanded },
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
            DsExpandableColumn(
                visible = technicalDetailsExpanded,
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                    Text(resourceSummary, style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                    Text(pressureSummary, style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                    Text(contextSummary, style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
                    Text(contextSourceSummary, style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
                    if (
                        state.conversationMode == LocalConversationMode.CONTINUATION &&
                        !state.handoffSummary.isNullOrBlank()
                    ) {
                        Text(
                            stringResource(R.string.local_context_handoff),
                            style = DsType.caption11Strong.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                        Text(
                            state.handoffSummary.orEmpty(),
                            style = DsType.caption11.withReadingWeight().copy(fontFamily = DsType.contentFont),
                            color = colors.labelSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

private fun LocalJobInfo.needsUserAttention(): Boolean =
    isAgent && (status == "dormant" || pendingMessageCount > 0)

@Composable
private fun RunCenterJobsSection(
    sessionId: String,
    jobs: List<LocalJobInfo>,
    attention: Boolean,
    showAll: Boolean,
    onJobOutput: (String) -> String,
    onStopJob: (String) -> String,
    onSendAgentMessage: suspend (String, String) -> LocalWorkUiActionResult,
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    var agentMessageDraft by remember(sessionId) { mutableStateOf("") }
    var agentMessageFeedback by remember(sessionId) { mutableStateOf("") }
    var agentMessageSending by remember(sessionId) { mutableStateOf(false) }
    var jobActionFeedback by remember(sessionId) { mutableStateOf("") }
    var expandedJobId by remember(sessionId) { mutableStateOf<String?>(null) }
    var expandedJobOutput by remember(sessionId) { mutableStateOf("") }
    var expandedOutputError by remember(sessionId) { mutableStateOf(false) }
    val outputFailedMessage = stringResource(R.string.local_run_job_output_read_failed)
    val agentActionFailedMessage = stringResource(R.string.local_team_action_failed)

    fun refreshJobOutput(jobId: String) {
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { onJobOutput(jobId) }
                if (expandedJobId == jobId) {
                    expandedJobOutput = updated
                    expandedOutputError = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expandedJobId == jobId) expandedOutputError = true
            }
        }
    }
    val orderedJobs = jobs.sortedWith(
        compareBy<LocalJobInfo> {
            when {
                it.needsUserAttention() -> 0
                it.status in setOf("running", "interrupted") -> 1
                else -> 2
            }
        }.thenByDescending(LocalJobInfo::pendingMessageCount),
    )

    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text(
            if (attention) {
                stringResource(R.string.local_run_needs_attention, jobs.size)
            } else {
                stringResource(R.string.local_run_background)
            },
            style = DsType.caption11Strong.withReadingWeight(),
            color = if (attention) colors.warnLabel else colors.labelTertiary,
        )
        (if (showAll) orderedJobs else orderedJobs.take(4)).forEach { job ->
            val expanded = expandedJobId == job.id
            val active = job.status == "running"
            Surface(
                shape = DsShapes.row,
                color = if (attention) {
                    colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING)
                } else {
                    colors.bgModulePlatform
                },
                border = if (job.isAgent) {
                    BorderStroke(
                        1.dp,
                        if (active) colors.accent.copy(alpha = 0.26f) else colors.borderL2,
                    )
                } else {
                    null
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DsSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        if (job.isAgent) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ui_create_subagent),
                                contentDescription = null,
                                tint = if (active) colors.accent else colors.labelSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            StateDot(
                                state = when (job.status) {
                                    "running" -> StateDotState.Running
                                    "failed" -> StateDotState.Error
                                    "completed" -> StateDotState.Done
                                    else -> StateDotState.Idle
                                },
                                size = 8.dp,
                            )
                        }
                        Text(
                            job.label,
                            style = if (job.isAgent) {
                                DsType.small13Strong.withReadingWeight().copy(fontFamily = DsType.contentFont)
                            } else {
                                DsType.small13.withReadingWeight().copy(fontFamily = DsType.contentFont)
                            },
                            color = if (job.isAgent || attention) colors.labelPrimary else colors.labelSecondary,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            localJobStatusLabel(job.status),
                            style = DsType.caption11Strong.withReadingWeight(),
                            color = when {
                                attention -> colors.warnLabel
                                active -> colors.accent
                                else -> colors.labelTertiary
                            },
                        )
                        DsButton(
                            text = stringResource(
                                if (expanded) R.string.local_run_job_hide
                                else R.string.local_run_job_view,
                            ),
                            onClick = {
                                if (expanded) {
                                    expandedJobId = null
                                    expandedJobOutput = ""
                                } else {
                                    expandedJobId = job.id
                                    expandedJobOutput = ""
                                    expandedOutputError = false
                                    refreshJobOutput(job.id)
                                    agentMessageDraft = ""
                                    agentMessageFeedback = ""
                                    jobActionFeedback = ""
                                }
                            },
                            variant = DsButtonVariant.Ghost,
                            size = DsButtonSize.Small,
                        )
                    }
                    DsExpandableColumn(
                        visible = expanded,
                        verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                    ) {
                        Text(
                            stringResource(R.string.local_run_job_output),
                            style = DsType.caption11Strong.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                        Text(
                            expandedJobOutput.ifBlank {
                                stringResource(R.string.local_run_job_output_empty)
                            },
                            style = DsType.caption11.withReadingWeight().copy(fontFamily = DsType.contentFont),
                            color = colors.labelSecondary,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (expandedOutputError) {
                            Text(
                                outputFailedMessage,
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.error,
                            )
                        }
                        if (job.isAgent) {
                            if (job.status == "dormant") {
                                Text(
                                    stringResource(R.string.local_run_agent_dormant_hint),
                                    style = DsType.caption11.withReadingWeight(),
                                    color = colors.labelSecondary,
                                )
                            }
                            if (job.pendingMessageCount > 0) {
                                Text(
                                    stringResource(
                                        R.string.local_run_agent_pending_messages,
                                        job.pendingMessageCount,
                                    ),
                                    style = DsType.caption11.withReadingWeight(),
                                    color = colors.labelTertiary,
                                )
                            }
                            if (job.canMessage) {
                                DsComposerField(
                                    value = agentMessageDraft,
                                    onValueChange = {
                                        agentMessageDraft = it
                                        agentMessageFeedback = ""
                                    },
                                    placeholder = stringResource(R.string.local_run_agent_message_hint),
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    DsButton(
                                        text = stringResource(R.string.local_run_agent_send),
                                        onClick = {
                                            val message = agentMessageDraft.trim()
                                            if (message.isNotEmpty() && !agentMessageSending) {
                                                scope.launch {
                                                    agentMessageSending = true
                                                    try {
                                                        val result = onSendAgentMessage(job.id, message)
                                                        agentMessageFeedback = result.message
                                                        if (result.accepted) agentMessageDraft = ""
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (_: Exception) {
                                                        agentMessageFeedback = agentActionFailedMessage
                                                    } finally {
                                                        agentMessageSending = false
                                                    }
                                                }
                                            }
                                        },
                                        enabled = agentMessageDraft.isNotBlank() && !agentMessageSending,
                                        loading = agentMessageSending,
                                        variant = DsButtonVariant.Outline,
                                        size = DsButtonSize.Small,
                                    )
                                }
                                if (agentMessageFeedback.isNotBlank()) {
                                    Text(
                                        agentMessageFeedback,
                                        style = DsType.caption11.withReadingWeight().copy(fontFamily = DsType.contentFont),
                                        color = colors.labelSecondary,
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            DsButton(
                                text = stringResource(R.string.local_run_job_refresh),
                                onClick = { refreshJobOutput(job.id) },
                                variant = DsButtonVariant.Ghost,
                                size = DsButtonSize.Small,
                            )
                            if (job.status in setOf("running", "dormant", "interrupted")) {
                                DsButton(
                                    text = stringResource(R.string.local_run_job_stop),
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val message = withContext(Dispatchers.IO) { onStopJob(job.id) }
                                                if (expandedJobId == job.id) jobActionFeedback = message
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                if (expandedJobId == job.id) jobActionFeedback = agentActionFailedMessage
                                            } finally {
                                                refreshJobOutput(job.id)
                                            }
                                        }
                                    },
                                    variant = DsButtonVariant.Danger,
                                    size = DsButtonSize.Small,
                                )
                            }
                        }
                        if (jobActionFeedback.isNotBlank()) {
                            Text(
                                jobActionFeedback,
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.labelSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
