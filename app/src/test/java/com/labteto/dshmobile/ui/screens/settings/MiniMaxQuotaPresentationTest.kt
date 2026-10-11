package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.local.model.MiniMaxProviderUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniMaxQuotaPresentationTest {
    @Test
    fun onlyConfirmedRemainingQuotaProducesProgress() {
        val percent = MiniMaxProviderUsage.Window(null, null, 83.0)
        assertEquals(0.83f, percent.remainingFraction()!!, 0.001f)
        assertEquals(0.25f, percent.copy(remaining = 1, total = 4, remainingPercent = null).remainingFraction()!!, 0.001f)
        assertNull(percent.copy(unlimited = true).remainingFraction())
        assertNull(percent.copy(notInPlan = true).remainingFraction())
        assertNull(percent.copy(remainingPercent = null).remainingFraction())
        assertNull(percent.copy(remainingPercent = Double.NaN).remainingFraction())
        assertNull(percent.copy(remainingPercent = 101.0).remainingFraction())
        assertNull(percent.copy(remaining = 2, total = 0, remainingPercent = null).remainingFraction())
    }
}
