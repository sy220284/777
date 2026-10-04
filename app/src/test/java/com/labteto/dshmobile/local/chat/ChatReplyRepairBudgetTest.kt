package com.labteto.dshmobile.local.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatReplyRepairBudgetTest {
    @Test fun domainRepairsShareOneBoundedCandidateBudget() = runTest {
        val budget = ChatReplyRepairBudget()
        var requests = 0
        assertEquals("immersion", budget.repair("immersion") { requests++; it })
        assertEquals("continuity", budget.repair("continuity") { requests++; it })
        try {
            budget.repair("nested") { requests++; it }
            throw AssertionError("第三次修复不得发起模型请求")
        } catch (_: IllegalStateException) { }
        assertEquals(2, requests)
    }
}
