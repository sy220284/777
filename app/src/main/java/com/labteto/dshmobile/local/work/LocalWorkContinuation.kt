package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val INTERNAL_WORK_CONTINUATION_PROMPT =
    "继续当前工作任务。上一执行片或模型请求中断；基于已有历史、检查点和工具结果继续未完成部分。禁止重做已经完成并有结果的工具调用，先核对现有进度再行动。"

/**
 * Queue a new Work turn instead of replaying the failed provider request.
 *
 * The continuation is a distinct model request with an explicit history boundary. It is only
 * admitted when the failed operation is marked continuation-safe, no user input is waiting and the
 * per-run continuation bound has not been exhausted.
 */
internal fun queueAutomaticWorkContinuation(
    binding: LocalWorkRunBinding,
    sourceRunId: String,
    error: LocalModelException,
): Boolean {
    if (
        !shouldAutoContinueWorkFailure(
            error = error,
            automaticContinuationCount = binding.automaticContinuationCount,
            pendingInputs = binding.pendingInputs.size(),
        )
    ) {
        return false
    }

    val continuationId = "continuation-$sourceRunId-${binding.automaticContinuationCount + 1}"
    val accepted = binding.pendingInputs.offer(
        QueuedAgentInput(
            content = INTERNAL_WORK_CONTINUATION_PROMPT,
            memoryInput = "",
            modelMessage = buildJsonObject {
                put("role", "user")
                put("content", INTERNAL_WORK_CONTINUATION_PROMPT)
            },
            id = continuationId,
        ),
    )
    if (!accepted) return false

    binding.automaticContinuationCount += 1
    binding.continuationParentRunId = sourceRunId
    binding.state.update {
        it.copy(
            error = null,
            queuedInputCount = binding.pendingInputs.size(),
        )
    }
    binding.eventLog.append("turn/continuation-queued", buildJsonObject {
        put("source_run_id", sourceRunId)
        put("reason", error.code)
        put("failure_kind", com.labteto.dshmobile.local.model.modelFailureKind(error))
        put("continuation_id", continuationId)
        put("continuation_index", binding.automaticContinuationCount)
    })
    return true
}
