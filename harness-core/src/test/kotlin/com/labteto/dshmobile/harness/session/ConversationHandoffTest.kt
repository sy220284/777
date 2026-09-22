package com.labteto.dshmobile.harness.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHandoffTest {
    @Test
    fun keepsIntentOpenTodosAndRecentConversation() {
        val summary = ConversationHandoffBuilder().build(
            HandoffState(
                goal = HandoffGoal("active", "完成原生 Harness"),
                plan = listOf("拆核心", "补测试"),
                todos = listOf(
                    HandoffTodo("completed", "旧任务"),
                    HandoffTodo("in_progress", "继续收口"),
                ),
                messages = listOf(
                    HandoffMessage("tool", "忽略工具噪声"),
                    HandoffMessage("user", "继续推进"),
                    HandoffMessage("assistant", "正在处理"),
                ),
            ),
        )

        assertTrue(summary.contains("完成原生 Harness"))
        assertTrue(summary.contains("继续收口"))
        assertFalse(summary.contains("旧任务"))
        assertTrue(summary.contains("用户：继续推进"))
        assertFalse(summary.contains("忽略工具噪声"))
    }

    @Test
    fun hardLimitIsAlwaysApplied() {
        val summary = ConversationHandoffBuilder(maxChars = 512).build(
            HandoffState(
                messages = listOf(HandoffMessage("user", "x".repeat(2_000))),
            ),
        )
        assertTrue(summary.length <= 512)
    }
}
