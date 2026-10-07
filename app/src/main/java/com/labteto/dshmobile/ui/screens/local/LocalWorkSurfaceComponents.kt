package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
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

@Composable
internal fun ExecutionStatusCard(
    state: LocalWorkUiState,
    onJobOutput: (String) -> String,
    onStopJob: (String) -> String,
    onOpenResults: () -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    val colors = DsTheme.colors
    var expandedJobId by remember(state.sessionId) { mutableStateOf<String?>(null) }
    var expandedJobOutput by remember(state.sessionId) { mutableStateOf("") }
    var technicalDetailsExpanded by remember(state.sessionId) { mutableStateOf(false) }
    var showAll by remember(state.sessionId) { mutableStateOf(false) }
    val completed = state.todos.count { it.status == "completed" }
    val total = state.todos.size
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
        color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
    ) {
        Column(
            Modifier.padding(DsSpacing.comfortable),
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

            state.goal?.let { goal ->
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    Text(
                        stringResource(R.string.local_run_current_goal),
                        style = DsType.caption11Strong.withReadingWeight(),
                        color = colors.labelTertiary,
                    )
                    Text(goal.description, style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary)
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
                            style = DsType.small13.withReadingWeight(),
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
                            style = DsType.small13.withReadingWeight(),
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

            if (state.jobs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    Text(
                        stringResource(R.string.local_run_background),
                        style = DsType.caption11Strong.withReadingWeight(),
                        color = colors.labelTertiary,
                    )
                    (if (showAll) state.jobs else state.jobs.sortedBy { it.status !in setOf("running", "idle", "interrupted") }.take(4)).forEach { job ->
                        val expanded = expandedJobId == job.id
                        Surface(
                            shape = DsShapes.row,
                            color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(DsSpacing.small),
                                verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                                ) {
                                    Text(
                                        job.label,
                                        style = DsType.small13.withReadingWeight(),
                                        color = colors.labelSecondary,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        localJobStatusLabel(job.status),
                                        style = DsType.caption11.withReadingWeight(),
                                        color = colors.labelTertiary,
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
                                                expandedJobOutput = onJobOutput(job.id)
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
                                            style = DsType.caption11.withReadingWeight(),
                                            color = colors.labelSecondary,
                                            maxLines = 12,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                        ) {
                                            DsButton(
                                                text = stringResource(R.string.local_run_job_refresh),
                                                onClick = { expandedJobOutput = onJobOutput(job.id) },
                                                variant = DsButtonVariant.Ghost,
                                                size = DsButtonSize.Small,
                                            )
                                            if (job.status in setOf("running", "idle", "interrupted")) {
                                                DsButton(
                                                    text = stringResource(R.string.local_run_job_stop),
                                                    onClick = {
                                                        onStopJob(job.id)
                                                        expandedJobOutput = onJobOutput(job.id)
                                                    },
                                                    variant = DsButtonVariant.Danger,
                                                    size = DsButtonSize.Small,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            if (state.plan.size > 5 || state.todos.size > 5 || state.jobs.size > 4) {
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
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
