package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.jobs.JobMessageAdmission
import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.harness.jobs.JobStartResult
import com.labteto.dshmobile.harness.jobs.JobExecutionSliceBoundaryException
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.agent.requireCompletedOutput
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.LocalModelRunContext
import com.labteto.dshmobile.local.model.routeFingerprint
import com.labteto.dshmobile.local.runtime.BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS
import com.labteto.dshmobile.local.runtime.DEFAULT_WEB_FETCH_BYTES
import com.labteto.dshmobile.local.runtime.MAX_WEB_FETCH_BYTES
import com.labteto.dshmobile.local.runtime.PERSISTENT_RECOVERY_RETRY_MILLIS
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.LocalAgentDelegationKind
import com.labteto.dshmobile.local.agent.buildLocalDelegationCapabilities
import com.labteto.dshmobile.local.agent.modelMessageForAgentInput
import com.labteto.dshmobile.local.agent.captureLocalAgentParentLink
import com.labteto.dshmobile.local.agent.decodeLocalAgentParentLink
import com.labteto.dshmobile.local.agent.toJson
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import com.labteto.dshmobile.local.agent.LocalSubagentLaunchSpec
import com.labteto.dshmobile.local.agent.validateLocalSubagentLaunchSpec
import com.labteto.dshmobile.local.agent.encodeLocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.decodeLocalSubagentCapabilities
import com.labteto.dshmobile.local.web.LocalWebTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Identify the authoritative session before interpreting potentially corrupted recovery payloads.
 * Older ownerless records may still be resumed when the payload explicitly names the session.
 * An undecodable ownerless payload is left intact for manual inspection, not reassigned.
 */
internal fun isJobRecoveryOwnedBySession(snapshot: JobSnapshot, targetSession: String, json: Json): Boolean {
    snapshot.ownerId?.let { return it == targetSession }
    val serialized = snapshot.resumePayload ?: return false
    return runCatching {
        json.parseToJsonElement(serialized).jsonObject["session_id"]?.jsonPrimitive?.contentOrNull ==
            targetSession
    }.getOrDefault(false)
}

/** An interrupted action may already have performed irreversible work. */
internal fun mustPreserveUnknownRecoveryOutcome(snapshot: JobSnapshot): Boolean =
    snapshot.status == "interrupted"

/** Session-level recovery admits every eligible Job; explicit Agent resume admits only its ID. */
internal fun matchesRequestedAgentRecovery(jobId: String, requestedAgentId: String?): Boolean =
    requestedAgentId == null || jobId == requestedAgentId

