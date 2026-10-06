package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.harness.agent.AgentStepLimitExtender
import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
import com.labteto.dshmobile.local.LocalHarnessState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val MAX_FOREGROUND_DYNAMIC_STEPS = 512
internal const val FOREGROUND_TURN_TIMEOUT_MILLIS = 15 * 60_000L

/**
 * Builds the foreground soft-step extender without making the Runtime Kernel own budget policy.
 */
internal fun localForegroundStepLimitExtender(
    enabled: Boolean,
    configuredBase: Int,
    task: String,
    state: () -> LocalHarnessState,
    pressure: () -> HarnessResourcePressure,
    onExtended: (JsonObject) -> Unit,
    canExtend: () -> Boolean = { true },
    maxTotalSteps: Int = 512,
): AgentStepLimitExtender? {
    if (!enabled) return null
    return AgentStepLimitExtender { currentLimit, stepsUsed ->
        if (currentLimit >= maxTotalSteps) return@AgentStepLimitExtender null
        if (!canExtend()) return@AgentStepLimitExtender null
        val current = state()
        val livePressure = pressure()
        val next = nextAdaptiveAgentStepLimit(
            currentLimit = currentLimit,
            configuredBase = configuredBase,
            task = task,
            contextChars = current.kernel.contextChars,
            contextBudgetChars = current.kernel.contextBudgetChars,
            pressure = livePressure,
            kind = LocalAgentRunKind.FOREGROUND,
        )
        val boundedNext = next?.coerceAtMost(maxTotalSteps)
        if (boundedNext != null && boundedNext > currentLimit) {
            onExtended(
                buildJsonObject {
                    put("steps_used", stepsUsed)
                    put("previous_limit", currentLimit)
                    put("next_limit", boundedNext)
                    put("context_chars", current.kernel.contextChars)
                    put("context_budget_chars", current.kernel.contextBudgetChars)
                    put("resource_pressure", livePressure.name.lowercase())
                },
            )
        }
        boundedNext
    }
}
