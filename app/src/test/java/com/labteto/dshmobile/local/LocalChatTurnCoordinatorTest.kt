package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChatTurnCoordinatorTest {
    @Test
    fun groupAntiRepeatOnlyLooksAtTheSameCharacter() {
        val messages = listOf(
            LocalHarnessMessage("a1", "assistant", "甲的旧话", createdAt = 1L, speakerId = "a", speakerName = "甲"),
            LocalHarnessMessage("b1", "assistant", "乙的旧话", createdAt = 2L, speakerId = "b", speakerName = "乙"),
            LocalHarnessMessage("a2", "assistant", "甲的新话", createdAt = 3L, speakerId = "a", speakerName = "甲"),
        )

        val replies = recentRoleReplies(
            messages = messages,
            groupEnabled = true,
            persona = PersonaProfile(id = "a", name = "甲"),
        )

        assertEquals(listOf("甲的旧话", "甲的新话"), replies)
    }
    @Test
    fun replySuggestionContextUsesLatestSixDialogueMessagesBeforeAnchoredReply() {
        val messages = listOf(
            LocalHarnessMessage("u0", "user", "最旧用户消息", createdAt = 1L),
            LocalHarnessMessage("a0", "assistant", "最旧角色回复", createdAt = 2L),
            LocalHarnessMessage("u1", "user", "第一条保留用户消息", createdAt = 3L),
            LocalHarnessMessage("a1", "assistant", "第一条保留角色回复", createdAt = 4L),
            LocalHarnessMessage("s1", "system", "系统消息不应进入建议上下文", createdAt = 5L),
            LocalHarnessMessage("u2", "user", "第二轮用户消息", createdAt = 6L),
            LocalHarnessMessage("a2", "assistant", "第二轮角色回复", createdAt = 7L),
            LocalHarnessMessage("u3", "user", "最新用户消息", createdAt = 8L),
            LocalHarnessMessage("a3", "assistant", "最新角色回复", createdAt = 9L),
            LocalHarnessMessage("u4", "user", "锚点之后的消息不能混入", createdAt = 10L),
        )

        val dialogue = recentReplySuggestionDialogue(
            messages = messages,
            latestAssistantMessageId = "a3",
        )

        assertEquals(
            listOf(
                "user" to "第一条保留用户消息",
                "assistant" to "第一条保留角色回复",
                "user" to "第二轮用户消息",
                "assistant" to "第二轮角色回复",
                "user" to "最新用户消息",
                "assistant" to "最新角色回复",
            ),
            dialogue,
        )
    }

}
