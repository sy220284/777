package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranscriptPresentationTest {
    @Test
    fun groupsCurrentWorkProcessAndLeavesFinalAnswerStandalone() {
        val items = buildLocalTranscript(
            listOf(
                message("u1", "user", "检查项目"),
                message("r1", "reasoning", "先看代码"),
                message("p1", "progress", "我先读取相关文件"),
                message("t1", "tool", "file content", toolName = "read"),
                message("r2", "reasoning", "已经定位问题"),
                message("a1", "assistant", "最终完整答复"),
            ),
        )

        assertEquals(3, items.size)
        assertTrue(items[0] is LocalTranscriptItem.Message)
        val work = items[1] as LocalTranscriptItem.WorkProcess
        assertEquals(listOf("reasoning", "progress", "tool", "reasoning"), work.messages.map { it.role })
        val answer = items[2] as LocalTranscriptItem.Message
        assertEquals("assistant", answer.message.role)
        assertEquals("最终完整答复", answer.message.content)
    }

    @Test
    fun foldsLegacyIntermediateAssistantWhenToolResultFollows() {
        val items = buildLocalTranscript(
            listOf(
                message("u1", "user", "继续"),
                message("r1", "reasoning", "分析中"),
                message("a-old", "assistant", "我先调用工具"),
                message("t1", "tool", "ok", toolName = "grep"),
                message("a-final", "assistant", "处理完成"),
            ),
        )

        assertEquals(3, items.size)
        val work = items[1] as LocalTranscriptItem.WorkProcess
        assertEquals(listOf("reasoning", "assistant", "tool"), work.messages.map { it.role })
        val answer = items[2] as LocalTranscriptItem.Message
        assertEquals("处理完成", answer.message.content)
    }

    @Test
    fun doesNotFoldCompletedAnswerAcrossNextUserTurn() {
        val items = buildLocalTranscript(
            listOf(
                message("u1", "user", "第一问"),
                message("a1", "assistant", "第一答"),
                message("u2", "user", "第二问"),
                message("r2", "reasoning", "分析第二问"),
                message("a2", "assistant", "第二答"),
            ),
        )

        assertEquals(5, items.size)
        assertEquals("第一答", (items[1] as LocalTranscriptItem.Message).message.content)
        assertTrue(items[3] is LocalTranscriptItem.WorkProcess)
        assertEquals("第二答", (items[4] as LocalTranscriptItem.Message).message.content)
    }


    @Test
    fun internalSystemPayloadsAreNotUserFacingTranscriptRows() {
        val items = buildLocalTranscript(
            listOf(
                message("u1", "user", "开始"),
                message("s1", "system", "内部路径 /work/private.kt"),
                message("t1", "tool", "raw output", toolName = "read"),
                message("a1", "assistant", "完成"),
            ),
        )

        assertEquals(3, items.size)
        assertEquals("user", (items[0] as LocalTranscriptItem.Message).message.role)
        assertTrue(items[1] is LocalTranscriptItem.WorkProcess)
        assertEquals("assistant", (items[2] as LocalTranscriptItem.Message).message.role)
    }

    @Test
    fun chatProjectionHidesWorkProcessAndKeepsOnlyConversationRows() {
        val items = buildLocalTranscript(
            messages = listOf(
                message("u1", "user", "在吗"),
                message("r1", "reasoning", "内部思考"),
                message("p1", "progress", "准备调用工具"),
                message("t1", "tool", "tool output", toolName = "read"),
                message("a1", "assistant", "在。"),
            ),
            includeWorkProcess = false,
        )

        assertEquals(2, items.size)
        assertEquals("user", (items[0] as LocalTranscriptItem.Message).message.role)
        assertEquals("assistant", (items[1] as LocalTranscriptItem.Message).message.role)
        assertEquals("在。", (items[1] as LocalTranscriptItem.Message).message.content)
    }

    @Test
    fun transcriptWindowKeepsOnlyNewestMessagesAndReportsHiddenCount() {
        val messages = (1..500).map { index ->
            message("m$index", if (index % 2 == 0) "assistant" else "user", "message-$index")
        }

        val initial = localTranscriptWindow(messages, 200)
        assertEquals(200, initial.messages.size)
        assertEquals(300, initial.hiddenCount)
        assertEquals("m301", initial.messages.first().id)
        assertEquals("m500", initial.messages.last().id)

        val expanded = localTranscriptWindow(messages, 400)
        assertEquals(400, expanded.messages.size)
        assertEquals(100, expanded.hiddenCount)
        assertEquals("m101", expanded.messages.first().id)
    }

    @Test
    fun eventBackedOlderHistoryMergesBeforeLiveTailAndLiveMessageWins() {
        val older = listOf(
            message("m1", "user", "old-1"),
            message("m2", "assistant", "stale-copy"),
            message("m2", "assistant", "duplicate-old"),
        )
        val live = listOf(
            message("m2", "assistant", "live-copy"),
            message("m3", "user", "live-3"),
        )

        val merged = mergeLocalTranscriptHistory(older, live)

        assertEquals(listOf("m1", "m2", "m3"), merged.map { it.id })
        assertEquals("live-copy", merged[1].content)
    }

    private fun message(
        id: String,
        role: String,
        content: String,
        toolName: String? = null,
    ) = LocalHarnessMessage(
        id = id,
        role = role,
        content = content,
        toolName = toolName,
        createdAt = 1L,
    )
}
