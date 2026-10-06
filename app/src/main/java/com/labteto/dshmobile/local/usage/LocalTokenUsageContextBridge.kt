package com.labteto.dshmobile.local.usage

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.buildToolTokenUsageContext
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.coroutines.flow.StateFlow

/**
 * Resolves model-consuming tool calls back to the Work run that triggered them.
 *
 * Keeping this lookup outside the Runtime Kernel prevents usage analytics from adding another
 * orchestration responsibility to the engine while preserving session/run/subagent attribution.
 */
internal class LocalTokenUsageContextBridge(
    private val state: StateFlow<LocalHarnessState>,
    private val runState: (String) -> LocalHarnessState?,
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
        val snapshot = runState(resolvedSessionId)
            ?: state.value.copy(sessionId = resolvedSessionId)
        return buildToolTokenUsageContext(
            snapshot = snapshot,
            eventLog = eventLogFor(resolvedSessionId),
            sessionId = resolvedSessionId,
            callId = callId,
            action = action,
            fallbackTaskLabel = fallbackTaskLabel,
        )
    }
}
