package com.labteto.dshmobile.harness.session

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.agent.modelVisibleContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
    const val TOOL_NOT_STARTED = "TOOL_NOT_STARTED"
    const val TOOL_OUTCOME_UNKNOWN = "TOOL_OUTCOME_UNKNOWN"

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
    ): RecoveredToolResult {
        val code = if (started) TOOL_OUTCOME_UNKNOWN else TOOL_NOT_STARTED
        val content = if (started) {
            "该工具调用在中断前已记录为开始，但没有持久化结果。实际副作用未知；若工具可能改变状态，先检查外部状态，禁止盲目重试。"
        } else {
            "该工具调用在中断前尚未记录为开始。如任务仍需要，可重新执行。"
        }
        val modelContent = AgentToolResult(
            content = content,
            isError = true,
            errorCode = code,
            retryable = code == TOOL_NOT_STARTED,
            sideEffect = if (code == TOOL_OUTCOME_UNKNOWN) {
                AgentToolSideEffect.POSSIBLE
            } else {
                AgentToolSideEffect.NONE
            },
            recoveryHint = if (code == TOOL_OUTCOME_UNKNOWN) {
                "先检查外部状态，再决定是否重试。"
            } else {
                "如任务仍需要，可重新执行该工具。"
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
                        val calls = event.data["tool_calls"] as? JsonArray ?: return@forEachAfter
                        for (element in calls) {
                            val call = element as? JsonObject ?: continue
                            val id = call["id"]?.jsonPrimitive?.contentOrNull ?: continue
                            val function = call["function"] as? JsonObject
                            val name = function?.get("name")?.jsonPrimitive?.contentOrNull
                            pending[id] = PendingCall(id, name, openStep, started = false)
                        }
                    }
                    "tool/call" -> {
                        val id = event.data["id"]?.jsonPrimitive?.contentOrNull ?: return@forEachAfter
                        val name = event.data["name"]?.jsonPrimitive?.contentOrNull
                        val step = event.data["step"]?.jsonPrimitive?.intOrNull ?: openStep
                        val existing = pending[id]
                        if (existing == null) {
                            pending[id] = PendingCall(id, name, step, started = true)
                        } else {
                            existing.name = name ?: existing.name
                            existing.step = step ?: existing.step
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
