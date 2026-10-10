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

    @Test
    fun proactiveFocusIgnoresAutomaticMessagesAndKeepsOnlyRecentRealDialogue() {
        val history = listOf(
            message("u1", "user", "很久前的旧对话", 1L),
            message("a1", "assistant", "很久前的角色回复", 2L),
            message("p1", "assistant", "昨天自动问过你睡了吗", 3L, proactive = true),
            message("u2", "user", "我今天答应去医院看望朋友", 4L),
            message("a2", "assistant", "好，希望一切顺利", 5L),
            message("p2", "assistant", "我又来问你睡了吗", 6L, proactive = true),
        )
        val focus = proactiveConversationFocus(history, fallback = "后台定时触发条件")
        assertTrue(focus.contains("我今天答应去医院看望朋友"))
        assertTrue(focus.contains("好，希望一切顺利"))
        assertFalse(focus.contains("后台定时触发条件"))
        assertFalse(focus.contains("我又来问你睡了吗"))
        assertFalse(focus.contains("昨天自动问过你睡了吗"))
        assertEquals("真实故事摘要", proactiveConversationFocus(
            listOf(message("p3", "assistant", "自动打招呼", 7L, proactive = true)),
            fallback = "真实故事摘要",
        ))
        assertEquals("", proactiveConversationFocus(emptyList(), fallback = ""))
    }

    @Test
    fun proactiveAvoidanceAndDuplicateCheckLookOnlyAtMostRecentFiveProactiveTurns() {
        val messages = buildList {
            add(message("p-old", "assistant", "今天给你带了草莓奶昔，记得趁凉喝。", 1L, proactive = true))
            repeat(250) { index ->
                add(message("u$index", "user", "这次真实聊天内容$index", index + 2L))
                add(message("p$index", "assistant", "第${index}次给你发的新鲜祝福，今天也顺利。", index + 300L, proactive = true))
            }
            add(message("p-new", "assistant", "刚刚翻开一本旧书，想起我们在雨天的约定。", 900L, proactive = true))
        }
        val avoidance = recentProactiveAvoidanceContext(messages)
        assertTrue(avoidance.contains("刚刚翻开一本旧书"))
        assertFalse(avoidance.contains("草莓奶昔"))
        assertTrue(isNearDuplicateProactive(
            "刚刚翻开一本旧书，想起我们在雨天的约定。", messages,
        ))
        assertFalse(isNearDuplicateProactive(
            "今天给你带了草莓奶昔，记得趁凉喝。", messages,
        ))
    }

    @Test
    fun proactivePromptAnchorsRealUnfinishedPlansWithoutFabricatingUserTurns() {
        val persona = PersonaProfile(name = "阿青", coreIdentity = "园艺师")
        val state = ChatCharacterState(
            unresolvedThreads = listOf("还没把旧花盆归还给朋友"),
            currentAgenda = "今天要整理温室里的新苗",
        )
        val prompt = characterProactiveDirective("用户设置的晚上提醒", persona, state)
        assertTrue(prompt.contains("还没把旧花盆归还给朋友"))
        assertTrue(prompt.contains("整理温室里的新苗"))
        assertTrue(prompt.contains("不要编造用户刚刚说过"))
        assertTrue(prompt.contains("不编造重大事件"))
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
