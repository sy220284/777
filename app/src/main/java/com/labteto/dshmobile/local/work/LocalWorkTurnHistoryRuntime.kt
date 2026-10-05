package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.harness.tools.ToolResultRetention
import com.labteto.dshmobile.local.LocalHistoryBudget
import com.labteto.dshmobile.local.LocalToolOutputStore
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.adaptiveToolResultBudget
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.compact
import com.labteto.dshmobile.local.model.compactOverflow
import com.labteto.dshmobile.local.model.durableModelHistorySnapshot
import com.labteto.dshmobile.local.model.projectRecoverableToolResult
import com.labteto.dshmobile.local.model.workSystemPrompt
import com.labteto.dshmobile.local.runtime.MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.structuredWorkState
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Work-owned model-history lifecycle for one bound foreground run.
 *
 * The binding owns its mutable history and projection; Shared Runtime supplies only resource
 * pressure and request-pressure facts. Session Event remains the durable checkpoint authority.
 */
internal class LocalWorkTurnHistoryRuntime(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val workspacePath: String,
    private val toolOutputStore: LocalToolOutputStore,
) {
    private val compactor = LocalHistoryCompactor()
    private val checkpointCodec = ModelHistoryCheckpointCodec()

    internal fun ensureSystemMessage(binding: LocalWorkRunBinding) {
        val history = binding.runHandle.modelHistory
        if (history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") return
        val prompt = workSystemPrompt(workspacePath, binding.state.value.work.planMode)
        history.prepend(buildJsonObject {
            put("role", "system")
            put("content", prompt)
        })
        binding.eventLog.append("system/prompt", buildJsonObject { put("content", prompt) })
        updateContextMetrics(binding)
    }

    internal fun currentBudget(binding: LocalWorkRunBinding): LocalHistoryBudget =
        runtimeStateStore.historyBudgetFor(
            binding.aggregateSnapshot(),
            runtimeStateStore.resourceSnapshot(),
        )

    internal fun updateContextMetrics(binding: LocalWorkRunBinding) {
        val budget = currentBudget(binding)
        val history = binding.runHandle.modelHistory
        binding.state.update { current ->
            current.copy(
                kernel = current.kernel.copy(
                    contextChars = history.encodedChars,
                    contextBudgetChars = budget.maxHistoryChars,
                ),
            )
        }
    }

    internal fun compactIfNeeded(
        binding: LocalWorkRunBinding,
        extraTokens: Int = 0,
    ) {
        val baseBudget = currentBudget(binding)
        val history = binding.runHandle.modelHistory
        val snapshot = binding.aggregateSnapshot()
        val budget = workSteadyStateHistoryBudget(
            base = baseBudget,
            currentHistoryTokens = history.estimatedTokens,
            extraTokens = extraTokens,
            state = snapshot,
        )
        val workSteadyStateApplied = budget.maxHistoryTokens != baseBudget.maxHistoryTokens
        val compaction = history.compact(
            compactor = compactor,
            budget = budget,
            extraTokens = extraTokens,
            summaryMode = LocalHistorySummaryMode.WORK,
            structuredWorkState = structuredWorkState(snapshot, binding.eventLog),
        ) ?: run {
            updateContextMetrics(binding)
            return
        }

        runtimeStateStore.requestPressureStore.advanceGeneration(
            binding.sessionId,
            compaction.estimatedTokensAfter,
        )
        binding.eventLog.append("session/compaction", buildJsonObject {
            put("omitted_messages", compaction.omittedMessages)
            put("summary", compaction.summary)
            put("estimated_tokens_before", compaction.estimatedTokensBefore)
            put("estimated_tokens_after", compaction.estimatedTokensAfter)
            put("extra_request_tokens", extraTokens)
            put("work_steady_state", workSteadyStateApplied)
            budget.maxHistoryTokens?.let { put("history_budget_tokens", it) }
            budget.tailTokens?.let { put("tail_budget_tokens", it) }
        })
        checkpoint(binding, "session/compaction")
        updateContextMetrics(binding)
        persist(binding)
    }

    internal fun persistOverflowCompaction(
        snapshot: com.labteto.dshmobile.local.LocalHarnessState,
        summaryMode: LocalHistorySummaryMode,
        binding: LocalWorkRunBinding,
    ) {
        if (snapshot.sessionId != binding.sessionId) return
        val history = binding.runHandle.modelHistory
        val compaction = history.compactOverflow(
            compactor = compactor,
            summaryMode = summaryMode,
        ) ?: return

        runtimeStateStore.requestPressureStore.advanceGeneration(
            binding.sessionId,
            compaction.estimatedTokensAfter,
        )
        binding.eventLog.append("session/compaction", buildJsonObject {
            put("trigger", "context-overflow")
            put("omitted_messages", compaction.omittedMessages)
            put("summary", compaction.summary)
            put("estimated_tokens_before", compaction.estimatedTokensBefore)
            put("estimated_tokens_after", compaction.estimatedTokensAfter)
        })
        checkpoint(binding, "session/context-overflow")
        updateContextMetrics(binding)
        persist(binding)
    }

    internal fun retainToolResult(
        binding: LocalWorkRunBinding,
        callId: String?,
        result: String,
        retention: ToolResultRetention = ToolResultRetention.DURABLE,
    ): String {
        val history = binding.runHandle.modelHistory
        val budget = adaptiveToolResultBudget(
            base = currentBudget(binding),
            currentHistoryChars = history.encodedChars,
            currentHistoryTokens = history.estimatedTokens,
        )
        return projectRecoverableToolResult(
            value = result,
            retention = retention,
            usageMode = LocalUsageMode.WORK,
            budget = budget,
            callId = callId,
            spill = { id, value ->
                toolOutputStore.store(binding.sessionId, id, value) != null
            },
        ).text
    }

    internal fun checkpoint(binding: LocalWorkRunBinding, reason: String) {
        binding.eventLog.append(
            ModelHistoryCheckpointCodec.EVENT_TYPE,
            checkpointCodec.encode(
                durableModelHistorySnapshot(binding.runHandle.modelHistory.snapshot()),
                reason,
            ),
        )
        binding.runHandle.turnsSinceModelHistoryCheckpoint = 0
    }

    internal fun checkpointAtTurnBoundary(
        binding: LocalWorkRunBinding,
        reason: String,
    ) {
        compactIfNeeded(binding)
        binding.runHandle.turnsSinceModelHistoryCheckpoint += 1
        if (
            binding.runHandle.turnsSinceModelHistoryCheckpoint >=
                MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL
        ) {
            checkpoint(binding, reason)
        }
    }

    internal fun persist(binding: LocalWorkRunBinding) {
        sessionStorage.enqueueSnapshot(binding.persistenceSnapshot())
    }
}
