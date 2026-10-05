package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Shared authority for the visible foreground model-history projection.
 *
 * Feature code can update the already-owned foreground history without reaching through
 * LocalHarnessEngine. Durable Session EventLog remains authoritative for recovery.
 */
@Singleton
internal class LocalForegroundModelHistoryRuntime @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
) {
    internal val history: LocalModelHistoryBuffer
        get() = runtimeStateStore.foregroundRunHandle.modelHistory

    internal fun reset(expectedSessionId: String, messages: List<JsonObject>): Boolean {
        if (runtimeStateStore.state.value.sessionId != expectedSessionId) return false
        history.reset(messages)
        refreshMetrics(expectedSessionId)
        return true
    }

    internal fun ensureSystemPrompt(
        expectedSessionId: String,
        content: String,
    ): Boolean {
        if (runtimeStateStore.state.value.sessionId != expectedSessionId) return false
        if (history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") return true
        history.prepend(buildJsonObject {
            put("role", "system")
            put("content", content)
        })
        sessionStorage.eventLogs.get(expectedSessionId).append(
            "system/prompt",
            buildJsonObject { put("content", content) },
        )
        refreshMetrics(expectedSessionId)
        return true
    }

    internal fun compactChatIfNeeded(
        expectedSessionId: String,
        extraTokens: Int = 0,
    ): Boolean {
        val state = runtimeStateStore.state.value
        if (state.sessionId != expectedSessionId) return false
        val budget = runtimeStateStore.historyBudgetFor(
            state,
            runtimeStateStore.resourceSnapshot(),
        )
        val compaction = history.compact(
            compactor = compactor,
            budget = budget,
            extraTokens = extraTokens,
            summaryMode = LocalHistorySummaryMode.CHAT,
        ) ?: return refreshMetrics(expectedSessionId)

        runtimeStateStore.requestPressureStore.advanceGeneration(
            expectedSessionId,
            compaction.estimatedTokensAfter,
        )
        val eventLog = sessionStorage.eventLogs.get(expectedSessionId)
        eventLog.append("session/compaction", buildJsonObject {
            put("omitted_messages", compaction.omittedMessages)
            put("summary", compaction.summary)
            put("estimated_tokens_before", compaction.estimatedTokensBefore)
            put("estimated_tokens_after", compaction.estimatedTokensAfter)
            put("extra_request_tokens", extraTokens)
            put("work_steady_state", false)
        })
        checkpoint(expectedSessionId, "session/compaction")
        refreshMetrics(expectedSessionId)
        sessionStorage.enqueueCurrentSnapshot(expectedSessionId)
        return true
    }

    internal fun checkpointAtTurnBoundary(
        expectedSessionId: String,
        reason: String,
    ): Boolean {
        if (runtimeStateStore.state.value.sessionId != expectedSessionId) return false
        runtimeStateStore.foregroundRunHandle.turnsSinceModelHistoryCheckpoint += 1
        if (
            runtimeStateStore.foregroundRunHandle.turnsSinceModelHistoryCheckpoint >=
                MODEL_HISTORY_CHECKPOINT_TURN_INTERVAL
        ) {
            return checkpoint(expectedSessionId, reason)
        }
        return true
    }

    internal fun replaceSystemPrompt(expectedSessionId: String, content: String): Boolean {
        if (runtimeStateStore.state.value.sessionId != expectedSessionId) return false
        val system = buildJsonObject {
            put("role", "system")
            put("content", content)
        }
        if (history.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
            history.replaceSystem(system)
        } else {
            history.prepend(system)
        }
        refreshMetrics(expectedSessionId)
        return true
    }

    internal fun refreshMetrics(expectedSessionId: String): Boolean {
        val state = runtimeStateStore.state.value
        if (state.sessionId != expectedSessionId) return false
        val budget = runtimeStateStore.contextBudgetCharsFor(
            state,
            runtimeStateStore.resourceSnapshot(),
        )
        runtimeStateStore.projection.updateContextMetrics(
            sessionId = expectedSessionId,
            contextChars = history.encodedChars,
            contextBudgetChars = budget,
        )
        return true
    }

    internal fun checkpoint(expectedSessionId: String, reason: String): Boolean {
        if (runtimeStateStore.state.value.sessionId != expectedSessionId) return false
        val eventLog = sessionStorage.eventLogs.get(expectedSessionId)
        eventLog.append(
            ModelHistoryCheckpointCodec.EVENT_TYPE,
            codec.encode(durableModelHistorySnapshot(history.snapshot()), reason),
        )
        runtimeStateStore.foregroundRunHandle.turnsSinceModelHistoryCheckpoint = 0
        return true
    }

    private val codec = ModelHistoryCheckpointCodec()
    private val compactor = LocalHistoryCompactor()
}
