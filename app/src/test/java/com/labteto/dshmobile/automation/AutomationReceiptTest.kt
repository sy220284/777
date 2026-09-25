package com.labteto.dshmobile.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationReceiptTest {
    @Test
    fun keepsNewestTwoHundredReceipts() {
        var history = emptyList<AutomationRunReceipt>()
        repeat(250) { index ->
            history = appendAutomationReceipt(
                history,
                AutomationRunReceipt(
                    startedAt = index.toLong(),
                    finishedAt = index.toLong(),
                    status = "completed",
                ),
            )
        }

        assertEquals(200, history.size)
        assertEquals(50L, history.first().finishedAt)
        assertEquals(249L, history.last().finishedAt)
    }

    @Test
    fun prunesReceiptsOutsideThirtyDayWindow() {
        val day = 24L * 60L * 60L * 1000L
        val old = AutomationRunReceipt(0L, day, "completed")
        val recent = AutomationRunReceipt(31L * day, 31L * day, "failed")

        val history = appendAutomationReceipt(listOf(old), recent)

        assertEquals(1, history.size)
        assertTrue(history.single().status == "failed")
    }
}
