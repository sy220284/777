package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.local.tools.LocalToolActivityPhase
import com.labteto.dshmobile.local.tools.LocalToolActivityProjectionRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.session.LocalSessionFilesRuntime
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Work/interaction capability boundary for the local UI. */
@Singleton
class LocalWorkRuntime @Inject internal constructor(
    private val workRunRegistry: LocalWorkRunRegistry,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val eventLogs: LocalSessionEventLogRegistry,
    private val sessionFiles: LocalSessionFilesRuntime,
    private val toolActivity: LocalToolActivityProjectionRuntime,
    private val approvals: LocalWorkApprovalCoordinator,
    private val planMode: LocalWorkPlanModeCoordinator,
    private val execution: LocalWorkExecutionPort,
    private val agentUi: LocalWorkAgentUiPort,
) {
    internal fun send(
        text: String,
        attachments: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment> = emptyList(),
    ): com.labteto.dshmobile.local.send.LocalSendResult = execution.send(text, attachments)

    internal fun sendWithTeam(
        text: String,
        attachments: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment> = emptyList(),
    ): com.labteto.dshmobile.local.send.LocalSendResult = execution.sendWithTeam(text, attachments)

    internal fun regenerateReply(messageId: String): Boolean = execution.regenerateReply(messageId)

    internal fun artifactsForUi(
        sessionId: String,
        eventLimit: Int = 384,
    ): List<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem> {
        if (sessionId != runtimeStateStore.currentSessionId) return emptyList()
        val boundedScan = eventLimit.coerceIn(384, 4_096)
        val root = File(sessionFiles.workspace.path)
        return projectLocalWorkArtifacts(
            eventLogs.get(sessionId).pageBeforeChronological(limit = boundedScan),
            limit = (boundedScan / 8).coerceIn(40, 100),
        ).map { item ->
            if (item.category == "file") item.copy(
                currentlyAvailable = localWorkArtifactFileAvailable(root, item.reference),
            ) else item
        }
    }

    internal fun historyPageForUi(sessionId: String, cursor: com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor?): com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi {
        if (sessionId != runtimeStateStore.currentSessionId) return com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi(emptyList(), null, true)
        val page = projectLocalWorkHistoryPage(eventLogs.get(sessionId), cursor)
        return if (sessionId == runtimeStateStore.currentSessionId) page
        else com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi(emptyList(), null, true)
    }

    /** Read the authoritative EventLog revision; no parallel persisted tool activity stream. */
    internal fun eventSequenceForUi(sessionId: String): Long =
        if (sessionId == runtimeStateStore.currentSessionId) eventLogs.get(sessionId).latestSequence() else -1L

    internal fun toolActivitiesForUi(sessionId: String): List<LocalToolActivityUiItem> {
        if (sessionId != runtimeStateStore.currentSessionId) return emptyList()
        return toolActivity.snapshot(eventLogs.get(sessionId)).state.activities.takeLast(32).map { activity ->
            val phase = when (activity.phase) {
                LocalToolActivityPhase.DECLARED -> LocalToolUiPhase.DECLARED
                LocalToolActivityPhase.RUNNING -> LocalToolUiPhase.RUNNING
                LocalToolActivityPhase.COMPLETED -> LocalToolUiPhase.COMPLETED
                LocalToolActivityPhase.FAILED -> LocalToolUiPhase.FAILED
                LocalToolActivityPhase.OUTCOME_UNKNOWN -> LocalToolUiPhase.OUTCOME_UNKNOWN
                LocalToolActivityPhase.CANCELLED -> LocalToolUiPhase.CANCELLED
            }
            LocalToolActivityUiItem(
                callId = activity.callId,
                name = activity.name,
                phase = phase,
                errorCode = activity.errorCode,
                executionId = activity.executionId,
                declaredSequence = activity.declaredSequence,
                startedSequence = activity.startedSequence,
                finishedSequence = activity.finishedSequence,
                argumentsPreview = activity.argumentsPreview,
                resultPreview = activity.resultPreview,
            )
        }
    }

    /** Exact bounded lookup; the selected event must still belong to this session and call. */
    internal fun toolEvidenceForUi(sessionId: String, callId: String, sequence: Long): String? {
        if (sessionId != runtimeStateStore.currentSessionId || sequence <= 0L) return null
        val event = eventLogs.get(sessionId).pageAfter(sequence - 1L, limit = 1).singleOrNull()
            ?.takeIf { it.sequence == sequence && it.data["id"]?.jsonPrimitive?.contentOrNull == callId }
            ?: return null
        return when (event.type) {
            "tool/call" -> event.data["arguments"]?.toString()
            "tool/result" -> event.data["content"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    internal fun backgroundJobOutputForUi(jobId: String): String =
        runtimeStateStore.jobManager.output(jobId, runtimeStateStore.currentSessionId)
    internal fun stopBackgroundJobForUi(jobId: String): String =
        runtimeStateStore.jobManager.kill(jobId, runtimeStateStore.currentSessionId)
    internal suspend fun startBackgroundAgentForUi(task: String): LocalWorkAgentUiResult =
        agentUi.startBackgroundAgent(task)
    internal suspend fun startResearchAgentForUi(task: String): LocalWorkAgentUiResult =
        agentUi.startResearchAgent(task)
    internal suspend fun sendBackgroundAgentMessageForUi(agentId: String, message: String): LocalWorkAgentUiResult =
        agentUi.sendMessage(agentId, message)
    internal suspend fun sendTeamMemberMessageForUi(memberId: String, message: String): LocalWorkAgentUiResult =
        agentUi.sendTeamMessage(memberId, message)
    internal suspend fun stopTeamMemberForUi(memberId: String): LocalWorkAgentUiResult =
        agentUi.stopTeamMember(memberId)
    internal suspend fun stopTeamForUi(): LocalWorkAgentUiResult =
        agentUi.stopTeam()
    internal fun answerApproval(callId: String, approved: Boolean) {
        val binding = workRunRegistry[runtimeStateStore.currentSessionId]
        if (binding?.interactions?.answerApproval(callId, approved) == true) return
        runtimeStateStore.foregroundInteractions.answerApproval(callId, approved)
    }
    internal fun useDefaultApproval() =
        runtimeStateStore.performVisibleOperation("审批设置保存失败") { approvals.useDefaultApproval() }
    internal fun enableAutoApproval() =
        runtimeStateStore.performVisibleOperation("审批设置保存失败") { approvals.enableAutoApproval() }
    internal fun enableAutoApprovalForPending(callId: String) =
        runtimeStateStore.performVisibleOperation("审批设置保存失败") { approvals.enableAutoApprovalForPending(callId) }
    internal fun enableDeviceApprovalLease(callId: String) =
        runtimeStateStore.performVisibleOperation("审批设置保存失败") { approvals.enableDeviceApprovalLease(callId) }
    internal fun disableDeviceApprovalLease() =
        runtimeStateStore.performVisibleOperation("设备授权撤销保存失败") {
            approvals.disableDeviceApprovalLease(runtimeStateStore.state.value.sessionId)
        }
    internal fun disableAutoApproval() =
        runtimeStateStore.performVisibleOperation("审批设置保存失败") { approvals.disableAutoApproval() }
    internal fun answerQuestion(callId: String, answer: String) {
        val binding = workRunRegistry[runtimeStateStore.currentSessionId]
        if (binding?.interactions?.answerQuestion(callId, answer) == true) return
        runtimeStateStore.foregroundInteractions.answerQuestion(callId, answer)
    }
    internal fun cancelQuestion(callId: String) {
        val binding = workRunRegistry[runtimeStateStore.currentSessionId]
        if (binding?.interactions?.cancelQuestion(callId) == true) return
        runtimeStateStore.foregroundInteractions.cancelQuestion(callId)
    }
    internal fun stop() = runtimeStateStore.performVisibleOperation("停止任务时保存失败") {
        val sessionId = runtimeStateStore.currentSessionId
        val snapshot = runtimeStateStore.state.value
        if (snapshot.sessionId != sessionId || snapshot.usageMode != LocalUsageMode.WORK) return@performVisibleOperation
        if (workRunRegistry.requestCancel(sessionId)) return@performVisibleOperation

        runtimeStateStore.cancelForegroundRun(eventLogs.get(sessionId))
    }

    internal fun setPlanMode(enabled: Boolean) =
        runtimeStateStore.performVisibleOperation("计划模式切换失败") {
            planMode.setEnabled(enabled)
        }
}

/** For historical results, verify access at presentation time; never trust the event path. */
internal fun localWorkArtifactFileAvailable(root: File, relative: String): Boolean = runCatching {
    val canonicalRoot = root.canonicalFile.toPath()
    val target = File(root, relative).canonicalFile.toPath()
    target != canonicalRoot && target.startsWith(canonicalRoot) && java.nio.file.Files.isRegularFile(target)
}.getOrDefault(false)
