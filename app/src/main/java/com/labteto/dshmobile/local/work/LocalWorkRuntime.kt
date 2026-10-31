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
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
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

    /** Must run outside the foreground lock; a stale prepared hash cannot be accepted as current. */
    internal fun preparedEvidenceStillCurrent(prepared: LocalPreparedRequirementEvidence): Boolean =
        preparedRequirementEvidenceVersionMatches(
            File(sessionFiles.workspace.path), prepared.artifact.reference, prepared.version,
        )

    /** Sole reference writer. Caller freezes binding; Work rechecks its own requirement/log facts. */
    internal fun commitRequirementEvidenceForUi(prepared: LocalPreparedRequirementEvidence): Boolean {
        val current = runtimeStateStore.state.value
        if (prepared.sessionId != runtimeStateStore.currentSessionId || current.sessionId != prepared.sessionId ||
            current.usageMode != LocalUsageMode.WORK || current.work.todos.getOrNull(prepared.requirementIndex)?.content != prepared.requirement ||
            current.work.goal?.id != prepared.goalId) return false
        val log = eventLogs.get(prepared.sessionId)
        if (log.isClosed || log.historyInstanceIdentity != prepared.logIdentity || log.resetGeneration != prepared.generation) return false
        return log.withWriterLease {
            if (log.isClosed || log.resetGeneration != prepared.generation ||
                !preparedEvidenceStillCurrent(prepared) ||
                runtimeStateStore.state.value.work.goal?.id != prepared.goalId) return@withWriterLease false
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
            true
        }
    }

    /** Same authoritative EventLog across app restarts; source version checked on display. */
    /** The committed Session EventLog is the only revision source for Work UI updates. */
    internal fun workEventChangesForUi(sessionId: String): kotlinx.coroutines.flow.StateFlow<Long> =
        eventLogs.get(sessionId).changes

    /** Only visible, already-admitted artifact paths are watched. No filesystem polling. */
    internal fun workArtifactFileChangesForUi(sessionId: String, paths: List<String>): kotlinx.coroutines.flow.Flow<Unit> =
        channelFlow {
            if (sessionId != runtimeStateStore.currentSessionId || paths.isEmpty()) return@channelFlow
            // Re-arm inode observers after atomic file replacement and rename events.
            val rearm = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            merge(kotlinx.coroutines.flow.flowOf(Unit), rearm).collectLatest {
                watchLocalWorkEvidenceFiles(
                    File(sessionFiles.workspace.path), emptyList(), paths.take(100),
                ).collect {
                    send(Unit)
                    rearm.tryEmit(Unit)
                }
            }
        }.flowOn(Dispatchers.IO)

    /** Evidence and timeline read the same owner; no separate Work revision store. */
    internal fun requirementEvidenceChangesForUi(sessionId: String): kotlinx.coroutines.flow.StateFlow<Long> =
        workEventChangesForUi(sessionId)

    /** Exact file edits are surfaced by Android's event notifier, never a display timer. */
    internal fun requirementEvidenceFileChangesForUi(sessionId: String): kotlinx.coroutines.flow.Flow<Unit> =
        channelFlow {
            // Re-arm file-inode watches after external rename/create, in addition to
            // re-arming for newly linked evidence from the original EventLog.
            val rearm = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            merge(requirementEvidenceChangesForUi(sessionId).map { Unit }, rearm)
                .collectLatest {
                    watchLocalWorkEvidenceFiles(
                        File(sessionFiles.workspace.path),
                        requirementEvidenceIndexForUi(sessionId).links,
                        runtimeStateStore.state.value.takeIf {
                            it.sessionId == sessionId && it.usageMode == LocalUsageMode.WORK
                        }?.work?.goal?.artifactPaths.orEmpty(),
                    ).collect {
                        send(Unit)
                        rearm.tryEmit(Unit)
                    }
                }
        }.flowOn(Dispatchers.IO)

    /** Exactly one authoritative projection for links and manual conclusions. */
    internal fun requirementEvidenceIndexForUi(sessionId: String): IndexedRequirementEvidence {
        val snapshot = runtimeStateStore.state.value
        if (sessionId != runtimeStateStore.currentSessionId ||
            snapshot.sessionId != sessionId || snapshot.usageMode != LocalUsageMode.WORK)
            return IndexedRequirementEvidence(emptyList(), emptyList(), emptyList(), 0)
        val indexed = indexedRequirementEvidence(
            eventLogs.get(sessionId), snapshot.work.todos, snapshot.work.goal?.id,
        )
        return indexed.copy(links = checkRequirementEvidenceVersions(
            File(sessionFiles.workspace.path), indexed.links,
        ))
    }

    override fun requirementEvidenceForUi(sessionId: String): List<LocalRequirementEvidenceLink> =
        requirementEvidenceIndexForUi(sessionId).links

    /** Read-only independent machine check against the existing Goal acceptance contract. */
    internal fun machineEvidenceForUi(sessionId: String):
        com.labteto.dshmobile.uicontract.UiWorkMachineVerification? {
        val current = runtimeStateStore.state.value
        val goal = current.work.goal ?: return null
        if (current.sessionId != sessionId ||
            current.usageMode != LocalUsageMode.WORK ||
            runtimeStateStore.currentSessionId != sessionId) return null
        val log = eventLogs.get(sessionId)
        val sequence = log.latestSequence()
        val delivery = localWorkDeliveryEvidence(log, goal, File(sessionFiles.workspace.path))
        if (log.isClosed || log.latestSequence() != sequence ||
            runtimeStateStore.state.value.work.goal != goal) return null
        return projectLocalWorkMachineVerification(goal, delivery, sequence)
    }

    /** Manual review is a separate, user-owned event and never a Work completion claim. */
    internal fun requirementEvidenceReviewsForUi(sessionId: String): List<LocalRequirementEvidenceReview> =
        requirementEvidenceIndexForUi(sessionId).reviews

    internal fun prepareRequirementEvidenceReviewForUi(
        sessionId: String, goalId: String, requirementIndex: Int, expectedRequirement: String,
        artifactPath: String, linkSequence: Long, version: String,
    ): LocalPreparedRequirementEvidenceReview? {
        val current = runtimeStateStore.state.value
        if (goalId.isBlank() || current.sessionId != sessionId ||
            sessionId != runtimeStateStore.currentSessionId ||
            current.usageMode != LocalUsageMode.WORK || current.work.goal?.id != goalId ||
            current.work.todos.getOrNull(requirementIndex)?.content != expectedRequirement)
            return null
        val log = eventLogs.get(sessionId)
        val logIdentity = log.historyInstanceIdentity
        val generation = log.resetGeneration
        val links = requirementEvidenceForUi(sessionId)
        val link = links.singleOrNull {
            it.eventSequence == linkSequence && it.requirementIndex == requirementIndex &&
                it.requirement == expectedRequirement && it.artifactPath == artifactPath &&
                it.versionAtLink == version && it.versionNow == version
        } ?: return null
        val observedSequence = log.latestSequence()
        if (log.isClosed || generation != log.resetGeneration) return null
        return LocalPreparedRequirementEvidenceReview(
            sessionId, goalId, requirementIndex, expectedRequirement,
            link.artifactPath, link.eventSequence, link.versionAtLink,
            logIdentity, generation, observedSequence,
        )
    }

    internal fun preparedReviewVersionStillCurrent(prepared: LocalPreparedRequirementEvidenceReview): Boolean =
        preparedRequirementEvidenceVersionMatches(
            File(sessionFiles.workspace.path), prepared.artifactPath, prepared.version,
        )

    /** Caller holds the foreground binding lock; log version fences other edits/relinks. */
    internal fun commitRequirementEvidenceReviewForUi(
        prepared: LocalPreparedRequirementEvidenceReview, approved: Boolean,
    ): Boolean {
        val current = runtimeStateStore.state.value
        if (current.sessionId != prepared.sessionId ||
            runtimeStateStore.currentSessionId != prepared.sessionId ||
            current.usageMode != LocalUsageMode.WORK || current.work.goal?.id != prepared.goalId ||
            current.work.todos.getOrNull(prepared.requirementIndex)?.content != prepared.requirement)
            return false
        val log = eventLogs.get(prepared.sessionId)
        if (log.isClosed || log.historyInstanceIdentity != prepared.logIdentity ||
            log.resetGeneration != prepared.generation) return false
        return log.withWriterLease {
            if (log.isClosed || log.resetGeneration != prepared.generation ||
                log.latestSequence() != prepared.observedSequence ||
                !preparedReviewVersionStillCurrent(prepared) ||
                runtimeStateStore.state.value.work.goal?.id != prepared.goalId)
                return@withWriterLease false
            log.append("work/requirement-evidence-review", buildJsonObject {
            put("goal_id", prepared.goalId)
            put("requirement_index", prepared.requirementIndex)
            put("requirement", prepared.requirement)
            put("artifact_path", prepared.artifactPath)
            put("link_sequence", prepared.linkSequence)
            put("sha256", prepared.version)
            put("approved_by_user", approved)
            put("assertion", "manual_content_review_only")
            })
            true
        }
    }

    internal fun requirementEvidenceTextChecksForUi(sessionId: String): List<LocalRequirementEvidenceTextCheck> =
        requirementEvidenceIndexForUi(sessionId).textChecks

    internal data class PreparedEvidenceTextCheck(
        val reference: LocalPreparedRequirementEvidenceReview,
        val needleSha256: String,
        val matched: Boolean,
    )

    internal fun prepareEvidenceTextCheckForUi(
        sessionId: String, goalId: String, index: Int, requirement: String,
        path: String, linkSequence: Long, version: String, requiredText: String,
    ): PreparedEvidenceTextCheck? {
        if (requiredText.isBlank() || requiredText.length > 512) return null
        val proof = prepareRequirementEvidenceReviewForUi(
            sessionId, goalId, index, requirement, path, linkSequence, version) ?: return null
        val matched = inspectLocalWorkEvidenceText(
            File(sessionFiles.workspace.path), path, version, requiredText) ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(requiredText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return PreparedEvidenceTextCheck(proof, digest, matched)
    }

    /** The result concerns one literal condition and one observed byte version only. */
    internal fun commitEvidenceTextCheckForUi(value: PreparedEvidenceTextCheck): Boolean {
        val proof = value.reference
        val snapshot = runtimeStateStore.state.value
        if (snapshot.sessionId != proof.sessionId ||
            runtimeStateStore.currentSessionId != proof.sessionId ||
            snapshot.usageMode != LocalUsageMode.WORK || snapshot.work.goal?.id != proof.goalId ||
            snapshot.work.todos.getOrNull(proof.requirementIndex)?.content != proof.requirement)
            return false
        val log = eventLogs.get(proof.sessionId)
        if (log.isClosed || log.historyInstanceIdentity != proof.logIdentity ||
            log.resetGeneration != proof.generation) return false
        return log.withWriterLease {
            if (log.isClosed || log.resetGeneration != proof.generation ||
                log.latestSequence() != proof.observedSequence ||
                !preparedReviewVersionStillCurrent(proof) ||
                runtimeStateStore.state.value.work.goal?.id != proof.goalId)
                return@withWriterLease false
            log.append("work/requirement-evidence-text-check", buildJsonObject {
            put("goal_id", proof.goalId)
            put("requirement_index", proof.requirementIndex)
            put("artifact_path", proof.artifactPath)
            put("link_sequence", proof.linkSequence)
            put("sha256", proof.version)
            put("condition_sha256", value.needleSha256)
            put("contains_required_text", value.matched)
            put("assertion", "utf8_contains_only_not_work_completion")
            })
            true
        }
    }

    override fun planForUi(sessionId: String): com.labteto.dshmobile.uicontract.UiWorkPlanSnapshot? {
        val current = runtimeStateStore.state.value
        if (current.sessionId != sessionId || current.usageMode != LocalUsageMode.WORK) return null
        return com.labteto.dshmobile.uicontract.UiWorkPlanSnapshot(
            sessionId, current.work.plan.toList(),
            current.work.todos.map { com.labteto.dshmobile.uicontract.UiWorkPlanStep(it.content, it.status) },
            current.work.planMode,
        )
    }

    /** Read the authoritative EventLog revision; no parallel persisted tool activity stream. */
    override fun eventSequenceForUi(sessionId: String): Long =
        if (sessionId == runtimeStateStore.currentSessionId) eventLogs.get(sessionId).latestSequence() else -1L

    override fun toolActivitiesForUi(sessionId: String): List<LocalToolActivityUiItem> {
        if (sessionId != runtimeStateStore.currentSessionId) return emptyList()
        val activities = toolActivity.snapshot(eventLogs.get(sessionId)).state.activities
        // Every unfinished call remains visible; completed calls only need a recent window,
        // since durable transcript owns the full chronological history.
        val recent = activities.filter {
            it.phase != LocalToolActivityPhase.DECLARED && it.phase != LocalToolActivityPhase.RUNNING
        }.takeLast(32)
        val unfinished = activities.filter {
            it.phase == LocalToolActivityPhase.DECLARED || it.phase == LocalToolActivityPhase.RUNNING
        }
        return (unfinished + recent).sortedBy { it.declaredSequence ?: Long.MAX_VALUE }.map { activity ->
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
                authorizationGranted = activity.authorizationGranted,
                authorizationSequence = activity.authorizationSequence,
                declaredAt = activity.declaredAt,
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
            "tool/execution-started", "tool/authorization-decision" -> event.data.toString()
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
        if (binding?.interactions?.answerQuestionFromUi(requestId, answer) == true) return true
        return runtimeStateStore.foregroundInteractions.answerQuestionFromUi(requestId, answer)
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

internal data class LocalPreparedRequirementEvidenceReview(
    val sessionId: String, val goalId: String, val requirementIndex: Int,
    val requirement: String, val artifactPath: String, val linkSequence: Long,
    val version: String, val logIdentity: String, val generation: Long,
    val observedSequence: Long,
)

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
