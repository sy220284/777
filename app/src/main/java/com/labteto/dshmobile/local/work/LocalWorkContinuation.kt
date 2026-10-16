package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.agent.QueuedAgentInputKind
import com.labteto.dshmobile.harness.agent.AgentContinuationRef
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.LocalModelException
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val INTERNAL_WORK_CONTINUATION_PROMPT =
    "恢复同一工作任务。只处理检查点之后尚未完成的部分；已记录的工具回执、审批和副作用不可重复执行。先核对实际进度，再继续。"

/** Typed, checkpoint-addressed command; internal resume never masquerades as new user input. */
internal data class LocalWorkResumeCommand(
    val sourceRunId: String,
    val checkpointSequence: Long,
    val failureCode: String,
    val index: Int,
    val sourceMessageId: String?,
    val prompt: String = INTERNAL_WORK_CONTINUATION_PROMPT,
    val goalId: String? = null,
    val goalFingerprint: String? = null,
) {
    init {
        require(sourceRunId.isNotBlank() && checkpointSequence >= 0L && index > 0)
    }

    val id: String get() = "continuation-$sourceRunId-$index"

    fun toQueueInput(): QueuedAgentInput = QueuedAgentInput(
        content = prompt,
        memoryInput = "",
        modelMessage = buildJsonObject {
            put("role", "system")
            put("content", prompt)
        },
        id = id,
        kind = QueuedAgentInputKind.WORK_CONTINUATION,
        continuation = AgentContinuationRef(
            sourceRunId, checkpointSequence, failureCode, index, sourceMessageId,
            goalId, goalFingerprint,
        ),
    )
}

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
    sourceMessageId: String? = null,
): Boolean {
    if (
        !shouldAutoContinueWorkFailure(
            error = error,
            automaticContinuationCount = binding.automaticContinuationCount,
            pendingInputs = binding.runHandle.pendingInputs.size(),
        )
    ) {
        return false
    }

    val command = LocalWorkResumeCommand(
        sourceRunId = sourceRunId,
        checkpointSequence = binding.eventLog.latestSequence(),
        failureCode = error.code,
        index = binding.automaticContinuationCount + 1,
        sourceMessageId = sourceMessageId,
    )
    val queued = command.toQueueInput()
    // The user may queue a new instruction after the earlier pending-count check.
    // Keep failure recovery behind the same atomic user-priority gate as Goal progress.
    val accepted = binding.runHandle.pendingInputs.offerIfEmpty(queued) {
        // One authoritative inbox snapshot is committed while the queue is locked.
        // A process restart can reconstruct the typed continuation before running it.
        binding.eventLog.append(
            LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent(
                action = "work-continuation-queued",
                pending = binding.runHandle.pendingInputs.snapshot(),
                affected = listOf(queued),
            ),
        )
    }
    if (!accepted) return false
    // Additional lifecycle annotation; the inbox event above owns durable recovery.
    binding.eventLog.append("turn/continuation-queued", buildJsonObject {
        put("source_run_id", command.sourceRunId)
        put("reason", command.failureCode)
        put("failure_kind", com.labteto.dshmobile.local.model.modelFailureKind(error))
        put("continuation_id", command.id)
        put("continuation_index", command.index)
        put("checkpoint_sequence", command.checkpointSequence)
        put("source_message_id", command.sourceMessageId)
        put("origin", "WORK_CONTINUATION")
    })

    binding.automaticContinuationCount = command.index
    binding.continuationParentRunId = command.sourceRunId
    binding.state.update {
        it.copy(
            kernel = it.kernel.copy(queuedInputCount = binding.runHandle.pendingInputs.size()),
            error = null,
        )
    }
    return true
}
