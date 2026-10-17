package com.labteto.dshmobile.local.model

sealed interface MiniMaxProviderUsage {
    data class TokenPlan(val models: List<ModelQuota>) : MiniMaxProviderUsage
    data class AccountBalance(
        val available: String,
        val cash: String,
        val voucher: String,
        val credit: String,
    ) : MiniMaxProviderUsage

    data class ModelQuota(
        val name: String,
        val interval: Window,
        val weekly: Window,
        val intervalResetMillis: Long?,
        val weeklyResetMillis: Long?,
    )

    data class Window(
        val remaining: Long?,
        val total: Long?,
        val remainingPercent: Double?,
        val unlimited: Boolean = false,
        val notInPlan: Boolean = false,
    )
}
