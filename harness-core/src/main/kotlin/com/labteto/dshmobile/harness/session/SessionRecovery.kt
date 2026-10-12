package com.labteto.dshmobile.harness.session

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.agent.modelVisibleContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RecoveredToolResult(
    val callId: String,
    val name: String?,
    val step: Int?,
    val code: String,
    val content: String,
    val modelContent: String,
)

data class SessionRepairResult(
    val appended: List<SessionEvent> = emptyList(),
    val toolResults: List<RecoveredToolResult> = emptyList(),
) {
    val repaired: Boolean get() = appended.isNotEmpty()
}

/**
 * Closes a persisted tail turn after process loss without guessing whether a side-effecting tool
 * completed. A recorded tool/call becomes TOOL_OUTCOME_UNKNOWN; an assistant-declared call that
 * never reached tool/call becomes TOOL_NOT_STARTED.
 */
object SessionRecovery {
    private const val MODEL_TOOL_CALLS_EVENT_KEY = "model_tool_calls"
    const val TOOL_NOT_STARTED = "TOOL_NOT_STARTED"
    const val TOOL_OUTCOME_UNKNOWN = "TOOL_OUTCOME_UNKNOWN"
    const val TOOL_READ_RESULT_UNKNOWN = "TOOL_READ_RESULT_UNKNOWN"

    private data class PendingCall(
        val id: String,
        var name: String?,
        var step: Int?,
        var started: Boolean,
    )

    /**
     * Build the model-visible settlement for a tool call interrupted before a durable result exists.
     *
     * The same semantic is used both for process-loss recovery and for an in-process cancelled turn,
     * so a continuation never sees an assistant tool call without a matching tool result.
     */
    fun interruptedToolResult(
        callId: String,
        name: String?,
        step: Int?,
        started: Boolean,
        verifiedReadOnly: Boolean = false,
    ): RecoveredToolResult {
        val code = when {
            !started -> TOOL_NOT_STARTED
            verifiedReadOnly -> TOOL_READ_RESULT_UNKNOWN
            else -> TOOL_OUTCOME_UNKNOWN
        }
        val content = when (code) {
            TOOL_OUTCOME_UNKNOWN ->
                "工具已开始执行，但缺少持久化结果。可能存在外部副作用，先核实真实状态，不得盲目重复写入。"
            TOOL_READ_RESULT_UNKNOWN ->
                "已核验此工具是只读调用，但中断前没有完整结果；原结果不能宣称成功。可按当前需求重新读取。"
            else -> "工具在中断前尚未开始。如任务仍需要，可重新执行。"
        }
        val modelContent = AgentToolResult(
            content = content,
            isError = true,
            errorCode = code,
            retryable = code != TOOL_OUTCOME_UNKNOWN,
            sideEffect = if (code == TOOL_OUTCOME_UNKNOWN) {
                AgentToolSideEffect.POSSIBLE
            } else {
                AgentToolSideEffect.NONE
            },
            recoveryHint = if (code == TOOL_OUTCOME_UNKNOWN) {
                "先检查外部状态，再决定是否重试。"
            } else {
                "本次操作尚无成功结果，可安全重新执行只读操作或未开始的调用。"
            },
        ).modelVisibleContent()
        return RecoveredToolResult(callId, name, step, code, content, modelContent)
    }

