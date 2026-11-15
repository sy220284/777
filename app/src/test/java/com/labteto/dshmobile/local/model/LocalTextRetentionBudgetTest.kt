package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTextRetentionBudgetTest {
    @Test
    fun longMultilingualToolResultStaysWithinBothBudgets() {
        val source = "中文工具结果与emoji😀".repeat(10_000)
        for (maxTokens in listOf(1, 8, 128, 2_400)) {
            for (maxChars in listOf(1, 8, 64, 1_024, 8_192)) {
                val retained = retainTextForModel(source, maxTokens, maxChars)
                assertTrue("characters exceeded $maxChars", retained.text.length <= maxChars)
                assertTrue("tokens exceeded $maxTokens",
                    estimateModelTokens(retained.text) <= maxTokens)
                assertTrue(retained.truncated)
                assertTrue(retained.omittedBytes > 0)
                assertFalse(retained.text.contains("\uFFFD"))
            }
        }
    }

    @Test
    fun smallExactResultKeepsOriginalWithoutMarker() {
        val source = "完整的工具结果"
        val result = retainTextForModel(source, maxTokens = 100, maxChars = 100)
        assertEquals(source, result.text)
        assertFalse(result.truncated)
        assertEquals(0, result.omittedBytes)
    }
}
