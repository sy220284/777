package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.estimateModelTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDiaryRecallPolicyTest {
    @Test
    fun longTermMemoryBudgetStaysBoundedAcrossContextWindows() {
        assertEquals(800, chatLongTermMemoryBudget(8_192).totalTokens)
        assertEquals(1_400, chatLongTermMemoryBudget(16_384).totalTokens)
        assertTrue(chatLongTermMemoryBudget(1_000_000).totalTokens <= 2_200)
    }


    @Test
    fun groupRecallKeepsInnerNarrativePrivateToCurrentCharacter() {
        val instruction = diaryRecallUsageInstruction(groupAudience = true)
        assertTrue(instruction.contains("不代表其他成员知情"))
        assertTrue(instruction.contains("心理活动"))
        assertTrue(instruction.contains("不要主动逐条公开"))
    }

    @Test
    fun renderedMemoryIsHardClippedByEstimatedModelTokens() {
        val bounded = takeWithinModelTokenBudget("人物日记".repeat(2_000), 120)
        assertTrue(estimateModelTokens(bounded) <= 120)
    }
}
