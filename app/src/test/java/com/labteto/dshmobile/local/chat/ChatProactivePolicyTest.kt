package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import java.util.Calendar
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
    fun quietHoursRetryMovesToNextEndBoundary() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 2, 23, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val expected = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 3, 7, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(expected, nextQuietHoursEndMillis(now, endHour = 7))
    }

    @Test
    fun quietWindowSupportsOvernightRange() {
        assertTrue(isHourInQuietWindow(hour = 23, startHour = 23, endHour = 7))
        assertTrue(isHourInQuietWindow(hour = 2, startHour = 23, endHour = 7))
        assertFalse(isHourInQuietWindow(hour = 7, startHour = 23, endHour = 7))
        assertFalse(isHourInQuietWindow(hour = 18, startHour = 23, endHour = 7))
    }

    @Test
    fun customCooldownReturnsExactRetryTime() {
        val hour = 60L * 60L * 1000L
        val messages = listOf(
            message("u1", "user", "先忙", 1L),
            message("p1", "assistant", "那我晚点再来。", 2L * hour, proactive = true),
        )

        val decision = evaluateChatProactivePolicy(
            messages = messages,
            nowMillis = 5L * hour,
            minimumGapMinutes = 6L * 60L,
        )

        assertFalse(decision.shouldSend)
        assertEquals(8L * hour, decision.retryAt)
        assertFalse(decision.waitingForUserReply)
    }

    @Test
    fun unansweredLimitWaitsForNextUserReply() {
        val messages = listOf(
            message("u1", "user", "晚点聊", 1L),
            message("p1", "assistant", "好。", 2L, proactive = true),
            message("p2", "assistant", "有空再叫我。", 3L, proactive = true),
            message("p3", "assistant", "我先不打扰。", 4L, proactive = true),
        )

        val decision = evaluateChatProactivePolicy(
            messages = messages,
            nowMillis = 100L,
            minimumGapMinutes = 0L,
            maxUnanswered = 3,
        )

        assertFalse(decision.shouldSend)
        assertTrue(decision.waitingForUserReply)
        assertTrue(decision.retryAt == null)
    }

    @Test
    fun quietWindowSupportsMinutePrecision() {
        assertTrue(
            isMinuteInQuietWindow(
                minuteOfDay = 23 * 60,
                startMinuteOfDay = 22 * 60 + 30,
                endMinuteOfDay = 6 * 60 + 45,
            ),
        )
        assertTrue(
            isMinuteInQuietWindow(
                minuteOfDay = 6 * 60 + 44,
                startMinuteOfDay = 22 * 60 + 30,
                endMinuteOfDay = 6 * 60 + 45,
            ),
        )
        assertFalse(
            isMinuteInQuietWindow(
                minuteOfDay = 6 * 60 + 45,
                startMinuteOfDay = 22 * 60 + 30,
                endMinuteOfDay = 6 * 60 + 45,
            ),
        )
    }

    @Test
    fun quietHoursDecisionCarriesMinutePreciseRetry() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 2, 23, 15, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val expected = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 3, 6, 45, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val decision = evaluateChatProactivePolicy(
            messages = emptyList(),
            nowMillis = now,
            quietHoursEnabled = true,
            quietStartHour = 22,
            quietStartMinute = 30,
            quietEndHour = 6,
            quietEndMinute = 45,
        )

        assertFalse(decision.shouldSend)
        assertEquals(expected, decision.retryAt)
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
