package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.local.model.LocalModelRunContext
import com.labteto.dshmobile.local.model.LocalModelGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
    private val subagentRunner: (
        sessionId: String,
        state: LocalHarnessState,
        history: () -> List<JsonObject>,
    ) -> LocalSubagentRunner,
    private val eventLogFor: (String) -> LocalSessionEventLog,
) {
    private val lock = Any()
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
            put("virtual_screen", virtualScreen)
        }.toString()
        return jobs.startPersistent(
            label = "子代理：${task.take(100)}",
            resumeKind = "subagent_readonly",
            resumePayload = payload,
            ownerSessionId = sessionId,
        ) { jobId, _ ->
            withContext(LocalModelRunContext(runProfile)) {
                runner.runResult(
                    task = task,
                    inheritHistory = false,
                    allowMutation = false,
                    backgroundJobId = jobId,
                    modelOverride = null,
                    maxSteps = maxSteps,
                    virtualScreen = virtualScreen,
                ).requireCompletedOutput()
            }
        }
    }

    fun schedule() {
        synchronized(lock) {
            if (recoveryJob?.isActive == true) return
            recoveryJob = scope.launch {
                try {
                    while (true) {
                        val targetSession = currentSessionId()
                        resumePass(targetSession)
                        val remaining = jobs.interruptedSnapshots().any { snapshot ->
                            interruptedSessionId(snapshot, targetSession) == targetSession
                        }
                        if (!remaining) break
                        delay(PERSISTENT_RECOVERY_RETRY_MILLIS)
                    }
                } finally {
                    val completed = currentCoroutineContext()[Job]
                    synchronized(lock) {
                        if (recoveryJob === completed) recoveryJob = null
                    }
                }
            }
        }
    }

    private fun resumePass(targetSession: String) {
        jobs.interruptedSnapshots().forEach { snapshot ->
            val payload = decodePayload(snapshot, targetSession) ?: return@forEach
            val sessionId = payload["session_id"]?.jsonPrimitive?.contentOrNull ?: targetSession
            if (sessionId != targetSession || jobs.availableSlots() <= 0) return@forEach
            try {
                when (snapshot.resumeKind) {
                    "web_fetch" -> resumeWebFetch(snapshot, sessionId, payload)
                    "subagent_readonly" -> resumeSubagent(snapshot, sessionId, payload)
                    else -> jobs.failInterrupted(
                        snapshot.id,
                        "任务恢复失败：不支持的恢复类型 ${snapshot.resumeKind.orEmpty()}",
                    )
                }
            } catch (error: Exception) {
                recordError(snapshot, sessionId, error)
            }
        }
    }

    private fun decodePayload(snapshot: JobSnapshot, fallbackSessionId: String): JsonObject? {
        val text = snapshot.resumePayload
        if (text.isNullOrBlank()) {
            jobs.failInterrupted(snapshot.id, "任务恢复失败：缺少恢复元数据")
            return null
        }
        return try {
            json.parseToJsonElement(text).jsonObject
        } catch (error: Exception) {
            jobs.failInterrupted(snapshot.id, "任务恢复失败：恢复元数据损坏")
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

    private fun resumeSubagent(snapshot: JobSnapshot, sessionId: String, payload: JsonObject) {
        val version = payload["version"]?.jsonPrimitive?.intOrNull
            ?: error("旧版持久子代理缺少路由身份，已停止自动续跑")
        require(version == PERSISTENT_SUBAGENT_RESUME_VERSION) {
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
        val maxSteps = payload["max_steps"]?.jsonPrimitive?.intOrNull
            ?.let(LocalAgentRuntimeLimits::normalizeSubagentSteps)
            ?: currentState().subagentMaxSteps
        val virtualScreen = payload["virtual_screen"]?.jsonPrimitive?.booleanOrNull ?: false
        val runner = subagentRunner(sessionId, currentState(), defaultHistory)

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
                    task = task,
                    inheritHistory = false,
                    allowMutation = false,
                    backgroundJobId = jobId,
                    modelOverride = null,
                    maxSteps = maxSteps,
                    virtualScreen = virtualScreen,
                ).requireCompletedOutput()
            }
        }
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
        const val PERSISTENT_SUBAGENT_RESUME_VERSION = 1
    }
}
