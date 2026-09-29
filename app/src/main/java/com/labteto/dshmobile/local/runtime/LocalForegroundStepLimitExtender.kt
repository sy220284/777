package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentStepLimitExtender
import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the foreground soft-step extender without making LocalHarnessEngine own budget policy.
 */
internal fun localForegroundStepLimitExtender(
    enabled: Boolean,
    configuredBase: Int,
    task: String,
    state: () -> LocalHarnessState,
    pressure: () -> HarnessResourcePressure,
    onExtended: (JsonObject) -> Unit,
): AgentStepLimitExtender? {
    if (!enabled) return null
    return AgentStepLimitExtender { currentLimit, stepsUsed ->
        val current = state()
        val livePressure = pressure()
        val next = nextAdaptiveAgentStepLimit(
            currentLimit = currentLimit,
            configuredBase = configuredBase,
            task = task,
            contextChars = current.contextChars,
            contextBudgetChars = current.contextBudgetChars,
            pressure = livePressure,
            kind = LocalAgentRunKind.FOREGROUND,
        )
        if (next != null && next > currentLimit) {
            onExtended(
                buildJsonObject {
                    put("steps_used", stepsUsed)
                    put("previous_limit", currentLimit)
                    put("next_limit", next)
                    put("context_chars", current.contextChars)
                    put("context_budget_chars", current.contextBudgetChars)
                    put("resource_pressure", livePressure.name.lowercase())
                },
            )
        }
        next
    }
}
