package com.labteto.dshmobile.local.context

/** Neutral request-pressure estimate; provider-reported usage remains the accounting fact. */
internal data class LocalPromptPressure(
    val contextChars: Int,
    val estimatedInputTokens: Int,
    val systemTokens: Int,
    val historyTokens: Int,
    val currentUserTokens: Int,
    val toolDefinitionTokens: Int,
    val operationalLimitTokens: Int,
    val modelContextWindowTokens: Int? = null,
) {
    val remainingOperationalTokens: Int
        get() = (operationalLimitTokens - estimatedInputTokens).coerceAtLeast(0)

    val usageRatio: Double
        get() = if (operationalLimitTokens <= 0) 1.0
        else estimatedInputTokens.toDouble() / operationalLimitTokens.toDouble()
}
