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
    val executionId: String? = null,
)

data class SessionRepairResult(
    val appended: List<SessionEvent> = emptyList(),
    val toolResults: List<RecoveredToolResult> = emptyList(),
) {
    val repaired: Boolean get() = appended.isNotEmpty()
}

/**
 * Closes a persisted tail turn after process loss without guessing whether a side-effecting tool
 * completed. An admitted mutation (or legacy admission without a read-only contract) becomes
 * TOOL_OUTCOME_UNKNOWN. A verified read remains safely retryable; unadmitted calls are NOT_STARTED.
 */
object SessionRecovery {
    const val TOOL_NOT_STARTED = "TOOL_NOT_STARTED"
    const val TOOL_OUTCOME_UNKNOWN = "TOOL_OUTCOME_UNKNOWN"
    const val TOOL_READ_RESULT_UNKNOWN = "TOOL_READ_RESULT_UNKNOWN"

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
        executionId: String? = null,
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
        return RecoveredToolResult(callId, name, step, code, content, modelContent, executionId)
    }

    fun repairInterruptedTail(log: SessionEventLog): SessionRepairResult = repairInterruptedTail(
        withWriterLease = { task -> log.withWriterLease { task() } },
        latest = { type -> log.latest(type) },
        forEachAfter = log::forEachAfter,
        append = log::append,
    )

    /**
     * The recovery algorithm is independent of the durable backend. SQLite and
     * native JSONL use the same reducer and interrupted-tool policy; each backend
     * supplies a cross-process writer lease covering check, fold and receipts.
     */
    fun repairInterruptedTail(
        withWriterLease: ((() -> SessionRepairResult) -> SessionRepairResult),
        latest: (String) -> SessionEvent?,
        forEachAfter: (Long, (SessionEvent) -> Unit) -> Unit,
        append: (String, JsonObject) -> SessionEvent,
    ): SessionRepairResult = withWriterLease {
        repairInterruptedTailUnderLease(latest, forEachAfter, append)
    }

    private fun repairInterruptedTailUnderLease(
        latest: (String) -> SessionEvent?,
        forEachAfter: (Long, (SessionEvent) -> Unit) -> Unit,
        append: (String, JsonObject) -> SessionEvent,
    ): SessionRepairResult {
        // A restart only needs to inspect the newest unfinished turn. Replaying the complete
        // append-only archive here used to duplicate years of session history in the Android heap
        // and could OOM before the Harness reached its incremental projection checkpoints.
        val turnStart = latest("turn/start") ?: return SessionRepairResult()
        val turnEnd = latest("turn/end")
        if (turnEnd != null && turnEnd.sequence > turnStart.sequence) {
            return SessionRepairResult()
        }

        val latestStepStart = latest("step/start")?.takeIf {
            it.sequence > turnStart.sequence
        }
        val latestStepNumber = latestStepStart?.data?.get("step")?.jsonPrimitive?.intOrNull
        val latestStepEnd = latest("step/end")
        val endedCurrentStep = latestStepStart != null && latestStepEnd != null &&
            latestStepEnd.sequence > latestStepStart.sequence &&
            // Legacy step/end omitted the step number. A numbered end, however,
            // must match the newest start; stale end events cannot close it.
            (latestStepEnd.data["step"]?.jsonPrimitive?.intOrNull?.let { it == latestStepNumber }
                ?: true)
        val openStep = latestStepNumber.takeUnless { endedCurrentStep }
        val pending = ToolAttemptLedger()
        if (latestStepStart != null) {
            // A durable step/end cannot prove an earlier tool result was flushed.
            // Always fold the newest step's tool facts, even when it has an end,
            // so a crash between the two writes cannot silently lose a mutation.
            forEachAfter(latestStepStart.sequence) { event ->
                pending.observe(event, latestStepNumber)
            }
        }

        val appended = mutableListOf<SessionEvent>()
        val recovered = mutableListOf<RecoveredToolResult>()
        for (call in pending.pending()) {
            val result = interruptedToolResult(
                callId = call.id,
                name = call.name,
                step = call.step,
                started = call.started,
                verifiedReadOnly = call.verifiedReadOnly,
                executionId = call.executionId,
            )
            recovered += result
            appended += append("tool/result", buildJsonObject {
                result.step?.let { put("step", it) }
                put("id", result.callId)
                result.executionId?.let { put("execution_id", it) }
                result.name?.let { put("name", it) }
                put("content", result.content)
                put("model_content", result.modelContent)
                put("is_error", true)
                put("retryable", result.code != TOOL_OUTCOME_UNKNOWN)
                put("side_effect", if (result.code == TOOL_OUTCOME_UNKNOWN) "possible" else "none")
                put("error_code", result.code)
                put("recovered", true)
            })
        }
        openStep?.let { step ->
            appended += append("step/end", buildJsonObject {
                put("step", step)
                put("recovered", true)
            })
        }
        appended += append("turn/end", buildJsonObject {
            put("reason", "interrupted")
            put("recovered", true)
        })
        return SessionRepairResult(appended, recovered)
    }
}
