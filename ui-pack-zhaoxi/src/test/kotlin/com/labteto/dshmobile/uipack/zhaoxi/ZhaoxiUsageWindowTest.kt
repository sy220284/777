package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxUsageWindowRecords
import org.junit.Assert.assertEquals
import org.junit.Test

class ZhaoxiUsageWindowTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test fun allRetainedRecordsShowNewestFirst() {
        val entries = listOf(TokenUsageRecord("old", now - 40 * day, "A"),
            TokenUsageRecord("new", now, "B"))
        assertEquals(listOf("new", "old"), zxUsageWindowRecords(entries, now, 0).map { it.requestId })
    }

    @Test fun oneDayDoesNotDisplayWeekOldRequests() {
        val entries = listOf(TokenUsageRecord("week", now - 7 * day, "A"),
            TokenUsageRecord("today", now - 1000L, "A"))
        assertEquals(listOf("today"), zxUsageWindowRecords(entries, now, 1).map { it.requestId })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeWindow() { zxUsageWindowRecords(emptyList(), now, -1) }
}
