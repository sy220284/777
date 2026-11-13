package com.labteto.dshmobile.ui.screens.local

// FEATURE_TREE: {"id":"work.history-evidence-ui","path":"第一层｜产品功能与界面 / 工作与智能体功能 / 会话内工作过程与成果 / 工具调用活动时间线","owner":"WORK","entry":"LocalWorkEvidenceSheet","test":"app/src/androidTest/java/com/labteto/dshmobile/ui/screens/local/LocalWorkEvidenceSheetTest.kt#olderPageIsReachableAndUnknownOutcomeKeepsItsMeaning"}
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalRequirementUiTarget
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.ui.agentOperationKind
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SySegment
import com.labteto.dshmobile.ui.shenyan.SySegmented
import com.labteto.dshmobile.ui.theme.DsType
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun workToolPhaseLabel(phase: LocalToolUiPhase): Int = when (phase) {
    LocalToolUiPhase.DECLARED -> R.string.work_tool_declared
    LocalToolUiPhase.RUNNING -> R.string.work_tool_running
    LocalToolUiPhase.COMPLETED -> R.string.work_tool_completed
    LocalToolUiPhase.FAILED -> R.string.work_tool_failed
    LocalToolUiPhase.OUTCOME_UNKNOWN -> R.string.work_tool_unknown
    LocalToolUiPhase.CANCELLED -> R.string.work_tool_cancelled
}

/** A call ID identifies one activity; an execution ID is not a reliable list-row identity. */
internal fun workEvidenceActivityKey(activity: LocalToolActivityUiItem): String = "active:${activity.callId}"

