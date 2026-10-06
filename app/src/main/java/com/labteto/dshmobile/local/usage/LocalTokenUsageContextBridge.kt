package com.labteto.dshmobile.local.usage

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.runtime.LOCAL_AGENT_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.runtime.LOCAL_AUTOMATION_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.runtime.LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Feature-agnostic Session facts required for usage attribution. */
internal data class LocalTokenUsageSessionFacts(
    val mode: LocalUsageMode? = null,
    val title: String? = null,
)

/**
 * Resolves model-consuming tool calls back to their durable run/session identity without reading
 * the app-wide aggregate state. Product owners provide only the neutral Session facts required for
 * usage attribution.
 */
internal class LocalTokenUsageContextBridge(
    private val sessionFacts: (String) -> LocalTokenUsageSessionFacts,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val currentSessionId: () -> String,
) {
    fun resolve(
        sessionId: String?,
        callId: String?,
        action: TokenUsageAction,
        fallbackTaskLabel: String? = null,
    ): TokenUsageContext {
        val resolvedSessionId = sessionId?.takeIf(String::isNotBlank) ?: currentSessionId()
        val facts = sessionFacts(resolvedSessionId)
        val checkpoint = callId?.takeIf(String::isNotBlank)?.let { targetCallId ->
            eventLogFor(resolvedSessionId).latestMatching(TOOL_USAGE_CHECKPOINT_TYPES) { data ->
                data["call_id"]?.jsonPrimitive?.contentOrNull == targetCallId
            }?.data
        }
        val runId = checkpoint?.get("run_id")?.jsonPrimitive?.contentOrNull
        return TokenUsageContext(
            mode = facts.mode,
            sessionId = resolvedSessionId,
            sessionTitle = facts.title?.takeIf(String::isNotBlank),
            turnId = runId,
            runId = runId,
            parentRunId = checkpoint?.get("parent_run_id")?.jsonPrimitive?.contentOrNull,
            runKind = checkpoint?.get("run_kind")?.jsonPrimitive?.contentOrNull,
            agentId = checkpoint?.get("agent_id")?.jsonPrimitive?.contentOrNull,
            taskLabel = checkpoint?.get("input")?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: fallbackTaskLabel?.trim()?.takeIf(String::isNotBlank)?.take(120),
            step = checkpoint?.get("step")?.jsonPrimitive?.intOrNull,
            action = action,
        )
    }
}

private val TOOL_USAGE_CHECKPOINT_TYPES = setOf(
    LOCAL_AGENT_RUN_CHECKPOINT_EVENT,
    LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT,
    LOCAL_AUTOMATION_RUN_CHECKPOINT_EVENT,
)
