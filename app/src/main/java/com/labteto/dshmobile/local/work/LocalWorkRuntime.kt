package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.local.tools.LocalToolActivityPhase
import com.labteto.dshmobile.local.tools.LocalToolActivityProjectionRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalSessionEventLog
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
) : com.labteto.dshmobile.uicontract.WorkReadUiPort {
    internal fun send(
        text: String,
        attachments: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment> = emptyList(),
    ): com.labteto.dshmobile.local.send.LocalSendResult = execution.send(text, attachments)

    internal fun sendWithTeam(
        text: String,
        attachments: List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment> = emptyList(),
    ): com.labteto.dshmobile.local.send.LocalSendResult = execution.sendWithTeam(text, attachments)

    internal fun editAndResendUserMessage(
        messageId: String,
        replacement: String,
    ): com.labteto.dshmobile.local.session.LocalUserMessageEditResult =
        execution.editAndResendUserMessage(messageId, replacement)

    internal fun regenerateReply(messageId: String): Boolean = execution.regenerateReply(messageId)

    override fun artifactsForUi(
        sessionId: String,
        eventLimit: Int,
    ): List<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem> {
        if (sessionId != runtimeStateStore.currentSessionId) return emptyList()
        val boundedScan = eventLimit.coerceIn(384, 4_096)
        val root = File(sessionFiles.workspace.path)
        val log = eventLogs.get(sessionId)
        val generation = log.resetGeneration
        return projectLocalWorkArtifacts(
            log.pageBeforeChronological(limit = boundedScan),
            limit = (boundedScan / 8).coerceIn(40, 100),
        ).map { observeLocalWorkArtifact(root, it.copy(logIdentity = log.historyInstanceIdentity, logGeneration = generation)) }
    }

    override fun artifactRevisionForUi(sessionId: String): com.labteto.dshmobile.local.presentation.LocalWorkHistoryRevision? {
        if (sessionId != runtimeStateStore.currentSessionId) return null
        val log = eventLogs.get(sessionId)
        return com.labteto.dshmobile.local.presentation.LocalWorkHistoryRevision(
            log.historyInstanceIdentity, log.resetGeneration, log.latestSequence())
    }

    override fun refreshArtifactFilesForUi(sessionId: String, items: List<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem>): List<com.labteto.dshmobile.local.presentation.LocalArtifactUiItem> {
        if (sessionId != runtimeStateStore.currentSessionId) return emptyList()
        val log = eventLogs.get(sessionId)
        val root = File(sessionFiles.workspace.path)
        return items.filter { artifactBelongsToLog(it, log) }.map { observeLocalWorkArtifact(root, it) }
    }

    override fun inspectArtifactForUi(sessionId: String, item: com.labteto.dshmobile.local.presentation.LocalArtifactUiItem): com.labteto.dshmobile.local.presentation.LocalArtifactUiItem? {
        if (sessionId != runtimeStateStore.currentSessionId || item.category != "file") return null
        val log = eventLogs.get(sessionId)
        val generation = log.resetGeneration
        if (!artifactBelongsToLog(item, log)) return null
        if (!sourceArtifactMatchesEvidence(log.pageAfter(item.asOfSequence - 1L, 1).singleOrNull(), item)) return null
        val root = File(sessionFiles.workspace.path)
        val before = observeLocalWorkArtifact(root, item)
        val version = localWorkFileSha256(root, item.reference) ?: return before
        val after = observeLocalWorkArtifact(root, before)
        if (before.fileObservation != after.fileObservation || log.isClosed || generation != log.resetGeneration ||
            sessionId != runtimeStateStore.currentSessionId) return null
        return after.copy(versionNow = version)
    }

    override fun historyPageForUi(sessionId: String, cursor: com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor?): com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi {
        if (sessionId != runtimeStateStore.currentSessionId) return com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi(emptyList(), null, true)
        val log = eventLogs.get(sessionId)
        val generation = log.resetGeneration
        val root = File(sessionFiles.workspace.path)
        val page = projectLocalWorkHistoryPage(log, cursor)
        val projected = page.copy(records = page.records.map { record ->
            record.copy(artifacts = record.artifacts.map { observeLocalWorkArtifact(root,
                it.copy(logIdentity = log.historyInstanceIdentity, logGeneration = generation)) })
        })
        return if (sessionId == runtimeStateStore.currentSessionId && !log.isClosed && generation == log.resetGeneration) projected
        else com.labteto.dshmobile.local.presentation.LocalWorkHistoryPageUi(emptyList(), null, true)
    }

    /** Hash/lookup preparation performs no write and does not need the foreground binding lock. */
    internal fun prepareRequirementEvidenceForUi(sessionId: String, requirementIndex: Int,
        expectedRequirement: String, artifact: com.labteto.dshmobile.local.presentation.LocalArtifactUiItem): LocalPreparedRequirementEvidence? {
        val snapshot = runtimeStateStore.state.value
        if (sessionId != runtimeStateStore.currentSessionId || snapshot.sessionId != sessionId ||
            snapshot.usageMode != LocalUsageMode.WORK || snapshot.work.todos.getOrNull(requirementIndex)?.content != expectedRequirement) return null
        val sourceCallId = artifact.sourceCallId?.takeIf(String::isNotBlank) ?: return null
        val log = eventLogs.get(sessionId)
        val generation = log.resetGeneration
        if (!artifactBelongsToLog(artifact, log)) return null
        val origin = log.pageAfter(artifact.asOfSequence - 1, 1).singleOrNull()
        val version = selectedRequirementEvidenceVersion(File(sessionFiles.workspace.path), origin, artifact) ?: return null
        if (log.isClosed || log.resetGeneration != generation) return null
        return LocalPreparedRequirementEvidence(sessionId, requirementIndex, expectedRequirement, artifact,
            sourceCallId, version, log.historyInstanceIdentity, generation, snapshot.work.goal?.id)
    }

    /** Sole reference writer. Caller freezes binding; Work rechecks its own requirement/log facts. */
    internal fun commitRequirementEvidenceForUi(prepared: LocalPreparedRequirementEvidence): Boolean {
        val current = runtimeStateStore.state.value
        if (prepared.sessionId != runtimeStateStore.currentSessionId || current.sessionId != prepared.sessionId ||
            current.usageMode != LocalUsageMode.WORK || current.work.todos.getOrNull(prepared.requirementIndex)?.content != prepared.requirement ||
            current.work.goal?.id != prepared.goalId) return false
        val log = eventLogs.get(prepared.sessionId)
        if (log.isClosed || log.historyInstanceIdentity != prepared.logIdentity || log.resetGeneration != prepared.generation) return false
        log.append("work/requirement-evidence", buildJsonObject {
            put("requirement_index", prepared.requirementIndex)
            put("requirement", prepared.requirement)
            prepared.goalId?.let { put("goal_id", it) }
            put("artifact_path", prepared.artifact.reference)
            put("origin_sequence", prepared.artifact.asOfSequence)
            put("source_call_id", prepared.sourceCallId)
            put("sha256", prepared.version)
            put("assertion", "user_selected_reference_only")
        })
        return true
    }

    /** Same authoritative EventLog across app restarts; source version checked on display. */
    override fun requirementEvidenceForUi(sessionId: String): List<LocalRequirementEvidenceLink> {
        val snapshot = runtimeStateStore.state.value
        if (sessionId != runtimeStateStore.currentSessionId ||
            snapshot.sessionId != sessionId || snapshot.usageMode != LocalUsageMode.WORK
        ) return emptyList()
        return checkRequirementEvidenceVersions(
            File(sessionFiles.workspace.path),
            eventLogs.get(sessionId).withEvents {
                projectRequirementEvidenceLinks(it, snapshot.work.todos, snapshot.work.goal?.id)
            },
        )
    }

    /** Read the authoritative EventLog revision; no parallel persisted tool activity stream. */
    override fun eventSequenceForUi(sessionId: String): Long =
        if (sessionId == runtimeStateStore.currentSessionId) eventLogs.get(sessionId).latestSequence() else -1L

    override fun toolActivitiesForUi(sessionId: String): List<LocalToolActivityUiItem> {
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
    override fun toolEvidenceForUi(sessionId: String, callId: String, sequence: Long): String? {
        if (sessionId != runtimeStateStore.currentSessionId || sequence < 0L) return null
        val event = eventLogs.get(sessionId).pageAfter(sequence - 1L, limit = 1).singleOrNull()
            ?.takeIf { it.sequence == sequence && it.data["id"]?.jsonPrimitive?.contentOrNull == callId }
            ?: return null
        return when (event.type) {
            "tool/call" -> event.data["arguments"]?.toString()
            "tool/result", "subagent/tool-result" -> event.data["content"]?.jsonPrimitive?.contentOrNull
            "tool/execution-started" -> event.data.toString()
            else -> null
        }
    }

    internal fun pendingQuestionForUi(): com.labteto.dshmobile.local.interaction.LocalQuestion? {
        val binding = workRunRegistry.live(runtimeStateStore.currentSessionId)
        return binding?.interactions?.pendingQuestionForUi()
            ?: runtimeStateStore.foregroundInteractions.pendingQuestionForUi()
    }
    internal fun answerQuestionFromUi(requestId: String, answer: String?): Boolean {
        val binding = workRunRegistry.live(runtimeStateStore.currentSessionId)
        return if (binding != null) binding.interactions.answerQuestionFromUi(requestId, answer)
        else runtimeStateStore.foregroundInteractions.answerQuestionFromUi(requestId, answer)
    }
    internal fun pendingApprovalForUi(): com.labteto.dshmobile.local.interaction.LocalApproval? {
        val binding = workRunRegistry[runtimeStateStore.currentSessionId]
        return binding?.interactions?.pendingApprovalForUi()
            ?: runtimeStateStore.foregroundInteractions.pendingApprovalForUi()
    }
    internal fun answerApproval(requestId: String, approved: Boolean): Boolean {
        val binding = workRunRegistry[runtimeStateStore.currentSessionId]
        if (binding?.interactions?.answerApprovalFromUi(requestId, approved) == true) return true
        return runtimeStateStore.foregroundInteractions.answerApprovalFromUi(requestId, approved)
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
    internal fun answerQuestion(callId: String, answer: String): Boolean {
        val binding = workRunRegistry.live(runtimeStateStore.currentSessionId)
        val accepted = if (binding != null) {
            binding.interactions.answerQuestion(callId, answer)
        } else {
            runtimeStateStore.foregroundInteractions.answerQuestion(callId, answer)
        }
        if (!accepted) runtimeStateStore.projection.publishError("当前问题已结束或会话已变化，请查看最新任务状态")
        return accepted
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
            if (!planMode.setEnabled(enabled)) {
                runtimeStateStore.projection.publishError(
                    "计划模式暂未切换：当前会话正在加载、执行任务或等待审批；请在任务可修改时重试",
                )
            }
        }
}

/** For historical results, verify access at presentation time; never trust the event path. */
internal fun localWorkArtifactFileAvailable(root: File, relative: String): Boolean = runCatching {
    val canonicalRoot = root.canonicalFile.toPath()
    val target = File(root, relative).canonicalFile.toPath()
    target != canonicalRoot && target.startsWith(canonicalRoot) && java.nio.file.Files.isRegularFile(target)
}.getOrDefault(false)

internal data class LocalPreparedRequirementEvidence(val sessionId: String, val requirementIndex: Int,
    val requirement: String, val artifact: com.labteto.dshmobile.local.presentation.LocalArtifactUiItem,
    val sourceCallId: String, val version: String, val logIdentity: String, val generation: Long,
    val goalId: String? = null)

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal abstract class LocalWorkReadUiPortBindings {
    @dagger.Binds @Singleton
    abstract fun reads(implementation: LocalWorkRuntime): com.labteto.dshmobile.uicontract.WorkReadUiPort
}

/** UI selections from before reset/reopen cannot acquire a new log's submission rights. */
private fun artifactBelongsToLog(item: com.labteto.dshmobile.local.presentation.LocalArtifactUiItem, log: LocalSessionEventLog): Boolean =
    !log.isClosed && item.logIdentity == log.historyInstanceIdentity && item.logGeneration == log.resetGeneration
