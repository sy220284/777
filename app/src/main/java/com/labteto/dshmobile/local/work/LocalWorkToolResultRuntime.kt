package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.tools.ToolResultRetention
import com.labteto.dshmobile.local.LocalToolOutputStore
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.adaptiveToolResultBudget
import com.labteto.dshmobile.local.model.projectRecoverableToolResult
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Work-owned projection/spill policy for model-visible tool results. */
internal class LocalWorkToolResultRuntime(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val toolOutputStore: LocalToolOutputStore,
    val importToolImages: suspend (com.labteto.dshmobile.harness.tools.ToolRichContent?) -> List<com.labteto.dshmobile.local.attachment.LocalImportedAttachment> = { emptyList() },
) {
    internal fun retain(
        binding: LocalWorkRunBinding,
        callId: String?,
        result: String,
        retention: ToolResultRetention = ToolResultRetention.DURABLE,
        requireStored: Boolean = false,
        preview: String? = null,
    ): String {
        val history = binding.runHandle.modelHistory
        // Reference protection is derived from existing Work history/checkpoints, never a
        // separate authority for task facts. Eviction still honors private storage budgets.
        val snapshot = history.snapshot()
        val referencedIds = referencedWorkToolCallIds(snapshot)
        toolOutputStore.retainActiveReferences(
            binding.sessionId, referencedIds,
        )
        val budget = adaptiveToolResultBudget(
            base = runtimeStateStore.historyBudgetFor(
                binding.aggregateSnapshot(),
                runtimeStateStore.resourceSnapshot(),
            ),
            currentHistoryChars = history.encodedChars,
            currentHistoryTokens = history.estimatedTokens,
        )
        return projectRecoverableToolResult(
            value = result,
            requireStored = requireStored,
            preview = preview ?: result,
            retention = retention,
            usageMode = LocalUsageMode.WORK,
            budget = budget,
            callId = callId,
            spill = { id, value ->
                toolOutputStore.store(binding.sessionId, id, value) != null
            },
        ).text
    }
}

/**
 * Existing checkpoint facts remain readable for legacy sessions only. New checkpoints use
 * tool_call_ids, where human-readable text cannot manufacture a retention reference.
 */
internal fun referencedWorkToolCallIds(snapshot: List<kotlinx.serialization.json.JsonObject>): List<String> {
    val recent = snapshot.takeLast(80).mapNotNull { message ->
        // User messages with attachments have JSON-array content. They are not tool-output
        // references; requesting jsonPrimitive here throws and breaks EVERY later tool receipt.
        (message["content"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let {
            com.labteto.dshmobile.local.model.retainedToolOutputReference(it)
        } ?: (message["tool_call_id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }
    val checkpoint = LocalWorkCheckpoint.latestFrom(snapshot)
    val checkpointIds = if (checkpoint?.hasTypedToolReferences == true) {
        checkpoint.toolCallIds
    } else {
        checkpoint?.facts.orEmpty().mapNotNull { fact ->
            Regex("""(?:^|\s)call=([^\s]+)""").find(fact)?.groupValues?.getOrNull(1)
        }
    }
    return (recent + checkpointIds).filter(String::isNotBlank).distinct()
}
