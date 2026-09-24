package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.OtherNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A node that draws nothing must not reach the transcript's list.
 *
 * The list spaces its rows with `Arrangement.spacedBy`, which happily puts 4dp between two
 * zero-height items — so a turn's worth of bookkeeping events stacks into a visible white band
 * above the conversation. These pin the predicate that keeps them out.
 */
class ChatNodeVisibilityTest {

    @Test
    fun `structural nodes draw nothing`() {
        assertFalse(TurnStartNode(seq = 1, turn = 1).rendersContent())
        assertFalse(
            ToolResultNode(seq = 2, callId = "c", content = null, isError = false, turn = 1, step = 1)
                .rendersContent(),
        )
        assertFalse(TurnEndNode(seq = 3, turn = 1, reasonKind = "completed").rendersContent())
    }

    @Test
    fun `an unclean turn ending is worth a row`() {
        assertTrue(TurnEndNode(seq = 3, turn = 1, reasonKind = "interrupted").rendersContent())
        assertTrue(TurnEndNode(seq = 3, turn = 1, reasonKind = "error").rendersContent())
    }

    @Test
    fun `raw protocol events are hidden and deliverables remain visible`() {
        assertFalse(OtherNode(seq = 4, type = "step/start", data = JsonNull).rendersContent())
        assertFalse(OtherNode(seq = 5, type = "assistant/chunk", data = JsonNull).rendersContent())
        assertFalse(OtherNode(seq = 6, type = "request/context", data = JsonNull).rendersContent())
        assertFalse(OtherNode(seq = 7, type = "something/new", data = JsonNull).rendersContent())
        assertTrue(OtherNode(seq = 8, type = "deliverables/presented", data = JsonNull).rendersContent())
    }

    @Test
    fun `an assistant message of nothing but tool references draws nothing`() {
        val node = AssistantMessageNode(
            seq = 7,
            messageId = null,
            turn = 1,
            step = 1,
            blocks = listOf(
                ChatBlock(kind = "tool-call", toolCallId = "c", toolName = "bash"),
                ChatBlock(kind = "text", text = "   "),
            ),
        )
        assertFalse(node.rendersContent())
        assertTrue(node.copy(blocks = node.blocks + ChatBlock("text", "done")).rendersContent())
        assertTrue(node.copy(interrupted = true).rendersContent())
    }

    @Test
    fun `an empty user message draws nothing`() {
        val node = UserMessageNode(seq = 8, messageId = null, blocks = emptyList(), sourceKind = "user")
        assertFalse(node.rendersContent())
        assertTrue(node.copy(blocks = listOf(ChatBlock("text", "hello"))).rendersContent())
    }

    /**
     * A harness that labels a block something this client has not seen should still put the user's
     * words on screen. The predicate and the renderer share [displayText] so they cannot disagree
     * about it — a node judged renderable that then draws nothing costs a gap, and one judged empty
     * that holds text loses the message.
     */
    @Test
    fun `an unknown block carrying text still counts as a user message`() {
        val node = UserMessageNode(
            seq = 9,
            messageId = null,
            blocks = listOf(ChatBlock("unknown", text = "typed this")),
            sourceKind = "user",
        )
        assertTrue(node.rendersContent())
        assertEquals("typed this", node.displayText())
    }

    @Test
    fun `an unknown block with no text is still nothing to draw`() {
        val node = UserMessageNode(
            seq = 10,
            messageId = null,
            blocks = listOf(ChatBlock("unknown", text = null)),
            sourceKind = "user",
        )
        assertFalse(node.rendersContent())
        assertEquals("", node.displayText())
    }

    @Test
    fun `multiple text blocks join in order`() {
        val node = UserMessageNode(
            seq = 11,
            messageId = null,
            blocks = listOf(ChatBlock("text", "one"), ChatBlock("text", "two")),
            sourceKind = "user",
        )
        assertEquals("one\ntwo", node.displayText())
    }

    @Test
    fun `a turn hides request context while keeping tool status`() {
        val nodes = listOf(
            TurnStartNode(seq = 1, turn = 1),
            OtherNode(seq = 2, type = "step/start", data = JsonNull),
            OtherNode(seq = 3, type = "request/header", data = JsonNull),
            OtherNode(seq = 4, type = "request/context", data = JsonNull),
            ToolCallNode(seq = 5, callId = "c1", name = "bash", arguments = "{}", turn = 1, step = 1),
            ToolResultNode(seq = 6, callId = "c1", content = null, isError = false, turn = 1, step = 1),
            OtherNode(seq = 7, type = "step/end", data = JsonNull),
            TurnEndNode(seq = 8, turn = 1, reasonKind = "completed"),
        )
        assertEquals(listOf(5L), nodes.filter { it.rendersContent() }.map { it.seq })
    }

    @Test
    fun `assistant step with matching tool call is work process`() {
        val assistant = AssistantMessageNode(
            seq = 10,
            messageId = "a1",
            turn = 2,
            step = 3,
            blocks = listOf(ChatBlock("text", "先检查文件")),
        )
        val nodes = listOf(
            assistant,
            ToolCallNode(seq = 11, callId = "c1", name = "read", arguments = "{}", turn = 2, step = 3),
        )

        assertTrue(assistant.isWorkProcess(nodes))
    }

