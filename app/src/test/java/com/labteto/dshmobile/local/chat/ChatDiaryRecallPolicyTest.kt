package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.estimateModelTokens
import org.junit.Assert.assertEquals
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
    fun renderedMemoryIsHardClippedByEstimatedModelTokens() {
        val bounded = takeWithinModelTokenBudget("人物日记".repeat(2_000), 120)
        assertTrue(estimateModelTokens(bounded) <= 120)
    }
}
