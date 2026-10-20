package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkReplyPresentationTest {
    private fun message(id: String, role: String, content: String = "", toolName: String? = null,
        error: Boolean? = null): LocalHarnessMessage =
        LocalHarnessMessage(id = id, role = role, content = content,
            toolName = toolName, createdAt = id.length.toLong(), toolIsError = error)

    @Test fun independentToolCallsKeepSeparateChronologicalRows() {
        val events = listOf(
            message("read-one", "tool", "ok", "mcp_github_fetch_file", false),
            message("read-two", "tool", "ok", "mcp_github_fetch_file", false),
            message("explain", "progress", "发现两处需要核对的实现。"),
            message("check", "tool", "ok", "mcp_github_get_pr_info", false),
        )
        val displayed = buildLocalDisplayTranscript(buildLocalTranscript(events))
        assertEquals(4, displayed.size)
    }

    @Test fun publicProgressKeepsMarkdownAndLongContent() {
        val content = "## 核查结果\n\n- 第一处\n- 第二处\n\n```kotlin\nval count = 2\n```\n\n" +
            "这是需要完整展示的公开说明。".repeat(150)
        assertEquals(content, localWorkTimelineText(content))
    }

    @Test fun consecutiveToolRowsBecomeOneSemanticStepWithoutLosingTheAnswer() {
        val timeline = listOf(
            message("one", "tool", "ok", "first_tool"),
            message("two", "tool", "ok", "second_tool"),
            message("narration", "progress", "已完成资料核对"),
            message("three", "tool", "ok", "third_tool"),
        )
        val answer = message("answer", "assistant", "结论正文")
        val projection = buildLocalDisplayTranscript(
            listOf(LocalTranscriptItem.WorkProcess(timeline), LocalTranscriptItem.Message(answer))
        )
        val steps = projection.filterIsInstance<LocalTranscriptItem.WorkNode>()
        assertEquals(1, steps.size)
        assertEquals("已完成资料核对", steps.single().node.summary)
        assertEquals(3, steps.single().node.count)
        assertEquals("message:answer", projection.last().key)
    }

    @Test fun failuresRemainVisibleAndNeverMergeWithSuccessfulToolResults() {
        val timeline = listOf(
            message("1", "tool", "ok", "first_tool", false),
            message("2", "tool", "ok", "second_tool", false),
            message("3", "tool", "failed", "third_tool", true),
            message("4", "tool", "failed", "fourth_tool", true),
        )
        val steps = buildLocalDisplayTranscript(listOf(LocalTranscriptItem.WorkProcess(timeline)))
            .filterIsInstance<LocalTranscriptItem.WorkNode>()
        assertEquals(2, steps.size)
        assertEquals(2, steps[0].node.count)
        assertFalse(steps[0].node.failed)
        assertEquals(2, steps[1].node.count)
        assertTrue(steps[1].node.failed)
    }

    @Test fun thinkingOnlyStaysPrivateAndEventIdentityRemainsStable() {
        val reasoning = message("reasoning", "reasoning", "secret")
        val items = buildLocalDisplayTranscript(listOf(LocalTranscriptItem.WorkProcess(listOf(reasoning))))
        assertTrue(items.single() is LocalTranscriptItem.WorkProcess)
        val narration = message("narration", "progress", "正在核对数据")
        val output = buildLocalDisplayTranscript(listOf(LocalTranscriptItem.WorkProcess(listOf(narration))))
        assertEquals("work-node:narration", output.single().key)
    }
}
