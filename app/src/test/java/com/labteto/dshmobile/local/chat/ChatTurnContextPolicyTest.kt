package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnContextPolicyTest {
    @Test
    fun ordinaryNegationsAreNotGlobalTopicResets() {
        listOf("我不想说谎", "你别把这件事说出去", "不能你到了才讲", "别说我没告诉你")
            .forEach { assertFalse(ChatTurnContextPolicy.isTopicRedirect(it)) }
        listOf("别提衣服了", "换个话题", "不想再聊工作", "先不聊这个")
            .forEach { assertTrue(ChatTurnContextPolicy.isTopicRedirect(it)) }
    }

    @Test
    fun userTopicPausePersistsAcrossWeakRepliesButNeverDeletesFacts() {
        val context = ChatContextState(
            scene = ChatSceneState(location = "房间"),
            continuity = ChatContinuityState(unfinished = listOf("整理宋制襦裙")),
        )
        val paused = context.enqueuePending(ChatPendingTurn(
            sequence = 31L, userMessage = "别提衣服了", assistantMessage = "好，我们聊别的。",
        ))
        val continued = paused.enqueuePending(ChatPendingTurn(
            sequence = 33L, userMessage = "嗯", assistantMessage = "嗯，听你说。",
        ))
        assertEquals("衣服", continued.attention.pausedSubject)
        assertEquals("", continued.attention.lastUserSubject)
        assertEquals(listOf("整理宋制襦裙"), continued.continuity.unfinished)
        assertFalse(renderChatContextForModel(continued, "嗯").contains("整理宋制襦裙"))
        assertTrue(renderChatContextForModel(continued, "嗯").contains("地点=房间"))
    }

    @Test
    fun savedAttentionRebasesWithBranchAndDirectRecallMayAccessHistoricalFact() {
        val stored = ChatContextState(attention = ChatAttentionState(
            lastUserSubject = "", pausedSubject = "衣服", updatedSequence = 12L,
        ))
        val forked = stored.rebaseGeneration()
        assertEquals(stored.attention, forked.attention)
        assertTrue(ChatTurnContextPolicy.relevant("刚才的宋制襦裙呢", "宋制襦裙", forked.attention))
        assertFalse(ChatTurnContextPolicy.relevant("嗯", "宋制襦裙", forked.attention))
    }

    @Test
    fun weakFollowUpResolvesAgainstLastExpressedUserSubjectNotOldUnfinishedTasks() {
        val attention = ChatAttentionState(lastUserSubject = "我们明天去城南修表")
        assertTrue(ChatTurnContextPolicy.relevant("继续", "明天城南修表的安排", attention))
        assertFalse(ChatTurnContextPolicy.relevant("继续", "上个月在海边拍照", attention))
    }

    @Test
    fun storyMemoryDoesNotBypassCurrentTopicRedirection() {
        val story = "剧情提要：昨天展示了宋制襦裙\n当前硬场景：地点=房间\n周末去城南看书"
        val attention = ChatAttentionState(pausedSubject = "衣服")
        assertEquals("", ChatTurnContextPolicy.storyLines(story, "别提衣服了", attention))
        assertFalse(ChatTurnContextPolicy.storyLines(story, "今天天气很好", attention).contains("宋制襦裙"))
        assertTrue(ChatTurnContextPolicy.storyLines(story, "周末去城南", attention).contains("城南"))
    }

    @Test
    fun attentionUpdateIsIdempotentWhenReplayingCommittedTurn() {
        val once = ChatTurnContextPolicy.advance(ChatAttentionState(), "换个话题", 7L)
        assertEquals(once, ChatTurnContextPolicy.advance(once, "继续聊衣服", 7L))
        assertEquals("*", once.pausedSubject)
    }
}
