package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.text.selection.SelectionContainer
import com.labteto.dshmobile.ui.components.DsSheetChoiceRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalWorkUiState
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.local.work.LocalRequirementEvidenceLink
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
import kotlinx.coroutines.delay
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
    onHistoryPage: ((String, com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor?) -> com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi)? = null,
    onArtifactHistory: (String, Int) -> List<LocalArtifactUiItem> = { _, _ -> emptyList() },
    onToolActivities: (String) -> List<LocalToolActivityUiItem> = { emptyList() },
    onEventSequence: (String) -> Long = { 0L },
    onToolEvidence: (String, String, Long) -> String? = { _, _, _ -> null },
    onRequirementEvidence: (String) -> List<LocalRequirementEvidenceLink> = { emptyList() },
    onLinkRequirementEvidence: (String, Int, LocalArtifactUiItem) -> Boolean = { _, _, _ -> false },
    onStopJob: (String) -> String,
    onStartBackgroundAgent: suspend (String) -> LocalWorkUiActionResult,
    onStartResearchAgent: suspend (String) -> LocalWorkUiActionResult = onStartBackgroundAgent,
    onSendAgentMessage: suspend (String, String) -> LocalWorkUiActionResult,
    onOpenResults: () -> Unit,
    onDismiss: () -> Unit,
    onOpenArtifact: (String) -> Unit = { onOpenResults() },
    onContinueArtifact: (String) -> Unit = {},
    usageRevision: kotlinx.coroutines.flow.StateFlow<Long>? = null,
    sessionUsage: ((String) -> com.labteto.dshmobile.local.TokenUsageAnalyticsSnapshot)? = null,
    taskUsage: (String) -> com.labteto.dshmobile.local.TokenUsageGroupDetail? = { null },
    requestUsage: (String) -> com.labteto.dshmobile.local.TokenUsageRecord? = { null },
) {
    val colors = DsTheme.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var showFullHistory by remember(state.sessionId) { mutableStateOf(false) }
    var showAgentLauncher by remember(state.sessionId) { mutableStateOf(false) }
    var agentTask by remember(state.sessionId) { mutableStateOf("") }
    var researchPreset by remember(state.sessionId) { mutableStateOf(false) }
    var agentFeedback by remember(state.sessionId) { mutableStateOf("") }
    var startingAgent by remember(state.sessionId) { mutableStateOf(false) }
    var artifacts by remember(state.sessionId) { mutableStateOf(emptyList<LocalArtifactUiItem>()) }
    var requirementLinks by remember(state.sessionId) { mutableStateOf(emptyList<LocalRequirementEvidenceLink>()) }
    var pendingRequirementArtifact by remember(state.sessionId) { mutableStateOf<LocalArtifactUiItem?>(null) }
    var requirementLinkFeedback by remember(state.sessionId) { mutableStateOf("") }
    var selectedToolCallId by remember(state.sessionId) { mutableStateOf<String?>(null) }
    var toolActivities by remember(state.sessionId) { mutableStateOf(emptyList<LocalToolActivityUiItem>()) }
    var artifactScanLimit by remember(state.sessionId) { mutableStateOf(384) }
    var artifactActionFailed by remember(state.sessionId) { mutableStateOf(false) }
    var activityRefreshFailed by remember(state.sessionId) { mutableStateOf(false) }
    // Bounded, visible-only refresh reads the single Session EventLog cursor, including tool
    // state changes that leave WorkState unchanged. No duplicate persistent event stream.
    LaunchedEffect(state.sessionId, state.running, state.jobs, state.todos, artifactScanLimit) {
        var lastSequence = Long.MIN_VALUE
        while (true) {
            try {
                val (sequence, recent) = withContext(Dispatchers.IO) {
                    val seq = onEventSequence(state.sessionId)
                    seq to if (seq != lastSequence) {
                        Triple(
                            if (artifactScanLimit == 384) onArtifacts(state.sessionId)
                            else onArtifactHistory(state.sessionId, artifactScanLimit),
                            onToolActivities(state.sessionId),
                            onRequirementEvidence(state.sessionId),
                        )
                    } else null
                }
                if (recent != null) {
                    artifacts = recent.first
                    toolActivities = recent.second
                    requirementLinks = recent.third
                    lastSequence = sequence
                }
                activityRefreshFailed = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the last successful projection; the next poll retries.
                activityRefreshFailed = true
            }
            delay(750)
        }
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
            if (onHistoryPage != null) {
                DsButton(text = stringResource(R.string.local_run_full_history), onClick = { showFullHistory = true },
                    variant = DsButtonVariant.Ghost, size = DsButtonSize.Small,
                    modifier = Modifier.padding(horizontal = DsSpacing.medium))
            }
            if (usageRevision != null && sessionUsage != null) {
                LocalRunCenterUsageSection(state.sessionId, usageRevision, sessionUsage, taskUsage, requestUsage)
            }
            if (!state.hasRunCenterContent() && artifacts.isEmpty() && toolActivities.isEmpty()) {
                Column(Modifier.fillMaxSize()) {
                    DsPageEmptyState(
                        icon = FeatherIcons.Activity,
                        title = stringResource(R.string.local_run_center_empty_title),
                        body = stringResource(R.string.local_run_center_empty_body),
                        modifier = Modifier.weight(1f),
                        actionText = stringResource(R.string.local_run_agent_start),
                        onAction = { showAgentLauncher = true },
                    )
                    if (artifactScanLimit < 4_096) {
                        DsButton(
                            text = stringResource(R.string.local_artifact_more_history),
                            onClick = { artifactScanLimit = (artifactScanLimit * 2).coerceAtMost(4_096) },
                            variant = DsButtonVariant.Ghost,
                            modifier = Modifier.padding(DsSpacing.medium),
                        )
                    }
                }
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
                    if (activityRefreshFailed) {
                        Text(stringResource(R.string.local_run_center_refresh_failed), color = colors.error)
                    }
                    if (toolActivities.isNotEmpty() || artifacts.isNotEmpty() || state.todos.isNotEmpty()) {
                        val evidence = localWorkDeliveryEvidenceCounts(state, artifacts, toolActivities)
                        Text(
                            stringResource(R.string.local_delivery_evidence_title),
                            style = DsType.base16Strong.withReadingWeight(), color = colors.labelPrimary,
                        )
                        Text(
                            stringResource(
                                R.string.local_delivery_evidence_counts,
                                evidence.availableFiles, evidence.missingFiles, evidence.uncheckedFiles,
                                evidence.completedTools, evidence.failedTools, evidence.unknownTools,
                                evidence.openTasks,
                            ),
                            style = DsType.small13.withReadingWeight(), color = colors.labelSecondary,
                        )
                        Text(
                            stringResource(R.string.local_delivery_evidence_scope),
                            style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary,
                        )
                    }
                    if (toolActivities.isNotEmpty()) {
                        Text(
                            stringResource(R.string.local_tool_activity_title),
                            style = DsType.base16Strong.withReadingWeight(),
                            color = colors.labelPrimary,
                        )
                        Text(
                            stringResource(R.string.local_tool_history_scope),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelTertiary,
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
                            DsSheetChoiceRow(
                                title = activity.name,
                                subtitle = stringResource(phase) + activity.errorCode?.let { " · " + it }.orEmpty(),
                                onClick = { selectedToolCallId = activity.callId },
                            )
                        }
                    }
                    if (artifacts.isNotEmpty()) {
                        Text(
                            stringResource(R.string.local_artifacts_title),
                            style = DsType.base16Strong.withReadingWeight(),
                            color = colors.labelPrimary,
                        )
                        Text(
                            stringResource(R.string.local_artifact_reference_hint),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                        Text(
                            stringResource(R.string.local_artifact_history_scope, artifactScanLimit),
                            style = DsType.caption11.withReadingWeight(),
                            color = colors.labelTertiary,
                        )
                        artifacts.forEach { artifact ->
                            val label = when (artifact.category) {
                                "file" -> if (artifact.currentlyAvailable == false)
                                    stringResource(R.string.local_artifact_file_unavailable)
                                    else stringResource(R.string.local_artifact_file)
                                "link" -> stringResource(R.string.local_artifact_link)
                                else -> stringResource(R.string.local_artifact_revision)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                            ) {
                                val detail = when {
                                    artifact.category != "file" -> stringResource(R.string.local_delivery_source_not_verified)
                                    artifact.currentlyAvailable == true &&
                                        artifact.versionAtCreation != null && artifact.versionNow != null &&
                                        artifact.versionAtCreation != artifact.versionNow ->
                                        stringResource(R.string.local_delivery_file_revision_changed)
                                    artifact.currentlyAvailable == true &&
                                        artifact.versionAtCreation != null &&
                                        artifact.versionAtCreation == artifact.versionNow ->
                                        stringResource(R.string.local_delivery_file_revision_matches)
                                    artifact.currentlyAvailable == true -> stringResource(R.string.local_delivery_file_present)
                                    artifact.currentlyAvailable == false -> stringResource(R.string.local_delivery_file_missing)
                                    else -> stringResource(R.string.local_delivery_file_unknown)
                                }
                                DsSheetChoiceRow(
                                    title = artifact.reference,
                                    subtitle = "$label · $detail",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        artifactActionFailed = false
                                        when (artifact.category) {
                                            "file" -> {
                                                if (artifact.currentlyAvailable == false) {
                                                    artifactActionFailed = true
                                                } else onOpenArtifact(artifact.reference)
                                            }
                                            "link" -> runCatching { uriHandler.openUri(artifact.reference) }
                                                .onFailure { artifactActionFailed = true }
                                            else -> clipboard.setText(AnnotatedString(artifact.reference))
                                        }
                                    },
                                )
                                artifact.sourceCallId?.let { callId ->
                                    if (toolActivities.any { it.callId == callId }) {
                                        DsButton(
                                            text = stringResource(R.string.local_delivery_open_evidence),
                                            onClick = { selectedToolCallId = callId },
                                            variant = DsButtonVariant.Ghost,
                                            size = DsButtonSize.Small,
                                        )
                                    }
                                }
                                if (artifact.category == "file" && artifact.currentlyAvailable == true &&
                                    state.todos.isNotEmpty()) {
                                    DsButton(
                                        text = stringResource(R.string.local_delivery_link_action),
                                        onClick = {
                                            pendingRequirementArtifact = artifact
                                            requirementLinkFeedback = ""
                                        },
                                        variant = DsButtonVariant.Ghost,
                                        size = DsButtonSize.Small,
                                    )
                                }
                                if (artifact.category == "file" && artifact.currentlyAvailable != false) {
                                    DsButton(
                                        text = stringResource(R.string.local_artifact_continue),
                                        onClick = { onContinueArtifact(artifact.reference) },
                                        variant = DsButtonVariant.Ghost,
                                        size = DsButtonSize.Small,
                                    )
                                }
                                DsButton(
                                    text = stringResource(R.string.common_copy),
                                    onClick = { clipboard.setText(AnnotatedString(artifact.reference)) },
                                    variant = DsButtonVariant.Ghost,
                                    size = DsButtonSize.Small,
                                )
                            }
                        }
                        if (requirementLinks.isNotEmpty()) {
                            Text(stringResource(R.string.local_delivery_linked_title),
                                style = DsType.small13Strong.withReadingWeight(), color = colors.labelPrimary)
                            requirementLinks.forEach { link ->
                                val current = artifacts.firstOrNull { it.reference == link.artifactPath }?.versionNow
                                Text(
                                    stringResource(
                                        R.string.local_delivery_linked_line,
                                        link.requirementIndex + 1,
                                        link.artifactPath,
                                        if (current == link.versionAtLink)
                                            stringResource(R.string.local_delivery_link_current)
                                        else stringResource(R.string.local_delivery_link_stale),
                                    ),
                                    style = DsType.small13.withReadingWeight(), color = colors.labelSecondary,
                                )
                            }
                        }
                        if (artifactActionFailed) {
                            Text(stringResource(R.string.local_artifact_open_failed), color = colors.error)
                        }
                        DsButton(
                            text = stringResource(R.string.local_artifacts_open_files),
                            onClick = onOpenResults,
                            variant = DsButtonVariant.Ghost,
                        )
                    }
                    if (artifactScanLimit < 4_096) {
                        DsButton(
                            text = stringResource(R.string.local_artifact_more_history),
                            onClick = { artifactScanLimit = (artifactScanLimit * 2).coerceAtMost(4_096) },
                            variant = DsButtonVariant.Ghost,
                        )
                    }
                }
            }
        }
    }

    pendingRequirementArtifact?.let { artifact ->
        DsBottomSheet(
            title = stringResource(R.string.local_delivery_link_title),
            subtitle = stringResource(R.string.local_delivery_link_hint),
            onDismiss = { pendingRequirementArtifact = null },
            scrollable = true,
        ) {
            if (requirementLinkFeedback.isNotBlank()) {
                Text(requirementLinkFeedback, style = DsType.small13.withReadingWeight(),
                    color = colors.labelSecondary)
            }
            state.todos.forEachIndexed { index, todo ->
                DsButton(
                    text = "${index + 1}. ${todo.content.take(100)}",
                    onClick = {
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                onLinkRequirementEvidence(state.sessionId, index, artifact)
                            }
                            requirementLinkFeedback = if (success) {
                                pendingRequirementArtifact = null
                                ""
                            } else "关联未保存：来源或文件版本可能已变化"
                        }
                    },
                    variant = DsButtonVariant.Ghost, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    val selectedActivity = toolActivities.firstOrNull { it.callId == selectedToolCallId }
    var fullArguments by remember(state.sessionId, selectedToolCallId) { mutableStateOf<String?>(null) }
    var fullResult by remember(state.sessionId, selectedToolCallId) { mutableStateOf<String?>(null) }
    var evidenceError by remember(state.sessionId, selectedToolCallId) { mutableStateOf(false) }
    LaunchedEffect(state.sessionId, selectedToolCallId, selectedActivity?.finishedSequence) {
        if (selectedActivity != null) {
            try {
                val evidence = withContext(Dispatchers.IO) {
                    val arguments = selectedActivity.declaredSequence?.let {
                        onToolEvidence(state.sessionId, selectedActivity.callId, it)
                    }
                    val result = selectedActivity.finishedSequence?.let {
                        onToolEvidence(state.sessionId, selectedActivity.callId, it)
                    }
                    arguments to result
                }
                fullArguments = evidence.first
                fullResult = evidence.second
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                evidenceError = true
            }
        }
    }
    if (selectedActivity != null) {
        DsBottomSheet(
            title = selectedActivity.name,
            subtitle = stringResource(R.string.local_tool_activity_details),
            scrollable = true,
            onDismiss = { selectedToolCallId = null },
        ) {
            Text(
                stringResource(R.string.local_tool_activity_evidence,
                    selectedActivity.declaredSequence?.toString() ?: "—",
                    selectedActivity.startedSequence?.toString() ?: "—",
                    selectedActivity.finishedSequence?.toString() ?: "—"),
                style = DsType.caption11.withReadingWeight(),
            )
            selectedActivity.errorCode?.let { code ->
                Text(stringResource(R.string.local_tool_activity_error, code), color = colors.error)
            }
            if (evidenceError) Text(stringResource(R.string.local_tool_activity_evidence_error), color = colors.error)
            (fullArguments ?: selectedActivity.argumentsPreview)?.let { arguments ->
                Text(stringResource(R.string.local_tool_activity_arguments), style = DsType.small13Strong)
                SelectionContainer { Text(arguments, style = DsType.small13) }
            }
            Text(stringResource(if (fullResult != null) R.string.local_tool_activity_full_result else R.string.local_tool_activity_result), style = DsType.small13Strong)
            SelectionContainer {
                Text(fullResult ?: selectedActivity.resultPreview ?: stringResource(R.string.local_tool_activity_pending_result),
                    style = DsType.small13)
            }
        }
    }

    if (showFullHistory && onHistoryPage != null) {
        LocalWorkHistorySheet(
            sessionId = state.sessionId,
            readPage = onHistoryPage,
            readEvidence = onToolEvidence,
            onOpenArtifact = onOpenArtifact,
            onDismiss = { showFullHistory = false },
        )
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

/**
 * Local, bounded observation from the existing Session event projection. An observed tool
 * completion is not proof of tests passing or a deliverable matching its requested version.
 */
internal data class LocalWorkDeliveryEvidenceCounts(
    val availableFiles: Int,
    val missingFiles: Int,
    val uncheckedFiles: Int,
    val completedTools: Int,
    val failedTools: Int,
    val unknownTools: Int,
    val openTasks: Int,
)

internal fun localWorkDeliveryEvidenceCounts(
    state: LocalWorkUiState,
    artifacts: List<LocalArtifactUiItem>,
    calls: List<LocalToolActivityUiItem>,
): LocalWorkDeliveryEvidenceCounts {
    val files = artifacts.filter { it.category == "file" }
    return LocalWorkDeliveryEvidenceCounts(
        availableFiles = files.count { it.currentlyAvailable == true },
        missingFiles = files.count { it.currentlyAvailable == false },
        uncheckedFiles = files.count { it.currentlyAvailable == null },
        completedTools = calls.count { it.phase == LocalToolUiPhase.COMPLETED },
        failedTools = calls.count { it.phase == LocalToolUiPhase.FAILED },
        unknownTools = calls.count {
            it.phase == LocalToolUiPhase.OUTCOME_UNKNOWN || it.phase == LocalToolUiPhase.CANCELLED
        },
        openTasks = state.todos.count { it.status == "pending" || it.status == "in_progress" },
    )
}
