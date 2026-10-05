package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.compact
import com.labteto.dshmobile.local.model.compactOverflow
import com.labteto.dshmobile.local.model.durableModelHistorySnapshot
import com.labteto.dshmobile.local.runtime.MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.model.workSystemPrompt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Work-owned durable model-history transaction boundary.
 *
 * A Work run owns its history/inbox projection while Shared Runtime supplies only resource budgets
 * and Session snapshot storage. Engine must not coordinate Work history compaction or checkpoints.
 */
@Singleton
internal class LocalWorkModelHistoryRuntime @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val workMemory: LocalWorkMemoryRuntime,
) {
    private val compactor = LocalHistoryCompactor()
    private val checkpointCodec = ModelHistoryCheckpointCodec()

    internal fun ensureSystemMessage(binding: LocalWorkRunBinding) {
        val history = binding.runHandle.modelHistory
        if (history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") return
        val prompt = workSystemPrompt(
            workspacePath = sessionStorage.files.workspace.path,
            planMode = binding.state.value.work.planMode,
        )
        history.prepend(
            buildJsonObject {
                put("role", "system")
                put("content", prompt)
            },
        )
        binding.eventLog.append("system/prompt", buildJsonObject { put("content", prompt) })
        updateContextMetrics(binding)
    }

    internal suspend fun drainPendingInputs(binding: LocalWorkRunBinding) {
        val pending = binding.runHandle.pendingInputs
        val queued = pending.drain()
        if (queued.isEmpty()) return

        val history = binding.runHandle.modelHistory
        val durableMessages = mutableListOf<JsonObject>()
        queued.forEach { input ->
            val durableMessage = input.modelMessage ?: buildJsonObject {
                put("role", "user")
                put("content", input.content)
            }
            history.append(durableMessage)
            durableMessages += durableMessage
            workMemory.captureAutoMemoryDirective(
                text = input.memoryInput,
                sourceMessageId = input.id,
                binding = binding,
            )
        }
        binding.state.update { current ->
            current.copy(
                kernel = current.kernel.copy(queuedInputCount = pending.size()),
            )
        }
        binding.eventLog.append(
            LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent(
                action = "claimed",
                pending = pending.snapshot(),
                affected = queued,
                modelMessages = durableMessages,
            ),
        )
        updateContextMetrics(binding)
        persist(binding)
    }

    internal fun updateContextMetrics(binding: LocalWorkRunBinding) {
        val resources = runtimeStateStore.resourceSnapshot()
        val budget = runtimeStateStore.historyBudgetFor(
            binding.aggregateSnapshot(),
            resources,
        )
        binding.state.update { current ->
            current.copy(
                kernel = current.kernel.copy(
                    contextChars = binding.runHandle.modelHistory.encodedChars,
                    contextBudgetChars = budget.maxHistoryChars,
                ),
            )
        }
    }

    internal fun compactIfNeeded(
        binding: LocalWorkRunBinding,
        extraTokens: Int = 0,
    ) {
        val snapshot = binding.aggregateSnapshot()
        val resources = runtimeStateStore.resourceSnapshot()
        val baseBudget = runtimeStateStore.historyBudgetFor(snapshot, resources)
        val history = binding.runHandle.modelHistory
        val budget = workSteadyStateHistoryBudget(
            base = baseBudget,
            currentHistoryTokens = history.estimatedTokens,
            extraTokens = extraTokens,
            state = snapshot,
        )
        val steadyStateApplied = budget.maxHistoryTokens != baseBudget.maxHistoryTokens
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
        binding.eventLog.append(
            "session/compaction",
            buildJsonObject {
                put("omitted_messages", compaction.omittedMessages)
                put("summary", compaction.summary)
                put("estimated_tokens_before", compaction.estimatedTokensBefore)
                put("estimated_tokens_after", compaction.estimatedTokensAfter)
                put("extra_request_tokens", extraTokens)
                put("work_steady_state", steadyStateApplied)
                budget.maxHistoryTokens?.let { put("history_budget_tokens", it) }
                budget.tailTokens?.let { put("tail_budget_tokens", it) }
            },
        )
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

    internal fun checkpoint(
        binding: LocalWorkRunBinding,
        reason: String,
    ) {
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
