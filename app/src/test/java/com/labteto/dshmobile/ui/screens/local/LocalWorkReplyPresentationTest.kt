package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkReplyPresentationTest {
    @Test fun workFailureAndStopRemainVisibleAfterReopeningHistory() {
        val failure = LocalHarnessMessage("failure", "system", "执行失败：网络连接中断", createdAt = 2L)
        val stopped = failure.copy(id = "stop", content = "本轮已停止。")
        val paused = failure.copy(id = "pause", content = "当前任务已无法继续扩展执行预算，已在第 64 步暂停；已有进度已保留。")
        val items = buildLocalDisplayTranscript(buildLocalTranscript(listOf(failure, stopped, paused)))
        assertEquals(listOf("message:failure", "message:stop", "message:pause"), items.map { it.key })
        assertTrue(buildLocalTranscript(listOf(failure), includeWorkProcess = false).isEmpty())
    }
    private fun message(id: String, role: String, content: String = "", toolName: String? = null,
        error: Boolean? = null): LocalHarnessMessage =
        LocalHarnessMessage(id = id, role = role, content = content,
            toolName = toolName, createdAt = id.length.toLong(), toolIsError = error)

    @Test fun builtInToolActionsDescribeTheActualCapability() {
        assertEquals("读取文件", userFacingWorkToolAction("read"))
        assertEquals("搜索文件内容", userFacingWorkToolAction("grep"))
        assertEquals("运行本地任务", userFacingWorkToolAction("bash"))
        assertEquals("修改文件", userFacingWorkToolAction("edit"))
    }

    @Test fun completedStepUsesItsOriginalDeclaredActionEvenWhenNotRunning() {
        val command = """{"command":"command -v curl && command -v wget"}"""
        val activity = com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem(
            callId = "call-1", name = "bash",
            phase = com.labteto.dshmobile.local.presentation.LocalToolUiPhase.COMPLETED,
            argumentsPreview = command,
        )
        val row = LocalTranscriptItem.WorkStep(
            message("result", "tool", "ok", "bash", false).copy(toolCallId = "call-1")
        )
        val visible = appendPendingWorkToolActivities(listOf(row), listOf(activity), running = false)
            .single() as LocalTranscriptItem.WorkStep
        assertEquals("work-tool:call-1", visible.key)
        assertEquals(command, visible.activity?.argumentsPreview)
        assertEquals("检查 curl、wget 是否可用",
            naturalWorkCommandAction(visible.activity?.argumentsPreview))
    }

    @Test fun sameMillisecondPendingCallStaysBeforeLaterCompletedCall() {
        val user = LocalTranscriptItem.Message(message("u", "user", "检查").copy(createdAt = 100L))
        val completed = LocalTranscriptItem.WorkStep(message("r", "tool", "ok", "read", false)
            .copy(createdAt = 180L, toolCallId = "second", toolDeclaredAt = 120L, toolDeclaredSequence = 6L))
        val first = com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem(
            callId = "first", name = "read",
            phase = com.labteto.dshmobile.local.presentation.LocalToolUiPhase.RUNNING,
            declaredAt = 120L, declaredSequence = 5L)
        val output = appendPendingWorkToolActivities(listOf(user, completed), listOf(first), true)
        assertEquals(listOf("message:u", "work-tool:first", "work-tool:second"), output.map { it.key })
    }

    @Test fun repeatedIdenticalStatusChecksCompactButRetainEveryReceipt() {
        val first = message("p1", "tool", "等待流水线", "workflow_run_status", false)
            .copy(toolCallId = "poll-1")
        val second = first.copy(id = "p2", content = "流水线仍在运行", toolCallId = "poll-2")
        val activity = com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem(
            callId = "poll-1", name = "workflow_run_status",
            phase = com.labteto.dshmobile.local.presentation.LocalToolUiPhase.COMPLETED,
            argumentsPreview = """{"run_id":"42"}""",
        )
        val compact = appendPendingWorkToolActivities(
            listOf(LocalTranscriptItem.WorkStep(first), LocalTranscriptItem.WorkStep(second)),
            listOf(activity, activity.copy(callId = "poll-2")), running = false,
        ).filterIsInstance<LocalTranscriptItem.WorkStep>()
        assertEquals(1, compact.size)
        assertEquals(2, compact.single().repeatCount)
        assertEquals(listOf("p2"), compact.single().groupedMessages.map { it.id })
    }

    @Test fun failedOrDifferentStatusChecksAreNeverCompacted() {
        val first = message("p1", "tool", "成功", "job_poll", false)
            .copy(toolCallId = "poll-1")
        val failed = first.copy(id = "p2", content = "失败", toolCallId = "poll-2",
            toolIsError = true)
        val activity = com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem(
            callId = "poll-1", name = "job_poll",
            phase = com.labteto.dshmobile.local.presentation.LocalToolUiPhase.COMPLETED,
            argumentsPreview = """{"id":"1"}""",
        )
        val other = activity.copy(callId = "poll-2", argumentsPreview = """{"id":"2"}""")
        val inputs = listOf(LocalTranscriptItem.WorkStep(first), LocalTranscriptItem.WorkStep(failed))
        assertEquals(2, appendPendingWorkToolActivities(inputs,
            listOf(activity, other), running = false).size)
        assertEquals(2, appendPendingWorkToolActivities(
            inputs.map { it.copy(message = it.message.copy(toolIsError = false)) },
            listOf(activity, other), running = false).size)
    }

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
        val settled = appendPendingWorkToolActivities(
            listOf(completed), listOf(activity), running = true)
        assertEquals(listOf(completed.key), settled.map { it.key })
        assertEquals(activity, (settled.single() as LocalTranscriptItem.WorkStep).activity)
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

    @Test fun toolCompletionStaysAtOriginalStartPositionAfterLaterNarration() {
        val user = message("user", "user", "检查").copy(createdAt = 100L)
        val narration = message("progress", "progress", "正在检查文件").copy(createdAt = 140L)
        val tool = message("completed", "tool", "ok", "mcp__GitHub__fetch_file", false)
            .copy(createdAt = 170L, toolCallId = "call-5", toolDeclaredAt = 120L)
        val items = buildLocalDisplayTranscript(buildLocalTranscript(listOf(user, narration, tool)))
        assertEquals(listOf("message:user", "work-tool:call-5", "work-step:progress"),
            items.map { it.key })
        val inFlight = com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem(
            callId = "call-5", name = "mcp__GitHub__fetch_file",
            phase = com.labteto.dshmobile.local.presentation.LocalToolUiPhase.RUNNING,
            declaredAt = 120L, declaredSequence = 3L)
        val beforeFinish = appendPendingWorkToolActivities(
            buildLocalDisplayTranscript(buildLocalTranscript(listOf(user, narration))),
            listOf(inFlight), running = true)
        assertEquals(items.map { it.key }, beforeFinish.map { it.key })
    }

    @Test fun mediaTimelineKeepsRunningToolBeforeLaterMediaResult() {
        val user = LocalTranscriptItem.Message(message("u", "user", "开始检查").copy(createdAt = 100L))
        val media = LocalTranscriptItem.MediaResult(
            com.labteto.dshmobile.local.model.LocalMediaResult(
                id = "image-2", model = "test", provider = "OPENAI", kind = "IMAGE",
                createdAtMillis = 150L, textOutput = "结果", sessionId = "session"))
        val pending = com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem(
            callId = "read-2", name = "mcp__GitHub__fetch_file",
            phase = com.labteto.dshmobile.local.presentation.LocalToolUiPhase.RUNNING,
            declaredAt = 120L, declaredSequence = 2L)
        val visible = appendPendingWorkToolActivities(listOf(user, media), listOf(pending), true)
        assertEquals(
            listOf("message:u", "work-tool:read-2", "media-result:image-2"),
            visible.map { it.key })
    }

    @Test fun mediaTimelineKeepsCompletedToolAtDeclarationPosition() {
        val user = message("u", "user", "开始检查").copy(createdAt = 100L)
        val progress = message("p", "progress", "继续核查").copy(createdAt = 140L)
        val completed = message("t", "tool", "完成", "mcp__GitHub__fetch_file", false)
            .copy(createdAt = 170L, toolCallId = "read-1", toolDeclaredAt = 120L)
        val transcript = buildLocalDisplayTranscript(
            buildLocalTranscript(listOf(user, progress, completed)))
        val media = com.labteto.dshmobile.local.model.LocalMediaResult(
            id = "image-1", model = "test", provider = "OPENAI", kind = "IMAGE",
            createdAtMillis = 150L, textOutput = "结果", sessionId = "session",
        )
        val combined = mergeLocalMediaTimeline(
            transcript, "session", listOf(media), emptyList())
        assertEquals(
            listOf("message:u", "work-tool:read-1", "work-step:p", "media-result:image-1"),
            combined.map { it.key })
    }

    @Test fun concurrentCallsWithSameTimestampKeepDeclarationOrderAfterReverseCompletion() {
        val user = message("u", "user", "审计").copy(createdAt = 100L)
        val progress = message("n", "progress", "继续检查").copy(createdAt = 160L)
        val completedLater = message("second", "tool", "ok", "mcp__GitHub__fetch_file", false)
            .copy(createdAt = 170L, toolCallId = "second", toolDeclaredAt = 120L, toolDeclaredSequence = 6L)
        val completedFirst = message("first", "tool", "ok", "mcp__GitHub__fetch_file", false)
            .copy(createdAt = 190L, toolCallId = "first", toolDeclaredAt = 120L, toolDeclaredSequence = 5L)
        val items = buildLocalDisplayTranscript(buildLocalTranscript(
            listOf(user, progress, completedLater, completedFirst)))
        assertEquals(listOf("message:u", "work-tool:first", "work-tool:second", "work-step:n"),
            items.map { it.key })
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
