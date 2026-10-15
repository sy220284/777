package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.estimateModelTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDiaryRecallPolicyTest {
    @Test
    fun longTermMemoryBudgetStaysBoundedAcrossContextWindows() {
        assertEquals(800, chatLongTermMemoryBudget(null).totalTokens)
        assertEquals(800, chatLongTermMemoryBudget(8_192).totalTokens)
        assertEquals(1_000, chatLongTermMemoryBudget(16_384).totalTokens)
        assertTrue(chatLongTermMemoryBudget(1_000_000).totalTokens <= 1_300)
    }


    @Test
    fun groupRecallSelectsRelevantIndividualMemoryWithoutBroadcasting() {
        val instruction = diaryRecallUsageInstruction(groupAudience = true)
        assertTrue(instruction.contains("当前人物自己的经历和感受"))
        assertTrue(instruction.contains("猜测属于人物主观认识"))
        assertTrue(instruction.contains("其他成员的所知以实际公开发言和事件为准"))
        assertTrue(instruction.contains("旧版本属于历史"))
    }

    @Test
    fun historicalRecallIsExplicitAndOrdinaryRecallStaysCurrent() {
        assertTrue(isHistoricalDiaryRecall("最开始我们原来约的几点"))
        assertTrue(isHistoricalDiaryRecall("改之前是什么安排"))
        assertTrue(!isHistoricalDiaryRecall("我们最后约定几点"))
    }

    @Test
    fun anOldPrivateThoughtBeyondInitialChineseTokensRemainsSearchable() {
        val old = ChatDiaryEntry(
            id = "old-thought", subjectKey = "gallery:a", personaName = "阿青",
            event = "在山间书店聊了很久",
            privateReflection = "心里一直乱糟糟的。".repeat(23) +
                "直到最后才想起那片海。",
            importance = 3, createdAt = 1L, updatedAt = 1L,
        )
        val distractors = (1..320).map { index ->
            old.copy(
                id = "other-$index",
                event = "在城里买书$index",
                privateReflection = "",
                importance = 2,
                createdAt = index + 100L,
                updatedAt = index + 100L,
            )
        }
        val found = ChatDiaryRecallEngine.search(
            entries = distractors + old,
            query = "那片海",
            subjectKey = "gallery:a",
            groupAudience = false,
            maxItems = 4,
        )
        assertTrue(found.any { it.id == old.id })
        assertTrue(ChatDiaryRecallEngine.search(
            entries = distractors + old, query = "那片海",
            subjectKey = "gallery:b", groupAudience = true, maxItems = 4,
        ).isEmpty())
    }

    @Test
    fun privateVoiceReplacesDuplicatePsychologyAndLegacyUserLabelOnlyInRecall() {
        val entry = ChatDiaryEntry(
            id = "one", subjectKey = "gallery:a", personaName = "阿青",
            event = "用户约定和我周末一起出门",
            feeling = "我很开心",
            innerThought = "我开始期待",
            relationshipMeaning = "我开始期待",
            unresolvedEcho = "还想再商量时间",
            privateReflection = "我开始期待。只是希望这次约定能成真。",
            createdAt = 1L, updatedAt = 1L,
        )
        val rendered = renderRecalledChatDiary(entry, groupAudience = false)
        assertTrue(rendered.contains("对方约定和我"))
        assertFalse(rendered.contains("用户"))
        assertTrue(rendered.contains("私人日记想法"))
        assertFalse(rendered.contains("  感受："))
        assertFalse(rendered.contains("  心里："))
        assertFalse(rendered.contains("  关系理解：我开始期待"))
        assertTrue(rendered.contains("  未解心绪：还想再商量时间"))
        // Raw memory stays untouched for traceability and corrections.
        assertTrue(entry.event.startsWith("用户"))
        val old = renderRecalledChatDiary(entry.copy(privateReflection = ""), false)
        assertTrue(old.contains("  感受：我很开心"))
        assertTrue(old.contains("  心里：我开始期待"))
    }

    @Test
    fun renderedMemoryIsHardClippedByEstimatedModelTokens() {
        val bounded = takeWithinModelTokenBudget("人物日记".repeat(2_000), 120)
        assertTrue(estimateModelTokens(bounded) <= 120)
    }
}
