package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContinuityPromptProjectionTest {
    @Test
    fun longTermCommitmentsSurviveEvenWhenOldUnrelatedItemsAreNotInThePrompt() {
        val active = (1..70).map { "还有事项$it待处理" }
        val selected = ChatContinuityPromptProjection.select(active, "这轮聊点别的")
        assertTrue(selected.size <=
            ChatContinuityPromptProjection.RELATED_EVENT_BUDGET +
                ChatContinuityPromptProjection.RECENT_EVENT_BUDGET)
        assertEquals(active.takeLast(10), selected)
        // Omission from request projection must never mutate the source collection.
        assertEquals(70, active.size)
        assertFalse("还有事项1待处理" in selected)
    }

    @Test
    fun mentionedOlderCommitmentReappearsAlongsideRecentContext() {
        val active = (1..50).map { "记得处理事项$it" } + "明天去城南确认车票" +
            (51..90).map { "记得处理事项$it" }
        val selected = ChatContinuityPromptProjection.select(active, "明天去城南的车票还用确认吗")
        assertTrue("明天去城南确认车票" in selected)
        assertTrue("记得处理事项90" in selected)
        assertTrue(selected.size <= 20)
    }
}