/** Low-frequency evidence is reachable from the Work conversation, without another product route. */
@Composable
internal fun LocalWorkEvidenceSheet(
    sessionId: String,
    workspacePath: String,
    actions: LocalWorkFeatureUiActions,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(sessionId, actions) { LocalWorkEvidenceController(scope, sessionId, actions) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) { controller.refresh() }
    val state by controller.state.collectAsStateWithLifecycle()
    val work by actions.workState.collectAsStateWithLifecycle()
    val currentWork = work.takeIf { it.sessionId == sessionId }
    var tab by remember(sessionId) { mutableStateOf("history") }
    var evidenceRequest by remember(sessionId) { mutableStateOf<Pair<String, Long>?>(null) }
    val colors = LocalSyColors.current
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .60f }
    SyResponsiveSheet(stringResource(R.string.work_evidence_title), onDismiss = onDismiss) {
        SySegmented(
            segments = listOf(SySegment("history", stringResource(R.string.work_evidence_history)),
                SySegment("requirements", stringResource(R.string.work_evidence_requirements))),
            selectedKey = tab, onSelect = { tab = it }, stretch = true, modifier = Modifier.fillMaxWidth(),
        )
        SyButton(stringResource(R.string.work_evidence_refresh), onClick = controller::refresh,
            enabled = !state.loading && !state.linking, variant = SyButtonVariant.Ghost)
        if (state.linksFailed) Text(stringResource(R.string.work_evidence_link_error), color = colors.dangerInk)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("work_evidence_records"),
            verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg)) {
            if (state.loading) item { Text(stringResource(R.string.common_loading), color = colors.textSecondary) }
            if (state.failed) item {
                Text(stringResource(R.string.work_evidence_failed), color = colors.dangerInk)
                SyButton(stringResource(R.string.common_retry), onClick = controller::retry)
            }
            if (state.invalidated || currentWork == null) item {
                Text(stringResource(R.string.work_evidence_invalidated), color = colors.dangerInk)
            }
            if (tab == "requirements") {
                currentWork?.goal?.let { goal -> item {
                    Text(goal.description, style = DsType.std14Strong, color = colors.text)
                    Text(localGoalStatusLabel(goal.status), style = DsType.small13, color = colors.textSecondary)
                    goal.note?.let { Text(it, color = colors.textSecondary) }
                } }
                if (currentWork?.todos.isNullOrEmpty()) item {
                    Text(stringResource(R.string.work_evidence_no_requirements), color = colors.textSecondary)
                }
                itemsIndexed(currentWork?.todos.orEmpty()) { index, todo ->
                    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                        Text("${index + 1}. ${todo.content}", style = DsType.std14Strong, color = colors.text)
                        state.links.filter { it.requirementIndex == index && it.requirement == todo.content }.forEach { link ->
                            Text(link.artifactPath, style = DsType.small13, color = colors.text)
                            Text(if (link.versionNow == link.versionAtLink) link.versionAtLink
                                else stringResource(R.string.work_evidence_changed), style = DsType.caption11,
                                color = colors.textSecondary)
                            SyButton(stringResource(R.string.media_chat_view_entire_file), onClick = { onOpenFile(link.artifactPath) },
                                size = SyButtonSize.Small, variant = SyButtonVariant.Ghost)
                        }
                    }
                }
                item { Text(stringResource(R.string.work_evidence_reference_only), style = DsType.small13, color = colors.textSecondary) }
            } else {
                // Running/declared calls have no completed transcript row. Preserve their real phases.
                items(state.activities.filter { it.phase in setOf(LocalToolUiPhase.DECLARED, LocalToolUiPhase.RUNNING) },
                    key = ::workEvidenceActivityKey) { activity ->
                    Text(stringResource(agentOperationLabelRes(agentOperationKind(activity.name))),
                        modifier = Modifier.testTag("work_evidence_active_${activity.callId}"), color = colors.text)
                    Text(stringResource(workToolPhaseLabel(activity.phase)), color = colors.textSecondary)
                }
                if (state.records.isEmpty() && !state.loading && !state.failed && !state.invalidated) item {
                    Text(stringResource(R.string.work_evidence_empty), color = colors.textSecondary)
                }
                items(state.records, key = { "event:${it.sequence}" }) { record ->
                    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
                        Text(stringResource(agentOperationLabelRes(agentOperationKind(record.name))), style = DsType.std14Strong, color = colors.text)
                        record.phase?.let { Text(stringResource(workToolPhaseLabel(it)), color = colors.textSecondary) }
                        Text("#${record.sequence} · ${record.name}", style = DsType.caption11, color = colors.textSecondary)
                        record.callId?.let { callId ->
                            SyButton(stringResource(R.string.work_tool_details), onClick = { evidenceRequest = callId to record.sequence },
                                variant = SyButtonVariant.Ghost, size = SyButtonSize.Small)
                        }
                        record.artifacts.forEach { artifact ->
                            if (artifact.category == "file") {
                                // History is a ledger, not the live transcript: rendering every
                                // full media card starts file previews/image decoding as soon as
                                // the sheet opens. Show the existing reference and explicit actions.
                                Text(artifact.reference, style = DsType.small13, color = colors.text)
                                Text(stringResource(
                                    if (artifact.currentlyAvailable == false) R.string.media_chat_work_file_missing
                                    else R.string.media_chat_work_output_here,
                                ), style = DsType.caption11, color = colors.textSecondary)
                                SyButton(stringResource(R.string.media_chat_view_entire_file),
                                    onClick = { onOpenFile(artifact.reference) },
                                    size = SyButtonSize.Small, variant = SyButtonVariant.Ghost)
                            } else {
                                LocalWorkArtifactMessageRow(artifact, workspacePath, actions,
                                    onOpen = { onOpenFile(artifact.reference) })
                            }
                            if (artifact.category == "file" && artifact.currentlyAvailable == true) {
                                SyButton(stringResource(R.string.work_evidence_current_version),
                                    onClick = { controller.inspect(artifact) }, enabled = !state.linking && !state.invalidated,
                                    variant = SyButtonVariant.Ghost, size = SyButtonSize.Small)
                            }
                        }
                    }
                }
                if (state.older != null) item {
                    SyButton(stringResource(R.string.work_evidence_older), onClick = controller::older,
                        enabled = !state.loading && !state.linking, modifier = Modifier.testTag("work_evidence_older"))
                }
            }
        }
    }
    state.inspected?.let { artifact ->
        SyResponsiveSheet(stringResource(R.string.work_evidence_choose_requirement), onDismiss = controller::dismissInspection,
            dismissEnabled = !state.linking, scrollable = true) {
            Text(artifact.reference, style = DsType.std14Strong, color = colors.text)
            Text(artifact.versionNow ?: stringResource(R.string.work_evidence_changed), style = DsType.small13, color = colors.textSecondary)
            Text(stringResource(R.string.work_evidence_reference_only), style = DsType.small13, color = colors.textSecondary)
            currentWork?.todos?.forEachIndexed { index, todo ->
                SyButton(todo.content, onClick = { controller.link(LocalRequirementUiTarget(index, todo.content, currentWork.goal?.id), artifact) },
                    enabled = !state.linking && artifact.versionNow != null && state.linkAccepted != true,
                    variant = SyButtonVariant.Ghost, modifier = Modifier.testTag("work_evidence_link_$index"))
            }
            if (currentWork?.todos.isNullOrEmpty()) Text(stringResource(R.string.work_evidence_no_requirements), color = colors.textSecondary)
            state.linkAccepted?.let { accepted -> Text(stringResource(if (accepted) R.string.work_evidence_linked else R.string.work_evidence_link_failed),
                color = if (accepted) colors.textSecondary else colors.dangerInk) }
            if (state.linksFailed) Text(stringResource(R.string.work_evidence_link_error), color = colors.dangerInk)
        }
    }
    evidenceRequest?.let { request ->
        var detail by remember(request) { mutableStateOf<String?>(null) }
        var finished by remember(request) { mutableStateOf(false) }
        LaunchedEffect(request) {
            try { detail = withContext(Dispatchers.IO) { actions.toolEvidence(sessionId, request.first, request.second) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { detail = null }
            finally { finished = true }
        }
        SyResponsiveSheet(stringResource(R.string.work_tool_details), onDismiss = { evidenceRequest = null }, scrollable = true) {
            SelectionContainer { Text(detail?.take(32_000) ?: stringResource(if (finished) R.string.work_tool_detail_missing else R.string.common_loading),
                style = DsType.small13, color = colors.text) }
            if ((detail?.length ?: 0) > 32_000) Text(stringResource(R.string.work_evidence_preview_limited), color = colors.textSecondary)
        }
    }
}
