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
        assertTrue(instruction.contains("按当前问题关联性选取"))
        assertTrue(instruction.contains("其他成员不会自动获得此人物的完整记忆"))
        assertTrue(instruction.contains("只能听到实际说出口的话"))
        assertTrue(instruction.contains("不得作为当前有效事实回潮"))
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