internal class LocalPersistentJobRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val workspaceRoot: java.io.File,
    private val jobs: LocalJobManager,
    private val json: Json,
    private val modelGateway: LocalModelGateway,
    private val webTools: LocalWebTools,
    private val currentSessionId: () -> String,
    private val currentState: () -> LocalHarnessState,
    private val defaultHistory: () -> List<JsonObject>,
    private val stateForSession: (String) -> LocalHarnessState?,
    private val historyForSession: (String) -> List<JsonObject>?,
    private val subagentRunner: (
        sessionId: String,
        state: LocalHarnessState,
        history: () -> List<JsonObject>,
        workspace: com.labteto.dshmobile.local.files.LocalWorkspaceExecutionContext?,
    ) -> LocalSubagentRunner,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val webToolsForWorkspace: suspend (String, String) -> LocalWebTools,
    private val createWorkspace: suspend (String, String, String) -> JsonObject,
    private val resolveWorkspace: suspend (String, String) -> com.labteto.dshmobile.local.files.LocalWorkspaceExecutionContext,
    private val inspectWorkspace: suspend (String, String) -> JsonObject,
    private val mergeWorkspace: suspend (String, String, String, List<String>, suspend () -> Unit) -> JsonObject,
) {
    private val lock = Any()
    private val pendingRecoverySessions = linkedSetOf<String>()
    private val rootAdmittedSessions = linkedSetOf<String>()
    /** Explicit per-Agent resumes stay distinct from session-wide crash recovery. */
    private val pendingTargetedAgents = linkedSetOf<Pair<String, String>>()
    private var recoveryJob: Job? = null

    suspend fun startReadonlySubagent(
        task: String,
        model: String?,
        maxSteps: Int,
        instructions: String = "",
        virtualScreen: Boolean,
        outputSchema: JsonObject? = null,
        toolAllowlist: Set<String>? = null,
        forkParentCallId: String? = null,
        parentToolCallId: String? = null,
        sessionId: String = currentSessionId(),
        boundState: LocalHarnessState = currentState(),
        historySnapshot: () -> List<JsonObject> = defaultHistory,
        requestedJobId: String? = null,
        backendCapabilities: com.labteto.dshmobile.local.agent.LocalSubagentCapabilities? = null,
    ): String = startReadonlySubagentResult(
        task = task,
        model = model,
        maxSteps = maxSteps,
        instructions = instructions,
        virtualScreen = virtualScreen,
        outputSchema = outputSchema,
        toolAllowlist = toolAllowlist,
        forkParentCallId = forkParentCallId,
        parentToolCallId = parentToolCallId,
        sessionId = sessionId,
        boundState = boundState,
        historySnapshot = historySnapshot,
        requestedJobId = requestedJobId,
        backendCapabilities = backendCapabilities,
    ).message

    suspend fun startReadonlySubagentResult(
        task: String,
        model: String?,
        maxSteps: Int,
        instructions: String = "",
        virtualScreen: Boolean,
        outputSchema: JsonObject? = null,
        toolAllowlist: Set<String>? = null,
        forkParentCallId: String? = null,
        parentToolCallId: String? = null,
        sessionId: String = currentSessionId(),
        boundState: LocalHarnessState = currentState(),
        historySnapshot: () -> List<JsonObject> = defaultHistory,
        requestedJobId: String? = null,
        backendCapabilities: com.labteto.dshmobile.local.agent.LocalSubagentCapabilities? = null,
        allowMutation: Boolean = false,
        teamManaged: Boolean = false,
        initialOptionalTools: Set<String> = emptySet(),
        workflowFence: JsonObject? = null,
        workspaceRepository: String? = null,
    ): JobStartResult {
        val runProfile = modelGateway.profileForRun(
            model ?: boundState.modelState.modelSelection.activeProfileId,
        )
        val protocol = effectiveProtocol(runProfile)
        val parentLink = captureLocalAgentParentLink(parentToolCallId ?: forkParentCallId)
        require(parentLink == null || parentLink.sessionId == sessionId) { "AGENT_PARENT_SESSION_MISMATCH" }
        val selectedBackend = backendCapabilities
            ?: currentCoroutineContext()[com.labteto.dshmobile.local.agent.LocalAgentBackendSelectionContext]?.capabilities
        val capabilities = buildLocalDelegationCapabilities(
            kind = if (teamManaged) LocalAgentDelegationKind.TEAM_MEMBER
                else if (workflowFence != null) LocalAgentDelegationKind.WORKFLOW_NODE
                else LocalAgentDelegationKind.PERSISTENT,
            allowMutation = allowMutation,
            virtualScreen = virtualScreen,
            toolAllowlist = toolAllowlist,
            outputSchema = outputSchema,
            initialOptionalTools = initialOptionalTools,
        ).copy(
            executionBackend = selectedBackend?.executionBackend ?: com.labteto.dshmobile.local.agent.LocalAgentExecutionBackend.IN_PROCESS,
            remoteBackendRevision = selectedBackend?.remoteBackendRevision,
        )
        validateLocalSubagentLaunchSpec(
            LocalSubagentLaunchSpec(
                task = task,
                instructions = instructions,
                modelOverride = runProfile.id,
                maxSteps = maxSteps,
                backgroundJobId = "pending",
                capabilities = capabilities,
            ),
            structuredOutputSupported = true,
        )
        val workspaceId = workspaceRepository?.let { repository ->
            val stableId = requireNotNull(requestedJobId) { "持久独立工作区需要稳定 Job 编号" }
            createWorkspace(sessionId, stableId, repository).getValue("workspace_id").jsonPrimitive.content
        }
        val workspaceBinding = workspaceId?.let { resolveWorkspace(sessionId, it) }
        if (workspaceBinding != null && workflowFence != null) requireWorkflowWorkspaceInputs(workspaceBinding, workflowFence)
        val runner = subagentRunner(sessionId, boundState, historySnapshot, workspaceBinding)
        val initialHistorySeed = forkParentCallId?.let { parentCallId ->
            val jobId = requestedJobId
                ?: error("TEAM_FORK_REQUIRES_STABLE_JOB_ID：持久 fork 必须预分配稳定 jobId")
            val seed = runner.prepareForkSeed(
                task = task,
                parentCallId = parentCallId,
                outputSchema = outputSchema,
                instructions = instructions,
                allowMutation = capabilities.allowMutation,
            )
            eventLogFor(sessionId).append(
                LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
                encodeLocalSubagentHistoryCheckpoint(
                    backgroundJobId = jobId,
                    agentId = localPersistentSubagentId(jobId),
                    step = 0,
                    history = seed,
                    claimedMessageIds = emptySet(),
                ),
            )
            seed
        }
        val payload = buildJsonObject {
            put("version", PERSISTENT_SUBAGENT_RESUME_VERSION)
            put("session_id", sessionId)
            put("task", task)
            if (instructions.isNotBlank()) put("instructions", instructions)
            put("profile_id", runProfile.id)
            put("model", runProfile.model)
            put("base_url", runProfile.baseUrl)
            put("auth_kind", runProfile.authKind.name)
            put("protocol", protocol)
            runProfile.credentialRef?.let { put("credential_ref", it) }
            put("route_fingerprint", runProfile.routeFingerprint())
            put("max_steps", maxSteps)
            forkParentCallId?.let { put("parent_call_id", it) }
            parentLink?.let { put("parent_link", it.toJson()) }
            put("capabilities", encodeLocalSubagentCapabilities(capabilities))
            workflowFence?.let { put("workflow_fence", it) }
            workspaceId?.let { put("workspace_id", it) }
        }.toString()
        return jobs.startPersistentResult(
            label = "子代理：${task.take(100)}",
            resumeKind = "subagent_readonly",
            resumePayload = payload,
            ownerSessionId = sessionId,
            continuable = true,
            requestedId = requestedJobId,
        ) { jobId, report ->
            if (teamManaged) awaitTeamMemberSpawnCommit(eventLogFor(sessionId), sessionId, jobId)
            suspendWorkflowJobForNewUser(eventLogFor(sessionId), jobId, workflowFence != null, allowMutation, teamManaged)  {
              withContext((workspaceBinding ?: kotlin.coroutines.EmptyCoroutineContext) + LocalModelRunContext(runProfile) + LocalWorkflowExecutionFence {
                if (workflowFence != null) requireWorkflowFenceCurrent(sessionId, workflowFence)
              }) {
                runner.runResult(
                    spec = LocalSubagentLaunchSpec(
                        task = task,
                        instructions = instructions,
                        modelOverride = runProfile.id,
                        maxSteps = maxSteps,
                        parentCallId = forkParentCallId,
                        parentLink = parentLink,
                        backgroundJobId = jobId,
                        capabilities = capabilities,
                    ),
                    recoveredHistory = initialHistorySeed,
                    executionFence = {
                        if (workflowFence != null) requireWorkflowFenceCurrent(sessionId, workflowFence)
                    },
                    onCheckpointProgress = { step, totalLimit ->
                        report("子代理执行进度：$step/$totalLimit")
                    },
                ).requireCompletedOutput().also { persistWorkflowOutput(sessionId, jobId, it, workflowFence) }
              }
            }
        }
    }

    fun sendMessage(
        agentId: String,
        message: String,
        sessionId: String,
        mode: LocalAgentMessageDeliveryMode = LocalAgentMessageDeliveryMode.TRIGGER_TURN,
    ): String {
        val input = QueuedAgentInput(
            id = "msg-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16),
            content = message,
            memoryInput = message,
        )
        val admission = sendInput(agentId, input, sessionId, mode)
        return renderLocalAgentMessageSubmission(
            messageId = input.id,
            mode = mode,
            accepted = admission.accepted,
            duplicate = admission.duplicate,
            requiresResume = admission.requiresResume,
            rejection = admission.message.takeIf { !admission.accepted },
        )
    }

    fun sendInput(
        agentId: String,
        input: QueuedAgentInput,
        sessionId: String,
        mode: LocalAgentMessageDeliveryMode = LocalAgentMessageDeliveryMode.TRIGGER_TURN,
    ): JobMessageAdmission {
        val result = jobs.sendInput(agentId, input, sessionId)
        addressedAgentWakeTarget(
            agentId, mode, result.accepted, result.requiresResume, result.duplicate,
        )?.let { recipient ->
            // TriggerTurn wakes only this recipient through the existing guarded
            // recovery path. QueueOnly and duplicate deliveries remain passive.
            scheduleAgentResume(sessionId, recipient)
        }
        return result
    }

    /**
     * Request only the selected Agent's recovery. Never wake unrelated Jobs in the same Session.
     * Validation of membership and resumability belongs to the AgentControl caller; this path
     * still uses the original route, checkpoint, side-effect and ownership guards.
     */
    fun scheduleAgentResume(sessionId: String, agentId: String) {
        require(sessionId.isNotBlank() && agentId.isNotBlank()) { "AGENT_RESUME_TARGET_REQUIRED" }
        synchronized(lock) {
            pendingTargetedAgents += sessionId to agentId
            startRecoveryDrainLocked()
        }
    }

    fun schedule(
        sessionId: String = currentSessionId(),
        rootSliceAdmitted: Boolean = false,
    ) {
        val targetSession = sessionId.trim()
        if (targetSession.isEmpty()) return
        synchronized(lock) {
            pendingRecoverySessions += targetSession
            if (rootSliceAdmitted) rootAdmittedSessions += targetSession
            startRecoveryDrainLocked()
        }
    }

    private fun startRecoveryDrainLocked() {
        if (recoveryJob?.isActive == true) return
        recoveryJob = scope.launch {
                try {
                    while (true) {
                        val next = synchronized(lock) {
                            val targeted = pendingTargetedAgents.firstOrNull()
                                ?.also { pendingTargetedAgents.remove(it) }
                            if (targeted != null) {
                                Triple(targeted.first, true, targeted.second)
                            } else {
                                val session = pendingRecoverySessions.firstOrNull()
                                    ?.also { pendingRecoverySessions.remove(it) }
                                session?.let {
                                    Triple(it, rootAdmittedSessions.remove(it), null)
                                }
                            }
                        } ?: break
                        resumePass(next.first, resumeSlicePaused = next.second, onlyAgentId = next.third)
                    }
                } finally {
                    val completed = currentCoroutineContext()[Job]
                    val restart = synchronized(lock) {
                        if (recoveryJob === completed) recoveryJob = null
                        recoveryJob == null &&
                            (pendingRecoverySessions.isNotEmpty() || pendingTargetedAgents.isNotEmpty())
                    }
                    if (restart) {
                        val session = synchronized(lock) { pendingRecoverySessions.firstOrNull() }
                        if (session != null) schedule(session)
                        else synchronized(lock) { pendingTargetedAgents.firstOrNull() }
                            ?.let { scheduleAgentResume(it.first, it.second) }
                    }
                }
            }
    }

    private fun resumePass(
        targetSession: String,
        resumeSlicePaused: Boolean = false,
        onlyAgentId: String? = null,
    ) {
        jobs.resumableSnapshots().forEach { snapshot ->
            if (!matchesRequestedAgentRecovery(snapshot.id, onlyAgentId)) return@forEach
            // Verify identity before decoding, failing, or reclassifying a recovery record.
            if (!isJobRecoveryOwnedBySession(snapshot, targetSession, json)) return@forEach
            // Startup/UI rehydration cannot grant new model request slots.
            if (!canResumeSubagentAtRootSlice(snapshot.status, resumeSlicePaused)) return@forEach
            // A newer/unknown recovery protocol must not reinterpret a potentially
            // completed external action as business failure or replay it automatically.
            if (snapshot.resumeKind !in setOf("web_fetch", "subagent_readonly")) {
                markRecoveryUnavailable(
                    snapshot, "不支持的恢复类型 ${snapshot.resumeKind.orEmpty()}",
                )
                return@forEach
            }
            val payload = decodePayload(snapshot, targetSession) ?: return@forEach
            val sessionId = payload["session_id"]?.jsonPrimitive?.contentOrNull ?: targetSession
            if (sessionId != targetSession || jobs.availableSlots() <= 0) return@forEach
            try {
                when (snapshot.resumeKind) {
                    "web_fetch" -> resumeWebFetch(snapshot, sessionId, payload)
                    "subagent_readonly" -> resumeSubagent(snapshot, sessionId, payload)
                    else -> error("恢复类型预校验与执行分支不一致")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                markRecoveryUnavailable(
                    snapshot, "任务无法恢复：${error.message.orEmpty().take(500)}",
                )
                recordError(snapshot, sessionId, error)
            }
        }

        val waitingForActivationStop = jobs.pendingContinuableAgentMessageSnapshots().any { snapshot ->
            snapshot.ownerId == targetSession &&
                matchesRequestedAgentRecovery(snapshot.id, onlyAgentId) &&
                snapshot.status == "interrupted"
        }
        if (waitingForActivationStop) {
            scope.launch {
                delay(PERSISTENT_RECOVERY_RETRY_MILLIS)
                if (onlyAgentId == null) schedule(targetSession)
                else scheduleAgentResume(targetSession, onlyAgentId)
            }
        }
    }

    private fun markRecoveryUnavailable(snapshot: JobSnapshot, detail: String) {
        if (mustPreserveUnknownRecoveryOutcome(snapshot)) {
            jobs.markRecoveryUnverifiable(snapshot.id, detail)
        } else {
            jobs.failResumable(snapshot.id, detail)
        }
    }

    private fun decodePayload(snapshot: JobSnapshot, fallbackSessionId: String): JsonObject? {
        val text = snapshot.resumePayload
        if (text.isNullOrBlank()) {
            markRecoveryUnavailable(snapshot, "任务恢复失败：缺少恢复元数据")
            return null
        }
        return try {
            json.parseToJsonElement(text).jsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            markRecoveryUnavailable(snapshot, "任务恢复失败：恢复元数据损坏")
            recordError(snapshot, fallbackSessionId, error)
            null
        }
    }

    private fun resumeWebFetch(snapshot: JobSnapshot, sessionId: String, payload: JsonObject) {
        val url = payload["url"]?.jsonPrimitive?.contentOrNull ?: error("恢复任务缺少 url")
        val maxBytes = payload["max_bytes"]?.jsonPrimitive?.intOrNull ?: DEFAULT_WEB_FETCH_BYTES
        val format = payload["format"]?.jsonPrimitive?.contentOrNull ?: "text"
        val timeout = payload["timeout_seconds"]?.jsonPrimitive?.contentOrNull
            ?.toLongOrNull() ?: BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS
        jobs.resumePersistent(snapshot.id, ownerSessionId = sessionId) { _, report ->
            report("正在恢复网页抓取：$url")
            val selected = payload["workspace_id"]?.jsonPrimitive?.contentOrNull?.let {
                webToolsForWorkspace(sessionId, it)
            } ?: webTools
            selected.fetch(
                url,
                maxBytes.coerceIn(16 * 1024, MAX_WEB_FETCH_BYTES),
                format,
                timeout.coerceIn(30L, BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS),
            )
        }
    }

    private fun resumeSubagent(
        snapshot: JobSnapshot,
        sessionId: String,
        payload: JsonObject,
    ) {
        val version = payload["version"]?.jsonPrimitive?.intOrNull
            ?: error("旧版持久子代理缺少路由身份，已停止自动续跑")
        require(version in MIN_PERSISTENT_SUBAGENT_RESUME_VERSION..PERSISTENT_SUBAGENT_RESUME_VERSION) {
            "持久子代理恢复版本不受支持：$version"
        }
        val task = payload.requiredString("task")
        val instructions = if (version >= 3) {
            payload["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty()
        } else {
            ""
        }
        val profileId = payload.requiredString("profile_id")
        val model = payload.requiredString("model")
        val baseUrl = payload.requiredString("base_url")
        val authKind = payload.requiredString("auth_kind")
        val protocol = payload.requiredString("protocol")
        val credentialRef = payload["credential_ref"]?.jsonPrimitive?.contentOrNull
        val fingerprint = payload.requiredString("route_fingerprint")
        val capabilities = decodeLocalSubagentCapabilities(payload, version)
        val parentLink = payload["parent_link"]?.let { raw ->
            decodeLocalAgentParentLink(raw as? JsonObject
                ?: error("AGENT_PARENT_LINK_INVALID"))
        }
        require(parentLink == null || parentLink.sessionId == sessionId) {
            "AGENT_PARENT_SESSION_MISMATCH：恢复子代理的持久父节点不属于目标会话"
        }
        val workflowFence = payload["workflow_fence"] as? JsonObject
        require(!capabilities.workflowManaged || workflowFence != null) { "WORKFLOW_EXECUTION_FENCE_REQUIRED" }
        if (workflowFence != null) {
            try { requireWorkflowFenceCurrent(sessionId, workflowFence) }
            catch (_: LocalWorkflowSuperseded) { return } // New USER runs first; preserve a safe slice for explicit resume.
            catch (invalid: IllegalStateException) {
                markRecoveryUnavailable(snapshot, "任务图目标或节点证据已失效：${invalid.message.orEmpty()}")
                return
            }
        }
        val log = eventLogFor(sessionId)
        val checkpointEvent = log.latestMatching(setOf(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT)) { data ->
            data["background_job_id"]?.jsonPrimitive?.contentOrNull == snapshot.id
        }
        val continuation = checkpointEvent
            ?.let { event -> decodeLocalSubagentHistoryCheckpoint(event.data) }

        var pendingIds = jobs.peekMessages(snapshot.id).mapTo(hashSetOf()) { it.id }
        if (workflowFence != null && continuation?.terminalOutput != null &&
            log.latestMatching(setOf("taskgraph/job-result")) { it["job_id"]?.jsonPrimitive?.contentOrNull == snapshot.id } == null
        ) {
            // Legacy bounded checkpoint text is insufficient as a complete graph result.
            markRecoveryUnavailable(snapshot, "节点终态存在但完整结果未持久化，需要核验，不能重放")
            return
        }
        if (
            shouldSettleCompletedSubagentCheckpoint(continuation, pendingIds) &&
            jobs.settleResumableAgentIfInboxClaimed(
                id = snapshot.id,
                output = continuation?.terminalOutput.orEmpty(),
                claimedMessageIds = continuation?.claimedMessageIds.orEmpty(),
                ownerSessionId = sessionId,
            )
        ) {
            return
        }
        // Crash interruption can leave a writable side effect unresolved: require the Lead
        // to inspect it before reassigning work. A typed local request-slice boundary is
        // reached before provider admission, after the last completed tool checkpoint.
        // The latter resumes only with a newly admitted root Work slice.
        if ((workflowFence != null && workflowWritableResumeNeedsVerification(capabilities.allowMutation, snapshot.status)) ||
            requiresLeadVerificationForSubagentResume(
                capabilities.teamManaged, capabilities.allowMutation, snapshot.status,
            )
        ) {
            markRecoveryUnavailable(
                snapshot,
                "可写团队成员在应用中断后停止自动续跑；请由主代理先核验文件和任务状态，再重新分配。",
            )
            return
        }
        if (workflowFence != null && capabilities.allowMutation) {
            try {
                check(workflowToolContinuationSafe(log, snapshot.id)) { "工具回执之后缺少安全历史检查点" }
            } catch (unsettled: IllegalStateException) {
                markRecoveryUnavailable(snapshot, "可写节点工具副作用或历史检查点未安全结算，停止自动恢复：${unsettled.message.orEmpty()}")
                return
            }
        }
        // Only an actual model continuation needs a live, session-owned execution context.
        // Terminal checkpoint settlement above is fully durable and can finish off-screen.
        val boundState = stateForSession(sessionId) ?: return
        val parentHistory = historyForSession(sessionId) ?: return
        val maxSteps = payload["max_steps"]?.jsonPrimitive?.intOrNull
            ?.let(LocalAgentRuntimeLimits::normalizeSubagentSteps)
            ?: boundState.subagentMaxSteps

        // A genuinely new message may have arrived after the first Inbox snapshot.
        // Refresh before rebuilding the recovery tail so terminal settlement cannot
        // overwrite a newly queued continuation request.
        pendingIds = jobs.peekMessages(snapshot.id).mapTo(hashSetOf()) { it.id }

        val recoveredTail = if (checkpointEvent != null && continuation != null) {
            recoverClaimedMessagesAfterCheckpoint(
                log = log,
                backgroundJobId = snapshot.id,
                checkpointSequence = checkpointEvent.sequence,
                alreadyClaimed = continuation.claimedMessageIds,
                pendingIds = pendingIds,
            )
        } else {
            emptyList()
        }
        val recoveredHistory = continuation?.history?.let { base ->
            if (recoveredTail.isEmpty()) {
                base
            } else {
                base + recoveredTail.map { message ->
                    modelMessageForAgentInput(message)
                }
            }
        }
        val recoveredClaimedIds = buildSet {
            addAll(continuation?.claimedMessageIds.orEmpty())
            recoveredTail.forEach { add(it.id) }
        }

        jobs.resumePersistent(snapshot.id, ownerSessionId = sessionId) { jobId, report ->
          if (capabilities.teamManaged) awaitTeamMemberSpawnCommit(eventLogFor(sessionId), sessionId, jobId)
          suspendWorkflowJobForNewUser(eventLogFor(sessionId), jobId, workflowFence != null, capabilities.allowMutation, capabilities.teamManaged) {
            val profile = modelGateway.profileForRoute(profileId, model, baseUrl)
            require(
                profile.authKind.name == authKind &&
                    effectiveProtocol(profile) == protocol &&
                    profile.credentialRef == credentialRef &&
                    profile.routeFingerprint() == fingerprint
            ) { "持久子代理原模型路由身份已变化，已停止自动续跑" }
            val workspaceBinding = decodePersistentAgentWorkspaceId(payload)?.let { resolveWorkspace(sessionId, it) }
            if (workspaceBinding != null && workflowFence != null) requireWorkflowWorkspaceInputs(workspaceBinding, workflowFence)
            val runner = subagentRunner(sessionId, boundState, { parentHistory }, workspaceBinding)
            withContext((workspaceBinding ?: kotlin.coroutines.EmptyCoroutineContext) + LocalModelRunContext(profile) + LocalWorkflowExecutionFence {
                if (workflowFence != null) requireWorkflowFenceCurrent(sessionId, workflowFence)
            }) {
                runner.runResult(
                    spec = validateLocalSubagentLaunchSpec(
                        LocalSubagentLaunchSpec(
                            task = task,
                            instructions = instructions,
                            modelOverride = profile.id,
                            maxSteps = maxSteps,
                            parentCallId = payload["parent_call_id"]?.jsonPrimitive?.contentOrNull,
                            parentLink = parentLink,
                            backgroundJobId = jobId,
                            capabilities = capabilities,
                        ),
                        structuredOutputSupported = true,
                    ),
                    recoveredHistory = recoveredHistory,
                    recoveredClaimedMessageIds = recoveredClaimedIds,
                    recoveredStep = continuation?.step ?: 0,
                    recoveredSoftStepLimit = continuation?.softStepLimit,
                    recoveredEnabledOptionalTools = continuation?.enabledOptionalTools.orEmpty(),
                    resumeAfterCompletion =
                        shouldColdResumeCompletedSubagent(continuation, pendingIds),
                    executionFence = {
                        if (workflowFence != null) requireWorkflowFenceCurrent(sessionId, workflowFence)
                    },
                    onCheckpointProgress = { step, totalLimit ->
                        report("子代理执行进度：$step/$totalLimit")
                    },
                ).requireCompletedOutput().also { persistWorkflowOutput(sessionId, jobId, it, workflowFence) }
            }
          }
        }
    }

    internal suspend fun workflowRoute(model: String?): Pair<String, String> =
        modelGateway.profileForRun(model).let { it.id to it.routeFingerprint() }
    internal suspend fun workflowRouteFingerprint(model: String?): String = workflowRoute(model).second

    private suspend fun persistWorkflowOutput(sessionId: String, jobId: String, output: String, fence: JsonObject?) {
        if (fence == null) return
        requireWorkflowFenceCurrent(sessionId, fence)
        val toolExecutions = workflowToolSettlementEvidence(eventLogFor(sessionId), jobId)
        val scope = currentCoroutineContext()[com.labteto.dshmobile.harness.capability.WorkspaceExecutionContext]
            as? com.labteto.dshmobile.local.files.LocalWorkspaceExecutionContext
        if (scope != null) {
            requireWorkflowWorkspaceInputs(scope, fence)
            workflowOutputRejection(output, fence["required_evidence"]?.jsonPrimitive?.contentOrNull.orEmpty())?.let { error(it) }
            val paths = (fence["output_paths"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            require(paths.isNotEmpty()) { "独立节点必须声明交付路径" }
            paths.forEach { path ->
                require(localWorkFileSha256(scope.root, scope.localPath(path)) != null) { "节点未产生声明成果：$path" }
            }
            val inspected = inspectWorkspace(sessionId, scope.workspaceId)
            val merged = mergeWorkspace(sessionId, scope.workspaceId, inspected.getValue("tree").jsonPrimitive.content,
                paths.map(scope::localPath)) { requireWorkflowFenceCurrent(sessionId, fence) }
            check(merged["state"]?.jsonPrimitive?.contentOrNull in setOf("merged", "already_present")) {
                "节点成果整合发生冲突，已保留原工作区：${scope.workspaceId}"
            }
            eventLogFor(sessionId).append("taskgraph/workspace-merged", buildJsonObject {
                put("job_id", jobId); put("workspace_id", scope.workspaceId); put("receipt", merged)
            })
        }
        eventLogFor(sessionId).append("taskgraph/job-result", buildJsonObject {
            fence.forEach { (key, value) -> put(key, value) }
            put("job_id", jobId)
            put("output", output)
            put("tool_execution_ids", JsonArray(toolExecutions.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("output_files", buildJsonObject {
                (fence["output_paths"] as? JsonArray).orEmpty().forEach { path ->
                    val name = path.jsonPrimitive.content
                    put(name, localWorkFileSha256(workspaceRoot, name) ?: "missing")
                }
            })
        })
    }

    /** The Job's execution identity stays fixed; only the root USER authorization may advance. */
    private fun requireWorkflowFenceCurrent(sessionId: String, fence: JsonObject) {
        val log = eventLogFor(sessionId)
        val execution = fence["graph_execution_id"]?.jsonPrimitive?.contentOrNull
            ?: error("节点缺少图执行身份")
        check(log.latestMatching(setOf("taskgraph/end")) {
                it["graph_execution_id"]?.jsonPrimitive?.contentOrNull == execution
            } == null) { "任务图已结算，拒绝迟到节点结果" }
        val renewed = log.latestMatching(setOf("taskgraph/resume-authorized")) {
            it["graph_execution_id"]?.jsonPrimitive?.contentOrNull == execution
        }?.data
        // A new USER wins even when it also changed a file while the old Job was waiting.
        if (workflowAuthorizationSuperseded(log, renewed ?: fence)) throw LocalWorkflowSuperseded()
        val start = log.latestMatching(setOf("taskgraph/start")) {
            it["graph_execution_id"]?.jsonPrimitive?.contentOrNull == execution
        }?.data ?: error("节点缺少原图定义")
        val plan = LocalWorkTaskGraphPlan(start["plan"] as? JsonObject ?: error("原图定义不完整"))
        val nodeId = fence["node_id"]?.jsonPrimitive?.contentOrNull ?: error("节点身份缺失")
        val index = plan.nodes.indexOfFirst { it.id == nodeId }
        check(index >= 0 && fence["node_version"]?.jsonPrimitive?.contentOrNull == plan.nodeVersion(plan.nodes[index])) {
            "节点定义版本已改变"
        }
        check(fence["backend_binding"] == plan.backendBinding) { "节点执行后端身份已变化" }
        val originalFiles = fence["files"] as? JsonObject ?: error("节点文件版本缺失")
        val currentFiles = plan.fileVersions(workspaceRoot, index)
        val identityPaths = if (plan.writable) plan.inputPaths(index) else
            plan.inputPaths(index) + plan.outputPaths(index)
        check(identityPaths.all { originalFiles[it] == currentFiles[it] }) { "节点输入文件证据已变化" }
        val accepted = plan.recoveredResults(log, execution, workspaceRoot)
        check(plan.nodes[index].dependsOn.all { it in accepted }) { "节点依赖已失效" }
        val dependencyHashes = buildJsonObject { plan.nodes[index].dependsOn.forEach { id ->
            put(id, workflowVersion(accepted.getValue(id).output.orEmpty()))
        } }
        check(fence["dependencies"] == dependencyHashes) { "节点依赖内容版本已变化" }
        val expectedVersions = fence["dependency_versions"] as? JsonObject
        if (expectedVersions != null) check(plan.nodes[index].dependsOn.all { id ->
            log.latestMatching(setOf("taskgraph/node-accepted")) {
                it["graph_execution_id"]?.jsonPrimitive?.contentOrNull == execution &&
                    it["node_id"]?.jsonPrimitive?.contentOrNull == id
            }?.data?.get("result_sequence") == expectedVersions[id]
        }) { "节点依赖验收版本已变化" }
        if (plan.writable && renewed != null) check(
            renewed["allow_mutation"]?.jsonPrimitive?.booleanOrNull == true
        ) { "可写图本轮未授权" }
        val goal = stateForSession(sessionId)?.work?.goal
        val fingerprint = fence["goal_fingerprint"]?.jsonPrimitive?.contentOrNull.orEmpty()
        check(if (fingerprint.isBlank()) goal == null else
            goal?.status == "active" && localWorkGoalFingerprint(goal) == fingerprint) { "任务图目标版本已失效" }
    }

    /** Consume the original durable result before looking at a possibly cancelled/retained Job. */
    private fun requireWorkflowJobResult(result: JsonObject, jobId: String, fence: JsonObject, sessionId: String): String {
        requireWorkflowFenceCurrent(sessionId, fence)
        val original = result["files"] as? JsonObject ?: error("原 Job 缺少输入文件版本")
        val now = fence["files"] as? JsonObject ?: error("节点缺少输入文件版本")
        val execution = fence["graph_execution_id"]?.jsonPrimitive?.contentOrNull
        val plan = LocalWorkTaskGraphPlan(eventLogFor(sessionId).latestMatching(setOf("taskgraph/start")) {
            it["graph_execution_id"]?.jsonPrimitive?.contentOrNull == execution
        }?.data?.get("plan") as? JsonObject ?: error("原图定义缺失"))
        val index = plan.nodes.indexOfFirst { it.id == fence["node_id"]?.jsonPrimitive?.contentOrNull }
        check(index >= 0 && plan.inputPaths(index).all { original[it] == now[it] }) { "原 Job 输入文件版本已变化" }
        check(result["job_id"]?.jsonPrimitive?.contentOrNull == jobId &&
            result["graph_execution_id"] == fence["graph_execution_id"] &&
            result["node_id"] == fence["node_id"] &&
            result["node_version"] == fence["node_version"] &&
            result["goal_fingerprint"] == fence["goal_fingerprint"] &&
            result["dependencies"] == fence["dependencies"] &&
            result["tool_execution_ids"] is JsonArray) { "原节点 Job 结果身份或副作用证据不匹配" }
        val outputPaths = (fence["output_paths"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        val outputs = result["output_files"] as? JsonObject ?: error("原 Job 缺少交付文件证据")
        check(outputPaths.all { path ->
            val current = localWorkFileSha256(workspaceRoot, path)
            current != null && outputs[path]?.jsonPrimitive?.contentOrNull == current
        }) { "原节点交付文件已变化" }
        val output = result["output"]?.jsonPrimitive?.contentOrNull
        check(!output.isNullOrBlank()) { "原节点缺少完整结果" }
        return output
    }

    /** Jobs remains the sole execution owner; this caller only waits for durable terminal evidence. */
    internal suspend fun awaitWorkflowNode(
        jobId: String, task: String, model: String?, expectedRoute: String,
        maxSteps: Int, outputSchema: JsonObject?, toolAllowlist: Set<String>?,
        allowMutation: Boolean, binding: LocalWorkRunBinding, fence: JsonObject,
        workspaceRepository: String? = null,
    ): String {
        val log = binding.eventLog
        requireWorkflowFenceCurrent(binding.sessionId, fence)
        val prior = log.latestMatching(setOf("taskgraph/job-result")) {
            it["job_id"]?.jsonPrimitive?.contentOrNull == jobId
        }
        if (prior != null) return requireWorkflowJobResult(prior.data, jobId, fence, binding.sessionId)
        require(workflowRouteFingerprint(model) == expectedRoute) { "任务图原模型路由已变化，停止恢复" }
        val existing = jobs.snapshots().singleOrNull { it.id == jobId }
        if (existing == null) {
            // A result survives Jobs retention; never recreate an already executed job.
            val launched = log.latestMatching(setOf("taskgraph/job-admitted", "taskgraph/job-rejected")) {
                it["job_id"]?.jsonPrimitive?.contentOrNull == jobId
            }
            check(workflowCanCreateMissingJob(allowMutation, launched != null, launched?.type == "taskgraph/job-rejected")) { "可写节点原 Job 缺失且副作用未知，必须核验，禁止重放" }
            log.append("taskgraph/job-admitted", buildJsonObject {
                fence.forEach { (key, value) -> put(key, value) }
                put("job_id", jobId)
            })
            val admitted = startReadonlySubagentResult(
                task = task, model = model, maxSteps = maxSteps, virtualScreen = false,
                outputSchema = outputSchema, toolAllowlist = toolAllowlist,
                sessionId = binding.sessionId, boundState = binding.aggregateSnapshot(),
                historySnapshot = binding.runHandle.modelHistory::snapshot,
                requestedJobId = jobId, allowMutation = allowMutation,
                workflowFence = fence,
                backendCapabilities = com.labteto.dshmobile.local.agent.decodeAgentBackendBinding(
                    if ("backend_binding" in fence) fence["backend_binding"] as? JsonObject
                        ?: error("TASKGRAPH_BACKEND_BINDING_INVALID") else null),
                workspaceRepository = workspaceRepository,
            )
            if (!admitted.accepted) {
                log.append("taskgraph/job-rejected", buildJsonObject { put("job_id", jobId); put("detail", admitted.message) })
                if (jobs.availableSlots() == 0) throw LocalWorkflowSuspended()
                error(admitted.message)
            }
        } else {
            check(existing.ownerId == binding.sessionId) { "节点 Job 所有权不一致" }
            schedule(binding.sessionId, rootSliceAdmitted = true)
        }
        return awaitPersistedWorkflowJob(jobId, jobs,
            checkFence = { requireWorkflowFenceCurrent(binding.sessionId, fence) },
            consumeResult = {
                log.latestMatching(setOf("taskgraph/job-result")) {
                    it["job_id"]?.jsonPrimitive?.contentOrNull == jobId
                }?.data?.let { requireWorkflowJobResult(it, jobId, fence, binding.sessionId) }
                    ?: error("节点 Job 完成但缺少完整持久结果")
            },
        )
    }

    internal fun cancelWorkflowJobs(sessionId: String, executionId: String, preserveSafeSlice: Boolean = false) {
        jobs.snapshots().filter { snapshot ->
            snapshot.ownerId == sessionId && snapshot.resumePayload?.let { raw ->
                runCatching { json.parseToJsonElement(raw).jsonObject["workflow_fence"]
                    ?.jsonObject?.get("graph_execution_id")?.jsonPrimitive?.contentOrNull == executionId }.getOrDefault(false)
            } == true
        // A running Job observes its own fence and becomes a checked waiting_slice. Killing it here
        // would turn USER preemption into an unrecoverable cancelled Job before that checkpoint.
        }.filterNot { preserveSafeSlice }
            .forEach { jobs.kill(it.id, sessionId) }
    }

    private fun recoverClaimedMessagesAfterCheckpoint(
        log: LocalSessionEventLog,
        backgroundJobId: String,
        checkpointSequence: Long,
        alreadyClaimed: Set<String>,
        pendingIds: Set<String>,
    ): List<QueuedAgentInput> = recoverSubagentInboxClaims(
        backgroundJobId = backgroundJobId,
        checkpointSequence = checkpointSequence,
        scanThrough = log.latestSequence(),
        alreadyClaimed = alreadyClaimed,
        pendingIds = pendingIds,
        pageAfter = { after -> log.pageAfter(after, RECOVERY_EVENT_PAGE_SIZE) },
    )

    private fun effectiveProtocol(profile: LocalModelProfile): String =
        if (profile.authKind == LocalModelAuthKind.CHATGPT_PLAN) {
            LocalModelProtocol.RESPONSES.name
        } else {
            profile.protocol.name
        }

    private fun interruptedSessionId(snapshot: JobSnapshot, fallback: String): String? =
        snapshot.resumePayload?.let { payload ->
            runCatching {
                json.parseToJsonElement(payload).jsonObject["session_id"]
                    ?.jsonPrimitive?.contentOrNull ?: fallback
            }.getOrNull()
        }

    private fun recordError(snapshot: JobSnapshot, sessionId: String, error: Throwable) {
        eventLogFor(sessionId).append("job/resume-error", buildJsonObject {
            put("job_id", snapshot.id)
            put("kind", snapshot.resumeKind.orEmpty())
            put("detail", (error.message ?: error::class.java.simpleName).take(2_000))
        })
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("恢复任务缺少 $key")
    private companion object {
        const val MIN_PERSISTENT_SUBAGENT_RESUME_VERSION = 1
        const val PERSISTENT_SUBAGENT_RESUME_VERSION = 3
        const val RECOVERY_EVENT_PAGE_SIZE = 200
    }
}

/** Actual persisted Jobs block, retaining only a slice without uncheckpointed write effects. */
internal suspend fun <T> suspendWorkflowJobForNewUser(
    log: LocalSessionEventLog, jobId: String, workflowNode: Boolean, allowMutation: Boolean,
    teamManaged: Boolean = false,
    block: suspend () -> T,
): T = try { block() } catch (superseded: LocalWorkflowSuperseded) {
    if (!workflowNode && !teamManaged) throw superseded
    // Team task changes pause the existing Job only at a safely replayable slice.
    // A USER fence can fire between a tool's settlement and its history checkpoint.
    // Without a matching terminal result, even a settled write must not be reissued.
    if (allowMutation && !workflowToolContinuationSafe(log, jobId)) throw superseded
    throw JobExecutionSliceBoundaryException(superseded)
}

/** The production Jobs wait path; root Work alone decides whether an interruption pauses or settles the graph. */
internal suspend fun awaitPersistedWorkflowJob(
    jobId: String,
    jobs: LocalJobManager,
    checkFence: () -> Unit,
    consumeResult: () -> String,
): String {
    while (true) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        checkFence()
        val snapshot = jobs.snapshots().singleOrNull { it.id == jobId }
            ?: error("节点 Job 丢失，不能安全重放")
        when (snapshot.status) {
            "dormant", "completed" -> return consumeResult()
            "waiting_slice" -> throw LocalWorkflowSuspended()
            "running", "interrupted" -> Unit
            else -> throw com.labteto.dshmobile.harness.workflow.TaskGraphNodeExecutionException(
                "节点 Job 未成功：${snapshot.status}：${snapshot.output}",
            )
        }
        delay(100)
    }
}