    @Test
    fun `assistant step without matching tool call is final answer`() {
        val assistant = AssistantMessageNode(
            seq = 20,
            messageId = "a2",
            turn = 2,
            step = 4,
            blocks = listOf(ChatBlock("text", "最终答复")),
        )
        val nodes = listOf(
            assistant,
            TurnEndNode(seq = 21, turn = 2, reasonKind = "completed"),
        )

        assertFalse(assistant.isWorkProcess(nodes))
    }


    @Test
    fun `legacy assistant without turn metadata folds when tool follows`() {
        val assistant = AssistantMessageNode(
            seq = 30,
            messageId = "legacy",
            turn = null,
            step = null,
            blocks = listOf(ChatBlock("text", "我先查一下")),
        )
        val nodes = listOf(
            assistant,
            ToolCallNode(seq = 31, callId = "legacy-call", name = "grep", arguments = "{}", turn = 9, step = 1),
            AssistantMessageNode(
                seq = 32,
                messageId = "final",
                turn = null,
                step = null,
                blocks = listOf(ChatBlock("text", "最终结果")),
            ),
        )

        assertTrue(assistant.isWorkProcess(nodes))
        assertFalse((nodes[2] as AssistantMessageNode).isWorkProcess(nodes))
    }


    @Test
    fun `split final answer is merged and only last node anchors the card`() {
        val first = AssistantMessageNode(
            seq = 41,
            messageId = "a-first",
            turn = 7,
            step = 3,
            blocks = listOf(ChatBlock("text", "第一段")),
        )
        val second = AssistantMessageNode(
            seq = 42,
            messageId = "a-second",
            turn = 7,
            step = 4,
            blocks = listOf(ChatBlock("text", "第二段")),
        )
        val nodes = listOf(
            UserMessageNode(
                seq = 40,
                messageId = "u40",
                blocks = listOf(ChatBlock("text", "继续")),
                sourceKind = "user",
            ),
            first,
            second,
            TurnEndNode(seq = 43, turn = 7, reasonKind = "completed"),
        )

        assertFalse(first.isFinalAnswerAnchor(nodes))
        assertTrue(second.isFinalAnswerAnchor(nodes))
        assertEquals("第一段\n\n第二段", second.finalAnswerText(nodes))
        assertFalse(first.rendersInTranscript(nodes, TranscriptMode.CONCISE))
        assertTrue(second.rendersInTranscript(nodes, TranscriptMode.CONCISE))
    }

    @Test
    fun `semantic tool steps stay visible while raw work narration stays hidden`() {
        val work = AssistantMessageNode(
            seq = 51,
            messageId = "work",
            turn = 8,
            step = 1,
            blocks = listOf(ChatBlock("text", "先查文件")),
        )
        val tool = ToolCallNode(
            seq = 52,
            callId = "call-1",
            name = "read",
            arguments = "{}",
            turn = 8,
            step = 1,
        )
        val answer = AssistantMessageNode(
            seq = 53,
            messageId = "answer",
            turn = 8,
            step = 2,
            blocks = listOf(ChatBlock("text", "最终结果")),
        )
        val nodes = listOf(
            UserMessageNode(
                seq = 50,
                messageId = "u50",
                blocks = listOf(ChatBlock("text", "检查")),
                sourceKind = "user",
            ),
            work,
            tool,
            answer,
            TurnEndNode(seq = 54, turn = 8, reasonKind = "completed"),
        )

        assertFalse(work.rendersInTranscript(nodes, TranscriptMode.CONCISE))
        assertTrue(tool.rendersInTranscript(nodes, TranscriptMode.CONCISE))
        assertTrue(answer.rendersInTranscript(nodes, TranscriptMode.CONCISE))
        assertFalse(work.rendersInTranscript(nodes, TranscriptMode.FULL))
        assertTrue(tool.rendersInTranscript(nodes, TranscriptMode.FULL))
        assertTrue(answer.rendersInTranscript(nodes, TranscriptMode.FULL))
    }

    @Test
    fun `repeated tool categories collapse to one step per turn`() {
        val read1 = ToolCallNode(61, "r1", "read", """{"path":"a.kt"}""", 9, 1)
        val read2 = ToolCallNode(62, "r2", "read", """{"path":"b.kt"}""", 9, 2)
        val grep = ToolCallNode(63, "g1", "grep", """{"pattern":"x"}""", 9, 3)
        val edit1 = ToolCallNode(64, "e1", "edit", """{"path":"a.kt"}""", 9, 4)
        val edit2 = ToolCallNode(65, "e2", "write", """{"path":"b.kt"}""", 9, 5)
        val nodes = listOf<ChatNode>(read1, read2, grep, edit1, edit2)

        val visible = nodes.filter { it.rendersInTranscript(nodes, TranscriptMode.CONCISE) }
        assertEquals(listOf(61L, 63L, 64L), visible.map { it.seq })
    }


}
