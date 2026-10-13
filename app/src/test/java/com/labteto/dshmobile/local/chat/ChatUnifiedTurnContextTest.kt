package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUnifiedTurnContextTest {
    @Test
    fun sharedAssemblyKeepsLiveSceneAndInstructionsButDropsRedirectedHistory() {
        val ctx = ChatContextState(scene = ChatSceneState(location = "书房"))
        val parts = listOf(
            ChatContextPart(ChatContextLayer.LIVE, "【当前场景】地点=书房"),
            ChatContextPart(ChatContextLayer.HISTORICAL, "【历史】上周看过衣服"),
            ChatContextPart(ChatContextLayer.RECALL, "【日记】上周看过衣服"),
            ChatContextPart(ChatContextLayer.INSTRUCTION, "【身份】阿青"),
        )
        val result = ChatUnifiedTurnContext.compose("换个话题", ctx, parts)
        assertTrue(result.contains("地点=书房"))
        assertTrue(result.contains("【身份】阿青"))
        assertFalse(result.contains("上周看过衣服"))
    }

    @Test
    fun samePolicyAppliesToAllRecallSourcesAfterPausedTopic() {
        val ctx = ChatContextState(attention = ChatAttentionState(pausedSubject = "衣服"))
        val parts = listOf(
            ChatContextPart(ChatContextLayer.RECALL, "人物记忆：昨天聊过衣服"),
            ChatContextPart(ChatContextLayer.HISTORICAL, "周末一起看过风景"),
            ChatContextPart(ChatContextLayer.LIVE, "人物正在整理桌子"),
            ChatContextPart(ChatContextLayer.RECALL, "人物记忆：昨天聊过衣服"),
        )
        val result = ChatUnifiedTurnContext.compose("嗯", ctx, parts)
        assertFalse(result.contains("昨天聊过衣服"))
        assertTrue(result.contains("周末一起看过风景"))
        assertTrue(result.contains("正在整理桌子"))
        assertEquals(1, result.split("周末一起看过风景").size - 1)
        assertTrue(ChatUnifiedTurnContext.allowsHistoricalRecord("我们再聊衣服", ctx, "衣服"))
    }

    @Test
    fun exactDuplicateBlocksCollapseWithoutMergingRelatedPromises() {
        val ctx = ChatContextState()
        val lines = listOf(
            ChatContextPart(ChatContextLayer.HISTORICAL, "答应保密"),
            ChatContextPart(ChatContextLayer.HISTORICAL, "答应公开照片"),
            ChatContextPart(ChatContextLayer.HISTORICAL, " 答应保密 "),
        )
        val rendered = ChatUnifiedTurnContext.compose("之前的约定", ctx, lines)
        assertEquals(1, rendered.split("答应保密").size - 1)
        assertTrue(rendered.contains("答应公开照片"))
    }
}
