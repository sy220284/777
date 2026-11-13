package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.model.DeepSeekPriceTier
import com.labteto.dshmobile.local.model.DeepSeekModelPricing
import com.labteto.dshmobile.local.model.DeepSeekPricingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI pricing must mirror official-model tiers, never derive rates from usage logs. */
class LocalProviderPricingUiProjectionTest {
    @Test fun preservesOfficialPeakOffPeakPricesAndProvenance() {
        val state = DeepSeekPricingState(
            models = listOf(DeepSeekModelPricing(
                "deepseek-test", "DeepSeek Test", "v3",
                offPeak = DeepSeekPriceTier(1.0, 2.0, 3.0),
                peak = DeepSeekPriceTier(4.0, 5.0, 6.0),
            )),
            lastUpdatedAt = 1234567L,
            sourceUrl = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/",
        )
        val projected = state.asUiProviderPricing()
        assertEquals(1, projected.models.size)
        assertEquals("deepseek-test", projected.models.single().modelId)
        assertEquals(1.0, projected.models.single().offPeak.cacheHitCnyPerMillion, 0.000001)
        assertEquals(6.0, projected.models.single().peak.outputCnyPerMillion, 0.000001)
        assertEquals(state.lastUpdatedAt, projected.lastUpdatedAt)
        assertEquals(state.sourceUrl, projected.sourceUrl)
    }

    @Test fun errorsAreVisibleWithoutFalsifyingLastCachedTariffs() {
        val state = DeepSeekPricingState(error = "provider timeout", refreshing = false)
        val projected = state.asUiProviderPricing()
        assertEquals("provider timeout", projected.error)
        assertTrue(projected.models.isNotEmpty())
        assertEquals(state.models.size, projected.models.size)
    }
}
