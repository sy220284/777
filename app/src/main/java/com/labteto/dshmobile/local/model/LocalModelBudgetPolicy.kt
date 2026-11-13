package com.labteto.dshmobile.local.model

/**
 * Per-route execution policy shared by Chat's history governor and Work's request scheduler.
 *
 * A recognized model on an official MiniMax endpoint can use a larger local working set.
 * These are product budgets, never a statement about unlimited provider quota or free usage.
 * Custom gateways and unrecognized models retain the existing conservative defaults.
 */
internal data class LocalModelBudgetPolicy(
    val historyCharScale: Double = 1.0,
    val workRequestsPerSlice: Int = 128,
    val pendingWorkTokens: Long = 512_000L,
    val workProjectionTargetPermille: Int? = null,
    val workProjectionTriggerPermille: Int? = null,
)

internal object LocalModelBudgetPolicies {
    fun forRoute(model: String?, baseUrl: String?): LocalModelBudgetPolicy {
        if (model.isNullOrBlank() || baseUrl.isNullOrBlank() || !LocalMiniMaxPolicy.isOfficial(baseUrl)) {
            return LocalModelBudgetPolicy()
        }
        return when (model.trim().lowercase()) {
            "minimax-m3", "minimax-m3.1-flash", "minimax-m3.1-flash-preview" -> LocalModelBudgetPolicy(
                historyCharScale = 2.0,
                workRequestsPerSlice = 256,
                pendingWorkTokens = 1_000_000L,
                workProjectionTargetPermille = 200,
                workProjectionTriggerPermille = 300,
            )
            "minimax-m2", "minimax-m2.1", "minimax-m2.1-highspeed",
            "minimax-m2.5", "minimax-m2.5-highspeed", "minimax-m2.7",
            "minimax-m2.7-highspeed" -> LocalModelBudgetPolicy(
                historyCharScale = 1.4,
                workRequestsPerSlice = 160,
                pendingWorkTokens = 512_000L,
                workProjectionTargetPermille = 300,
                workProjectionTriggerPermille = 480,
            )
            else -> LocalModelBudgetPolicy()
        }
    }
}
