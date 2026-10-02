package com.labteto.dshmobile.core.wire

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcWebSocketBudgetTest {
    @Test
    fun rejectsFramesThatExceedUtf8Budget() {
        assertTrue(webSocketTextWithinBudget("a".repeat(8), maxBytes = 8))
        assertFalse(webSocketTextWithinBudget("a".repeat(9), maxBytes = 8))
        assertFalse(webSocketTextWithinBudget("😀😀😀", maxBytes = 8))
    }
}