    fun repairInterruptedTail(log: SessionEventLog): SessionRepairResult {
        // A restart only needs to inspect the newest unfinished turn. Replaying the complete
        // append-only archive here used to duplicate years of session history in the Android heap
        // and could OOM before the Harness reached its incremental projection checkpoints.
        val turnStart = log.latest("turn/start") ?: return SessionRepairResult()
        val turnEnd = log.latest("turn/end")
        if (turnEnd != null && turnEnd.sequence > turnStart.sequence) {
            return SessionRepairResult()
        }

        val latestStepStart = log.latest("step/start")
        val latestStepEnd = log.latest("step/end")
        val openStepEvent = latestStepStart?.takeIf { stepStart ->
            stepStart.sequence > turnStart.sequence &&
                (latestStepEnd == null || latestStepEnd.sequence < stepStart.sequence)
        }
        val openStep = openStepEvent?.data?.get("step")?.jsonPrimitive?.intOrNull
        val pending = linkedMapOf<String, PendingCall>()

        if (openStepEvent != null) {
            // Stream only the current open step. No List of historical SessionEvent objects is
            // created, and events from completed steps cannot leak pending calls into recovery.
            log.forEachAfter(openStepEvent.sequence) { event ->
                when (event.type) {
                    "assistant/message" -> {
                        val genericCalls = event.data[MODEL_TOOL_CALLS_EVENT_KEY] as? JsonArray
                        if (genericCalls != null) {
                            for (element in genericCalls) {
                                val call = element as? JsonObject ?: continue
                                val id = call["id"]?.jsonPrimitive?.contentOrNull ?: continue
                                val name = call["name"]?.jsonPrimitive?.contentOrNull
                                pending[id] = PendingCall(id, name, openStep, started = false)
                            }
                        } else {
                            // Backward compatibility for sessions written before provider-neutral
                            // model metadata was added.
                            val legacyCalls = event.data["tool_calls"] as? JsonArray ?: return@forEachAfter
                            for (element in legacyCalls) {
                                val call = element as? JsonObject ?: continue
                                val id = call["id"]?.jsonPrimitive?.contentOrNull ?: continue
                                val function = call["function"] as? JsonObject
                                val name = function?.get("name")?.jsonPrimitive?.contentOrNull
                                pending[id] = PendingCall(id, name, openStep, started = false)
                            }
                        }
                    }
                    "tool/call" -> {
                        val id = event.data["id"]?.jsonPrimitive?.contentOrNull ?: return@forEachAfter
                        val name = event.data["name"]?.jsonPrimitive?.contentOrNull
                        val step = event.data["step"]?.jsonPrimitive?.intOrNull ?: openStep
                        val legacyOrStarted = event.data["execution_started"]?.jsonPrimitive?.booleanOrNull ?: true
                        val existing = pending[id]
                        if (existing == null) {
                            pending[id] = PendingCall(id, name, step, started = legacyOrStarted)
                        } else {
                            existing.name = name ?: existing.name
                            existing.step = step ?: existing.step
                            existing.started = existing.started || legacyOrStarted
                        }
                    }
                    "tool/execution-started" -> {
                        val id = event.data["id"]?.jsonPrimitive?.contentOrNull ?: return@forEachAfter
                        val name = event.data["name"]?.jsonPrimitive?.contentOrNull
                        val existing = pending[id]
                        if (existing == null) {
                            pending[id] = PendingCall(id, name, openStep, started = true)
                        } else {
                            existing.name = name ?: existing.name
                            existing.started = true
                        }
                    }
                    "tool/result" -> {
                        event.data["id"]?.jsonPrimitive?.contentOrNull?.let(pending::remove)
                    }
                }
            }
        }

        val appended = mutableListOf<SessionEvent>()
        val recovered = mutableListOf<RecoveredToolResult>()
        for (call in pending.values) {
            val result = interruptedToolResult(
                callId = call.id,
                name = call.name,
                step = call.step,
                started = call.started,
            )
            recovered += result
            appended += log.append("tool/result", buildJsonObject {
                result.step?.let { put("step", it) }
                put("id", result.callId)
                result.name?.let { put("name", it) }
                put("content", result.content)
                put("model_content", result.modelContent)
                put("is_error", true)
                put("error_code", result.code)
                put("recovered", true)
            })
        }
        openStep?.let { step ->
            appended += log.append("step/end", buildJsonObject {
                put("step", step)
                put("recovered", true)
            })
        }
        appended += log.append("turn/end", buildJsonObject {
            put("reason", "interrupted")
            put("recovered", true)
        })
        return SessionRepairResult(appended, recovered)
    }
}
