package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeepSeekPricingRefreshTest {
    @Test
    fun cancellationRestoresPreviousPricingSnapshotAndClearsRefreshing() {
        val before = DeepSeekPricingState(
            lastUpdatedAt = 123L,
            refreshing = true,
            error = "previous error",
        )

        val restored = deepSeekPricingStateAfterCancellation(before)

        assertFalse(restored.refreshing)
        assertEquals(before.models, restored.models)
        assertEquals(before.lastUpdatedAt, restored.lastUpdatedAt)
        assertEquals(before.error, restored.error)
        assertEquals(before.sourceUrl, restored.sourceUrl)
    }
}
