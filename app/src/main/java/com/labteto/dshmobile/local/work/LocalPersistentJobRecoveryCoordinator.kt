package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.jobs.JobSnapshot
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
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import com.labteto.dshmobile.local.agent.LocalSubagentLaunchSpec
import com.labteto.dshmobile.local.agent.validateLocalSubagentLaunchSpec
import com.labteto.dshmobile.local.web.LocalWebTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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

internal class LocalPersistentJobRecoveryCoordinator(
    private val scope: CoroutineScope,
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
    ) -> LocalSubagentRunner,
    private val eventLogFor: (String) -> LocalSessionEventLog,
) {
    private val lock = Any()
    private val pendingRecoverySessions = linkedSetOf<String>()
    private var recoveryJob: Job? = null

    suspend fun startReadonlySubagent(
        task: String,
        model: String?,
        maxSteps: Int,
        virtualScreen: Boolean,
        sessionId: String = currentSessionId(),
        boundState: LocalHarnessState = currentState(),
        historySnapshot: () -> List<JsonObject> = defaultHistory,
    ): String {
        val runProfile = modelGateway.profileForRun(model)
        val protocol = effectiveProtocol(runProfile)
        val runner = subagentRunner(sessionId, boundState, historySnapshot)
        val capabilities = LocalSubagentCapabilities(
            allowMutation = false,
            continuable = true,
            virtualScreen = virtualScreen,
            historyMode = LocalSubagentHistoryMode.ISOLATED,
            maxDepth = 1,
        )
        val payload = buildJsonObject {
            put("version", PERSISTENT_SUBAGENT_RESUME_VERSION)
            put("session_id", sessionId)
            put("task", task)
            put("profile_id", runProfile.id)
            put("model", runProfile.model)
            put("base_url", runProfile.baseUrl)
            put("auth_kind", runProfile.authKind.name)
            put("protocol", protocol)
            runProfile.credentialRef?.let { put("credential_ref", it) }
            put("route_fingerprint", runProfile.routeFingerprint())
            put("max_steps", maxSteps)
            put("capabilities", encodeCapabilities(capabilities))
        }.toString()
        return jobs.startPersistent(
            label = "子代理：${task.take(100)}",
            resumeKind = "subagent_readonly",
            resumePayload = payload,
            ownerSessionId = sessionId,
            continuable = true,
        ) { jobId, _ ->
            withContext(LocalModelRunContext(runProfile)) {
                runner.runResult(
                    LocalSubagentLaunchSpec(
                        task = task,
                        modelOverride = null,
                        maxSteps = maxSteps,
                        backgroundJobId = jobId,
                        capabilities = capabilities,
                    ),
                ).requireCompletedOutput()
            }
        }
    }

    fun sendMessage(
        agentId: String,
        message: String,
        sessionId: String,
    ): String {
        val result = jobs.send(agentId, message, sessionId)
        if (result.startsWith("消息已发送") || result.startsWith("消息已持久排队")) {
            schedule(sessionId)
        }
        return result
    }

    fun schedule(sessionId: String = currentSessionId()) {
        val targetSession = sessionId.trim()
        if (targetSession.isEmpty()) return
        synchronized(lock) {
            pendingRecoverySessions += targetSession
            if (recoveryJob?.isActive == true) return
            recoveryJob = scope.launch {
                try {
                    while (true) {
                        val nextSession = synchronized(lock) {
                            pendingRecoverySessions.firstOrNull()?.also { pendingRecoverySessions.remove(it) }
                        } ?: break
                        resumePass(nextSession)
                    }
                } finally {
                    val completed = currentCoroutineContext()[Job]
                    val restart = synchronized(lock) {
                        if (recoveryJob === completed) recoveryJob = null
                        recoveryJob == null && pendingRecoverySessions.isNotEmpty()
                    }
                    if (restart) {
                        synchronized(lock) { pendingRecoverySessions.firstOrNull() }
                            ?.let(::schedule)
                    }
                }
            }
        }
    }

    private fun resumePass(targetSession: String) {
        jobs.resumableSnapshots().forEach { snapshot ->
            val payload = decodePayload(snapshot, targetSession) ?: return@forEach
            val sessionId = payload["session_id"]?.jsonPrimitive?.contentOrNull ?: targetSession
            if (sessionId != targetSession || jobs.availableSlots() <= 0) return@forEach
            try {
                when (snapshot.resumeKind) {
                    "web_fetch" -> resumeWebFetch(snapshot, sessionId, payload)
                    "subagent_readonly" -> resumeSubagent(snapshot, sessionId, payload)
                    else -> jobs.failResumable(
                        snapshot.id,
                        "任务恢复失败：不支持的恢复类型 ${snapshot.resumeKind.orEmpty()}",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                jobs.failResumable(snapshot.id, "任务无法恢复：${error.message.orEmpty().take(500)}")
                recordError(snapshot, sessionId, error)
            }
        }
    }

    private fun decodePayload(snapshot: JobSnapshot, fallbackSessionId: String): JsonObject? {
        val text = snapshot.resumePayload
        if (text.isNullOrBlank()) {
            jobs.failResumable(snapshot.id, "任务恢复失败：缺少恢复元数据")
            return null
        }
        return try {
            json.parseToJsonElement(text).jsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            jobs.failResumable(snapshot.id, "任务恢复失败：恢复元数据损坏")
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
            webTools.fetch(
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
        val profileId = payload.requiredString("profile_id")
        val model = payload.requiredString("model")
        val baseUrl = payload.requiredString("base_url")
        val authKind = payload.requiredString("auth_kind")
        val protocol = payload.requiredString("protocol")
        val credentialRef = payload["credential_ref"]?.jsonPrimitive?.contentOrNull
        val fingerprint = payload.requiredString("route_fingerprint")
        val capabilities = decodeCapabilities(payload, version)
        val log = eventLogFor(sessionId)
        val checkpointEvent = log.latestMatching(setOf(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT)) { data ->
            data["background_job_id"]?.jsonPrimitive?.contentOrNull == snapshot.id
        }
        val continuation = checkpointEvent
            ?.let { event -> decodeLocalSubagentHistoryCheckpoint(event.data) }

        var pendingIds = jobs.peekMessages(snapshot.id).mapTo(hashSetOf()) { it.id }
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
        // Only an actual model continuation needs a live, session-owned execution context.
        // Terminal checkpoint settlement above is fully durable and can finish off-screen.
        val boundState = stateForSession(sessionId) ?: return
        val parentHistory = historyForSession(sessionId) ?: return
        val maxSteps = payload["max_steps"]?.jsonPrimitive?.intOrNull
            ?.let(LocalAgentRuntimeLimits::normalizeSubagentSteps)
            ?: boundState.subagentMaxSteps
        val runner = subagentRunner(sessionId, boundState) { parentHistory }

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
                    message.modelMessage ?: buildJsonObject {
                        put("role", "user")
                        put("content", message.content)
                    }
                }
            }
        }
        val recoveredClaimedIds = buildSet {
            addAll(continuation?.claimedMessageIds.orEmpty())
            recoveredTail.forEach { add(it.id) }
        }

        jobs.resumePersistent(snapshot.id, ownerSessionId = sessionId) { jobId, _ ->
            val profile = modelGateway.profileForRoute(profileId, model, baseUrl)
            require(
                profile.authKind.name == authKind &&
                    effectiveProtocol(profile) == protocol &&
                    profile.credentialRef == credentialRef &&
                    profile.routeFingerprint() == fingerprint
            ) { "持久子代理原模型路由身份已变化，已停止自动续跑" }
            withContext(LocalModelRunContext(profile)) {
                runner.runResult(
                    spec = validateLocalSubagentLaunchSpec(
                        LocalSubagentLaunchSpec(
                            task = task,
                            modelOverride = null,
                            maxSteps = maxSteps,
                            backgroundJobId = jobId,
                            capabilities = capabilities,
                        ),
                    ),
                    recoveredHistory = recoveredHistory,
                    recoveredClaimedMessageIds = recoveredClaimedIds,
                    recoveredStep = continuation?.step ?: 0,
                    recoveredSoftStepLimit = continuation?.softStepLimit,
                    resumeAfterCompletion =
                        shouldColdResumeCompletedSubagent(continuation, pendingIds),
                ).requireCompletedOutput()
            }
        }
    }

    private fun recoverClaimedMessagesAfterCheckpoint(
        log: LocalSessionEventLog,
        backgroundJobId: String,
        checkpointSequence: Long,
        alreadyClaimed: Set<String>,
        pendingIds: Set<String>,
    ): List<QueuedAgentInput> {
        val recovered = mutableListOf<QueuedAgentInput>()
        val seen = alreadyClaimed.toMutableSet()
        val scanThrough = log.latestSequence()
        var cursor = checkpointSequence
        var pages = 0
        while (cursor < scanThrough) {
            check(pages < MAX_RECOVERY_EVENT_PAGES) {
                "持久子代理恢复事件过多，拒绝无界扫描"
            }
            pages += 1
            val page = log.pageAfter(cursor, RECOVERY_EVENT_PAGE_SIZE)
            if (page.isEmpty()) break
            for (event in page) {
                if (event.sequence > scanThrough) break
                if (
                    event.type != LOCAL_SUBAGENT_INBOX_CLAIM_EVENT ||
                    event.data["background_job_id"]?.jsonPrimitive?.contentOrNull != backgroundJobId
                ) {
                    continue
                }
                val messages = decodeLocalSubagentInboxClaimedMessages(event.data) ?: continue
                for (message in messages) {
                    if (message.id in pendingIds || !seen.add(message.id)) continue
                    recovered += message
                }
            }
            val nextCursor = page.last().sequence
            if (nextCursor <= cursor) break
            cursor = nextCursor
        }
        return recovered
    }

    private fun encodeCapabilities(
        capabilities: LocalSubagentCapabilities,
    ): JsonObject = buildJsonObject {
        put("allow_mutation", capabilities.allowMutation)
        put("continuable", capabilities.continuable)
        put("virtual_screen", capabilities.virtualScreen)
        put("history_mode", capabilities.historyMode.name.lowercase())
        put("max_depth", capabilities.maxDepth)
        capabilities.toolAllowlist?.let { allowlist ->
            put(
                "tool_allowlist",
                JsonArray(allowlist.sorted().map(kotlinx.serialization.json::JsonPrimitive)),
            )
        }
    }

    private fun decodeCapabilities(
        payload: JsonObject,
        version: Int,
    ): LocalSubagentCapabilities {
        if (version <= 1) {
            return LocalSubagentCapabilities(
                allowMutation = false,
                continuable = true,
                virtualScreen =
                    payload["virtual_screen"]?.jsonPrimitive?.booleanOrNull ?: false,
                historyMode = LocalSubagentHistoryMode.ISOLATED,
                maxDepth = 1,
            )
        }
        val data = payload["capabilities"] as? JsonObject
            ?: error("持久子代理 V2 缺少 capabilities")
        val historyMode = when (
            data["history_mode"]?.jsonPrimitive?.contentOrNull
                ?.lowercase()
        ) {
            "isolated" -> LocalSubagentHistoryMode.ISOLATED
            "inherit_parent" -> LocalSubagentHistoryMode.INHERIT_PARENT
            else -> error("持久子代理 capabilities.history_mode 无效")
        }
        val toolAllowlist = (data["tool_allowlist"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet()
        return LocalSubagentCapabilities(
            allowMutation = data["allow_mutation"]?.jsonPrimitive?.booleanOrNull ?: false,
            continuable = data["continuable"]?.jsonPrimitive?.booleanOrNull ?: false,
            virtualScreen = data["virtual_screen"]?.jsonPrimitive?.booleanOrNull ?: false,
            historyMode = historyMode,
            maxDepth = data["max_depth"]?.jsonPrimitive?.intOrNull ?: 1,
            toolAllowlist = toolAllowlist,
        )
    }

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
        const val PERSISTENT_SUBAGENT_RESUME_VERSION = 2
        const val RECOVERY_EVENT_PAGE_SIZE = 200
        const val MAX_RECOVERY_EVENT_PAGES = 64
    }
}
