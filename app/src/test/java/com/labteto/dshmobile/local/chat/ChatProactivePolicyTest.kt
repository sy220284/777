package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProactivePolicyTest {
    @Test
    fun pausesAfterTwoUnansweredProactiveMessages() {
        val messages = listOf(
            message("u1", "user", "晚点聊", 1L),
            message("a1", "assistant", "好", 2L),
            message("p1", "assistant", "我来找你了", 10L, proactive = true),
            message("p2", "assistant", "今天过得怎么样", 20L, proactive = true),
        )

        val decision = evaluateChatProactivePolicy(messages, nowMillis = 30L)

        assertFalse(decision.shouldSend)
    }

    @Test
    fun userReplyResetsProactiveCooldown() {
        val messages = listOf(
            message("p1", "assistant", "我来找你了", 10L, proactive = true),
            message("u1", "user", "刚忙完", 20L),
        )

        val decision = evaluateChatProactivePolicy(messages, nowMillis = 21L)

        assertTrue(decision.shouldSend)
    }

    @Test
    fun silenceTriggerUsesLatestUserMessageAndReturnsExactRetryTime() {
        val hour = 60L * 60L * 1000L
        val messages = listOf(
            message("u1", "user", "刚忙完", 2L * hour),
            message("a1", "assistant", "好", 2L * hour + 1L),
        )

        val waiting = evaluateChatSilenceTrigger(
            messages = messages,
            nowMillis = 5L * hour,
            silenceMinutes = 6L * 60L,
            fallbackReferenceAt = 0L,
        )
        assertFalse(waiting.ready)
        assertEquals(8L * hour, waiting.retryAt)

        val ready = evaluateChatSilenceTrigger(
            messages = messages,
            nowMillis = 8L * hour,
            silenceMinutes = 6L * 60L,
            fallbackReferenceAt = 0L,
        )
        assertTrue(ready.ready)
    }

    @Test
    fun quietWindowSupportsOvernightRange() {
        assertTrue(isHourInQuietWindow(hour = 23, startHour = 23, endHour = 7))
        assertTrue(isHourInQuietWindow(hour = 2, startHour = 23, endHour = 7))
        assertFalse(isHourInQuietWindow(hour = 7, startHour = 23, endHour = 7))
        assertFalse(isHourInQuietWindow(hour = 18, startHour = 23, endHour = 7))
    }

    @Test
    fun detectsNearDuplicateProactiveCopy() {
        val messages = listOf(
            message(
                "p1",
                "assistant",
                "刚刚路过一家店，突然想起你上次说想吃甜点。",
                10L,
                proactive = true,
            ),
        )

        assertTrue(
            isNearDuplicateProactive(
                "刚路过一家店，突然想起你上次说过想吃甜点。",
                messages,
            ),
        )
    }

    private fun message(
        id: String,
        role: String,
        content: String,
        createdAt: Long,
        proactive: Boolean = false,
    ) = LocalHarnessMessage(
        id = id,
        role = role,
        content = content,
        createdAt = createdAt,
        proactive = proactive,
    )
}
