package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

/** Explicit provider prices, separate from user token-usage estimates. */
data class UiProviderPriceTier(
    val cacheHitCnyPerMillion: Double,
    val cacheMissCnyPerMillion: Double,
    val outputCnyPerMillion: Double,
)

data class UiProviderPriceModel(
    val modelId: String,
    val displayName: String,
    val version: String,
    val peak: UiProviderPriceTier,
    val offPeak: UiProviderPriceTier,
)

data class UiProviderPricingSnapshot(
    val models: List<UiProviderPriceModel> = emptyList(),
    val sourceUrl: String = "",
    val lastUpdatedAt: Long = 0L,
    val refreshing: Boolean = false,
    val activePeriod: String = "OFF_PEAK",
    val error: String? = null,
)

sealed interface PricingUiReceipt {
    data object Completed : PricingUiReceipt
    data class Failed(val message: String?) : PricingUiReceipt
}

/** Only the original billing repository fetches or stores provider price information. */
interface PricingUiPort {
    val snapshots: Flow<UiProviderPricingSnapshot>
    fun snapshot(): UiProviderPricingSnapshot
    suspend fun refreshOfficial(): PricingUiReceipt
}
