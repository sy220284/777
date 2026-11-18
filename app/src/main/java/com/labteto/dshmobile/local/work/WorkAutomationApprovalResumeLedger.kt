package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.agent.modelVisibleContent
import com.labteto.dshmobile.harness.tools.ToolResultRetention
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.localToolHistoryMessage
import com.labteto.dshmobile.local.model.durableToolResultContent
import com.labteto.dshmobile.local.runtime.LocalHarnessBlockedException
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A precise, single-tool grant for a stopped background Work run.
 * No device-wide automatic approval, prompt replay or silent side-effect retries.
 */
@Singleton
internal class WorkAutomationApprovalResumeLedger private constructor(
    private val eventLogFor: (String) -> LocalSessionEventLog,
) {
    @Inject internal constructor(storage: LocalSessionStorageRuntime) : this(storage.eventLogs::get)
    /** File-backed EventLogs in JVM tests exercise the same durable event protocol. */
    internal constructor(eventLogFor: (String) -> LocalSessionEventLog, testOwner: Unit) : this(eventLogFor)
    internal data class Pending(
        val sessionId: String,
        val blockSequence: Long,
        val approvalSequence: Long,
        val call: LocalToolCall,
        val step: Int,
        val history: List<JsonObject>,
        val agentId: String,
    )
    internal data class Continuation(
        val history: List<JsonObject>,
        val step: Int,
        val callId: String,
    )
    private fun log(sessionId: String) = eventLogFor(sessionId)

    private fun callDigest(call: LocalToolCall): String {
        val bytes = (call.name + "\n" + call.id + "\n" + call.arguments.toString())
            .toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private data class Blocked(
        val sequence: Long,
        val call: LocalToolCall,
        val summary: String,
    )
    private fun latestBlocked(eventLog: LocalSessionEventLog): Blocked? {
        val event = eventLog.latest("approval/blocked") ?: return null
        val args = event.data["arguments"] as? JsonObject ?: return null
        val id = event.data["call_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: return null
        val name = event.data["tool"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: return null
        return Blocked(event.sequence, LocalToolCall(id,name,args,args.toString()),
            event.data["summary"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }
    private fun stepFor(log: LocalSessionEventLog, blocked: Blocked): Pair<Int,Pair<String,List<JsonObject>>>? {
        val event = log.latestMatching(setOf("automation/approval-step")) { data ->
            data["call_id"]?.jsonPrimitive?.contentOrNull == blocked.call.id
        } ?: return null
        if (event.sequence >= blocked.sequence) return null
        val raw = event.data["history"] as? JsonArray ?: return null
        val history = raw.mapNotNull { it as? JsonObject }
        if (history.size != raw.size || history.isEmpty()) return null
        val agent = event.data["agent_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: return null
        val step = event.data["step"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return null
        // The recovered model history must explicitly contain the declared call.
        // Otherwise a stale or forged event cannot authorize arbitrary execution.
        val assistant = history.lastOrNull {
            it["role"]?.jsonPrimitive?.contentOrNull == "assistant"
        } ?: return null
        val tools = assistant["tool_calls"] as? JsonArray ?: return null
        if (tools.none { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ==
                blocked.call.id }) return null
        return step to (agent to history)
    }

    /** Called only from the task's explicit review action, with task/generation fenced by UI. */
    fun authorize(sessionId: String): Boolean {
        require(sessionId.isNotBlank()) { "后台任务会话不存在" }
        val eventLog = log(sessionId)
        val blocked = latestBlocked(eventLog) ?: return false
        val step = stepFor(eventLog,blocked) ?: return false
        val latest = eventLog.latest("approval/resume-authorized")
        if (latest != null && latest.sequence > blocked.sequence) {
            // A WorkManager enqueue may fail after authorization was committed.
            // Let the user resubmit the SAME authorization until execution starts,
            // without silently broadening the grant or creating a second approval.
            return authorized(sessionId) != null &&
                eventOf(eventLog,"approval/resume-execution-started",blocked.sequence) == null
        }
        val finished = eventLog.latest("approval/resume-finalized")
        if (finished != null && finished.sequence > blocked.sequence) return false
        val previousExecution = eventLog.latestMatching(
            setOf("approval/resume-execution-started"),
        ) { it["blocked_sequence"]?.jsonPrimitive?.contentOrNull == blocked.sequence.toString() }
        if (previousExecution != null) return false
        eventLog.append("approval/resume-authorized", buildJsonObject {
            put("blocked_sequence", blocked.sequence)
            put("call_id", blocked.call.id)
            put("name", blocked.call.name)
            put("arguments_sha256", callDigest(blocked.call))
            put("step", step.first)
            put("approved_at", System.currentTimeMillis())
        })
        return true
    }

    /** No approval event means no replay privilege, even if global preferences changed. */
    fun authorized(sessionId: String): Pending? {
        val eventLog = log(sessionId)
        val blocked = latestBlocked(eventLog) ?: return null
        val approved = eventLog.latest("approval/resume-authorized") ?: return null
        if (approved.sequence <= blocked.sequence ||
            approved.data["blocked_sequence"]?.jsonPrimitive?.contentOrNull != blocked.sequence.toString() ||
            approved.data["call_id"]?.jsonPrimitive?.contentOrNull != blocked.call.id ||
            approved.data["name"]?.jsonPrimitive?.contentOrNull != blocked.call.name ||
            approved.data["arguments_sha256"]?.jsonPrimitive?.contentOrNull != callDigest(blocked.call))
            return null
        val finalized = eventLog.latest("approval/resume-finalized")
        if (finalized != null && finalized.sequence > approved.sequence) return null
        val step = stepFor(eventLog, blocked) ?: return null
        return Pending(sessionId, blocked.sequence, approved.sequence,
            blocked.call, step.first, step.second.second, step.second.first)
    }

    private fun eventOf(
        eventLog: LocalSessionEventLog, type: String, blockSequence: Long,
    ) = eventLog.latestMatching(setOf(type)) { data ->
        data["blocked_sequence"]?.jsonPrimitive?.contentOrNull == blockSequence.toString()
    }

    /** Executed inside the same Session runtime lease as the resumed model run.
     * Once STARTED is durable, an unknown result MUST be checked by the user,
     * even if WorkManager restarts between the external effect and its receipt.
     */
    suspend fun execute(pending: Pending, invoke: suspend (LocalToolCall) -> AgentToolResult): Continuation {
        val eventLog = log(pending.sessionId)
        val authorized = authorized(pending.sessionId)
        require(authorized?.approvalSequence == pending.approvalSequence) {
            "用户授权已过期，拒绝后台操作"
        }
        val recorded = eventOf(eventLog,"approval/resume-execution-finished",pending.blockSequence)
        val result = if (recorded != null) {
            val payload = recorded.data
            AgentToolResult(
                content = payload["content"]?.jsonPrimitive?.contentOrNull
                    ?: error("已完成工具操作的持久结果缺失"),
                isError = payload["is_error"]?.jsonPrimitive?.booleanOrNull ?: false,
                errorCode = payload["error_code"]?.jsonPrimitive?.contentOrNull,
                retryable = false,
                sideEffect = payload["side_effect"]?.jsonPrimitive?.contentOrNull?.let {
                    AgentToolSideEffect.valueOf(it.uppercase())
                } ?: AgentToolSideEffect.NONE,
            )
        } else {
            if (eventOf(eventLog,"approval/resume-execution-started",pending.blockSequence) != null) {
                throw LocalHarnessBlockedException(
                    "上次批准的工具操作已经开始，但尚无可靠执行回执；请先核对外部结果，再决定是否重新授权。",
                    pending.sessionId,
                )
            }
            eventLog.append("approval/resume-execution-started", buildJsonObject {
                put("blocked_sequence", pending.blockSequence)
                put("call_id", pending.call.id)
                put("arguments_sha256", callDigest(pending.call))
            })
            val actual = invoke(pending.call.copy(id =
                "approved:" + pending.blockSequence + ":" + pending.call.id))
            // Durable tool receipt precedes the next model request.
            eventLog.append("approval/resume-execution-finished", buildJsonObject {
                put("blocked_sequence", pending.blockSequence)
                put("call_id", pending.call.id)
                put("content", durableToolResultContent(actual.content,actual.retention))
                put("is_error", actual.isError)
                actual.errorCode?.let { put("error_code",it) }
                put("side_effect", actual.sideEffect.name.lowercase())
                put("authorization_sequence",pending.approvalSequence)
            })
            actual
        }
        return Continuation(
            history = pending.history + localToolHistoryMessage(
                pending.call.id,result.modelVisibleContent(),ToolResultRetention.DURABLE,
            ),
            step = pending.step,
            callId = pending.call.id,
        )
    }
    fun finalize(sessionId: String, pending: Pending, status: String, output: String) {
        log(sessionId).append("approval/resume-finalized",buildJsonObject {
            put("blocked_sequence",pending.blockSequence)
            put("authorization_sequence",pending.approvalSequence)
            put("status",status)
            put("output",output.take(4_000))
        })
    }
}
