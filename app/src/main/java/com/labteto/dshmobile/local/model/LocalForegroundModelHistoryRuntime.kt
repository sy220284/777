package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
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
        get() = runtimeStateStore.foregroundModelHistory

    internal fun reset(expectedSessionId: String, messages: List<JsonObject>): Boolean {
        if (runtimeStateStore.state.value.sessionId != expectedSessionId) return false
        history.reset(messages)
        refreshMetrics(expectedSessionId)
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
        runtimeStateStore.foregroundTurnsSinceModelHistoryCheckpoint = 0
        return true
    }

    private val codec = ModelHistoryCheckpointCodec()
}
