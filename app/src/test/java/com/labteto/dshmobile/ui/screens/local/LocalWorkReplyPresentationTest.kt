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
        assertEquals(listOf("work-step:read-one", "work-step:read-two",
            "work-step:explain", "work-step:check"), displayed.map { it.key })
    }

    @Test fun publicProgressKeepsMarkdownAndLongContent() {
        val content = "## 核查结果\n\n- 第一处\n- 第二处\n\n```kotlin\nval count = 2\n```\n\n" +
            "这是需要完整展示的公开说明。".repeat(150)
        assertEquals(content, localWorkTimelineText(content))
    }

    @Test fun toolRowsAndNarrationKeepSeparateOrderBeforeFinalAnswer() {
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
        val steps = projection.filterIsInstance<LocalTranscriptItem.WorkStep>()
        assertEquals(4, steps.size)
        assertEquals(listOf("one", "two", "narration", "three"),
            steps.map { it.message.id })
        assertEquals("已完成资料核对", steps[2].message.content)
        assertEquals("message:answer", projection.last().key)
    }

    @Test fun failuresKeepTheirOriginalEventStatusWithoutMerging() {
        val timeline = listOf(
            message("1", "tool", "ok", "first_tool", false),
            message("2", "tool", "ok", "second_tool", false),
            message("3", "tool", "failed", "third_tool", true),
            message("4", "tool", "failed", "fourth_tool", true),
        )
        val steps = buildLocalDisplayTranscript(listOf(LocalTranscriptItem.WorkProcess(timeline)))
            .filterIsInstance<LocalTranscriptItem.WorkStep>()
        assertEquals(4, steps.size)
        assertEquals(listOf("1", "2", "3", "4"), steps.map { it.message.id })
        assertFalse(steps[0].message.toolIsError ?: true)
        assertFalse(steps[1].message.toolIsError ?: true)
        assertTrue(steps[2].message.toolIsError ?: false)
        assertTrue(steps[3].message.toolIsError ?: false)
    }

    @Test fun descriptiveGitHubOperationsAreDistinctAndNotRawToolNames() {
        assertEquals("扫描 PR 改动文件",
            userFacingWorkToolAction("mcp__GitHub__list_pr_changed_filenames"))
        assertEquals("获取 GitHub Blob 内容",
            userFacingWorkToolAction("mcp__GitHub__fetch_blob"))
        assertEquals("查看 PR 详情",
            userFacingWorkToolAction("mcp__GitHub__get_pr_info"))
        assertEquals("读取代码文件",
            userFacingWorkToolAction("mcp__GitHub__fetch_file"))
        assertEquals("检查流水线状态",
            userFacingWorkToolAction("mcp__GitHub__get_commit_combined_status"))
        assertEquals(null, userFacingWorkToolAction("mcp__Figma__get_file"))
    }

    @Test fun legacyIntermediateAssistantNarrationIsPreservedAsMarkdownBeforeTool() {
        val content = "## 审计发现\n\n- 风险项\n\n```kotlin\nval ok = true\n```"
        val items = buildLocalDisplayTranscript(buildLocalTranscript(listOf(
            message("u", "user", "检查代码"),
            message("n", "assistant", content),
            message("t", "tool", "ok", "mcp_github_fetch_file", false),
            message("a", "assistant", "最终结论"),
        )))
        assertEquals(listOf("message:u", "work-step:n", "work-step:t", "message:a"),
            items.map { it.key })
        assertEquals(content, (items[1] as LocalTranscriptItem.WorkStep).message.content)
    }

    @Test fun pendingToolIsReplacedByItsMatchingDurableCompletion() {
        val activity = com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem(
            callId = "call-1", name = "mcp__GitHub__fetch_file",
            phase = com.labteto.dshmobile.local.presentation.LocalToolUiPhase.DECLARED,
        )
        val pending = appendPendingWorkToolActivities(emptyList(), listOf(activity), running = true)
        assertEquals(listOf("work-tool:call-1"), pending.map { it.key })
        assertTrue(pending.single() is LocalTranscriptItem.WorkPendingTool)

        val completed = LocalTranscriptItem.WorkStep(
            message("result", "tool", "ok", "mcp__GitHub__fetch_file", false)
                .copy(toolCallId = "call-1"),
        )
        assertEquals(pending.single().key, completed.key)
        assertEquals(listOf(completed), appendPendingWorkToolActivities(
            listOf(completed), listOf(activity), running = true))
        assertEquals(emptyList<LocalTranscriptItem>(),
            appendPendingWorkToolActivities(emptyList(), listOf(activity), running = false))
    }

    @Test fun concurrentPendingToolsAppearAtOriginalTimesBeforeLaterNarration() {
        val user = LocalTranscriptItem.Message(message("u", "user", "核查").copy(createdAt = 100L))
        val narrative = LocalTranscriptItem.WorkStep(
            message("n", "progress", "已发现风险点").copy(createdAt = 140L))
        val result = LocalTranscriptItem.WorkStep(
            message("r", "tool", "ok", "mcp__GitHub__fetch_file", false)
                .copy(createdAt = 155L, toolCallId = "finished"))
        val answer = LocalTranscriptItem.Message(message("a", "assistant", "最终结论").copy(createdAt = 190L))
        val one = com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem(
            callId = "one", name = "mcp__GitHub__fetch_file",
            phase = com.labteto.dshmobile.local.presentation.LocalToolUiPhase.RUNNING,
            declaredAt = 120L, declaredSequence = 5L)
        val two = one.copy(callId = "two", declaredAt = 130L, declaredSequence = 6L)
        val done = one.copy(callId = "finished", declaredAt = 145L, declaredSequence = 7L)
        val visible = appendPendingWorkToolActivities(
            listOf(user, narrative, result, answer),
            listOf(two, done, one), running = true)
        assertEquals(
            listOf("message:u", "work-tool:one", "work-tool:two", "work-step:n",
                "work-tool:finished", "message:a"),
            visible.map { it.key })
    }

    @Test fun unfinishedCallsRemainDistinctEvenAfterThirtyTwoEntries() {
        val activities = (1..48).map { index ->
            com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem(
                callId = "c$index", name = "mcp__GitHub__fetch_file",
                phase = com.labteto.dshmobile.local.presentation.LocalToolUiPhase.RUNNING,
                declaredAt = 1_000L + index, declaredSequence = index.toLong())
        }
        val entries = appendPendingWorkToolActivities(emptyList(), activities.reversed(), running = true)
        assertEquals(48, entries.size)
        assertEquals("work-tool:c1", entries.first().key)
        assertEquals("work-tool:c48", entries.last().key)
    }

    @Test fun toolOutcomeDoesNotMislabelCancellationOrUnknownAsSuccess() {
        val normal = message("done", "tool", "ok", "first_tool", false)
        val cancelled = normal.copy(toolIsError = true, toolErrorCode = "TOOL_CANCELLED")
        val uncertain = normal.copy(toolIsError = true, toolErrorCode = "TOOL_OUTCOME_UNKNOWN")
        val broken = normal.copy(toolIsError = true, toolErrorCode = "NETWORK_ERROR")
        assertEquals(com.labteto.dshmobile.local.presentation.LocalToolUiPhase.COMPLETED,
            completedWorkToolPhase(normal))
        assertEquals(com.labteto.dshmobile.local.presentation.LocalToolUiPhase.CANCELLED,
            completedWorkToolPhase(cancelled))
        assertEquals(com.labteto.dshmobile.local.presentation.LocalToolUiPhase.OUTCOME_UNKNOWN,
            completedWorkToolPhase(uncertain))
        assertEquals(com.labteto.dshmobile.local.presentation.LocalToolUiPhase.FAILED,
            completedWorkToolPhase(broken))
    }

    @Test fun thinkingOnlyStaysPrivateAndEventIdentityRemainsStable() {
        val reasoning = message("reasoning", "reasoning", "secret")
        val items = buildLocalDisplayTranscript(listOf(LocalTranscriptItem.WorkProcess(listOf(reasoning))))
        assertTrue(items.single() is LocalTranscriptItem.WorkProcess)
        val narration = message("narration", "progress", "正在核对数据")
        val output = buildLocalDisplayTranscript(listOf(LocalTranscriptItem.WorkProcess(listOf(narration))))
        assertEquals("work-step:narration", output.single().key)
    }
}
