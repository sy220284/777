package com.labteto.dshmobile.local.agent

/**
 * Agent runtime limit policy shared by Settings, recovery, foreground execution, and UI.
 *
 * Harness core validates positive values; product-side configuration and recovery normalize
 * through this Shared Agent capability so no lower layer depends on SettingsFeature internals.
 */
internal object LocalAgentRuntimeLimits {
    const val MAIN_MIN_STEPS = 4
    const val SUBAGENT_MIN_STEPS = 1
    const val MAX_CONFIGURED_STEPS = 512
    const val MODEL_ATTEMPTS_MIN = 1
    const val MODEL_ATTEMPTS_MAX = 5

    fun normalizeMainSteps(value: Int): Int =
        value.coerceIn(MAIN_MIN_STEPS, MAX_CONFIGURED_STEPS)

    fun normalizeSubagentSteps(value: Int): Int =
        value.coerceIn(SUBAGENT_MIN_STEPS, MAX_CONFIGURED_STEPS)

    fun normalizeModelAttempts(value: Int): Int =
        value.coerceIn(MODEL_ATTEMPTS_MIN, MODEL_ATTEMPTS_MAX)
}
