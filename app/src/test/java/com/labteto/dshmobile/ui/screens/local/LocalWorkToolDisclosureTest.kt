package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkToolDisclosureTest {
    private fun message(
        id: String, role: String, content: String, at: Long,
    ) = LocalHarnessMessage(id = id, role = role, content = content, createdAt = at)

    private fun tool(id: String, at: Long, error: Boolean = false) =
        message(id, "tool", "回执 $id", at).copy(
            toolName = "mcp__GitHub__fetch_file",
            toolCallId = id,
            toolDeclaredAt = at - 2,
            toolDeclaredSequence = at,
            toolIsError = error,
        )

    private fun LocalTranscriptItem.WorkTools.toolIds(): List<String> =
        operations.filterIsInstance<LocalTranscriptItem.WorkStep>()
            .flatMap { listOf(it.message.id) + it.groupedMessages.map(LocalHarnessMessage::id) }

    @Test fun publicNarrationAndFinalAnswerRemainVisibleWhileAllToolsMoveToTurnHeader() {
        val visible = foldWorkToolsToTurnStart(buildLocalTranscript(listOf(
            message("user", "user", "检查仓库", 10),
            message("intro", "progress", "开始检查仓库代码。", 11),
            tool("read", 20),
            message("finding", "progress", "发现一处实现不合理，准备修改。", 25),
            tool("update", 30),
            message("verify", "progress", "已经修复，正在验证。", 35),
            message("answer", "assistant", "最终结论：已完成。", 40),
        )))
        assertEquals(
            listOf("message:user", "work-tools:user", "work-step:intro",
                "work-step:finding", "work-step:verify", "message:answer"),
            visible.map(LocalTranscriptItem::key),
        )
        assertEquals(listOf("read", "update"),
            (visible[1] as LocalTranscriptItem.WorkTools).toolIds())
        assertEquals(2, workToolDisclosureCounts(
            (visible[1] as LocalTranscriptItem.WorkTools).operations).total)
    }

    @Test fun eachUserTurnOwnsExactlyItsOwnToolDisclosure() {
        val timeline = buildLocalTranscript(listOf(
            message("u1", "user", "第一轮", 1), tool("a", 4),
            message("answer1", "assistant", "第一轮结论", 5),
            message("u2", "user", "第二轮", 10),
            message("n2", "progress", "第二轮开始", 12),
            tool("b", 16), tool("c", 17),
            message("answer2", "assistant", "第二轮结论", 20),
        ))
        val result = foldWorkToolsToTurnStart(timeline)
        assertEquals(2, result.filterIsInstance<LocalTranscriptItem.WorkTools>().size)
        assertEquals(listOf("a"), result.filterIsInstance<LocalTranscriptItem.WorkTools>()[0].toolIds())
        assertEquals(listOf("b", "c"), result.filterIsInstance<LocalTranscriptItem.WorkTools>()[1].toolIds())
        assertEquals(listOf("work-tools:u1", "work-tools:u2"),
            result.filterIsInstance<LocalTranscriptItem.WorkTools>().map { it.key })
        assertEquals("message:answer2", result.last().key)
    }

    @Test fun pendingCallKeepsSameDisclosureIdentityWhenDurableReceiptArrives() {
        val user = LocalTranscriptItem.Message(message("user", "user", "检查", 100))
        val activity = LocalToolActivityUiItem(
            callId = "call", name = "mcp__GitHub__fetch_file", phase = LocalToolUiPhase.RUNNING,
            declaredAt = 120, declaredSequence = 3,
        )
        val pending = foldWorkToolsToTurnStart(
            appendPendingWorkToolActivities(listOf(user), listOf(activity), true),
        )
        val before = pending[1] as LocalTranscriptItem.WorkTools
        assertEquals("work-tools:user", before.key)
        assertEquals(1, workToolDisclosureCounts(before.operations).running)

        val complete = tool("receipt", 135).copy(
            toolCallId = "call", toolDeclaredAt = 120, toolDeclaredSequence = 3,
        )
        val settled = foldWorkToolsToTurnStart(appendPendingWorkToolActivities(
            buildLocalTranscript(listOf(user.message, complete)), listOf(activity), true,
        ))
        val after = settled[1] as LocalTranscriptItem.WorkTools
        assertEquals(before.key, after.key)
        assertEquals(listOf("receipt"), after.toolIds())
        assertEquals(1, workToolDisclosureCounts(after.operations).completed)
        assertFalse(after.operations.any { it is LocalTranscriptItem.WorkPendingTool })
    }

    @Test fun errorCancellationAndUnknownOutcomesStayDistinctInsideDisclosure() {
        val ok = tool("ok", 5)
        val failure = tool("failure", 8, true).copy(toolErrorCode = "NETWORK_ERROR")
        val cancelled = tool("cancelled", 10, true).copy(toolErrorCode = "TOOL_CANCELLED")
        val unknown = tool("unknown", 12, true).copy(toolErrorCode = "TOOL_OUTCOME_UNKNOWN")
        val group = foldWorkToolsToTurnStart(buildLocalTranscript(listOf(
            message("user", "user", "执行", 1), ok, failure, cancelled, unknown,
        ))).filterIsInstance<LocalTranscriptItem.WorkTools>().single()
        assertEquals(listOf("ok", "failure", "cancelled", "unknown"), group.toolIds())
        assertEquals(WorkToolDisclosureCounts(
            total = 4, running = 0, failed = 1, cancelled = 1, uncertain = 1,
        ), workToolDisclosureCounts(group.operations))
        assertTrue(workToolDisclosureCounts(group.operations).summary.contains("待确认"))
    }

    @Test fun largeTurnRetainsEveryCallWithoutCappingOrHidingPublicNarration() {
        val input = buildList {
            add(message("user", "user", "长任务", 1))
            (1..80).forEach { index ->
                add(tool("t$index", 100L + index * 5L))
                if (index % 10 == 0) {
                    add(message("n$index", "progress", "已检查 $index 项，继续进行。", 102L + index * 5L))
                }
            }
            add(message("final", "assistant", "核查结束", 600))
        }
        val displayed = foldWorkToolsToTurnStart(buildLocalTranscript(input))
        assertEquals(80, (displayed[1] as LocalTranscriptItem.WorkTools).toolIds().size)
        assertEquals(80, workToolDisclosureCounts(
            (displayed[1] as LocalTranscriptItem.WorkTools).operations).total)
        assertEquals(8, displayed.filterIsInstance<LocalTranscriptItem.WorkStep>()
            .count { it.message.role == "progress" })
        assertEquals("message:final", displayed.last().key)
    }

    @Test fun verifiedArtifactRemainsReachableAfterToolMovesToHeader() {
        val receipt = tool("write", 20).copy(toolExecutionId = "execution-write")
        val artifact = LocalTranscriptItem.WorkArtifact(
            LocalArtifactUiItem(reference = "report.md", category = "file",
                sourceCallId = "write", asOfSequence = 30, sourceExecutionId = "execution-write"),
        )
        val attached = composeLocalReplyAttachments(
            buildLocalTranscript(listOf(message("user", "user", "生成报告", 1), receipt)) + artifact)
        val displayed = foldWorkToolsToTurnStart(attached)
        assertEquals("work-tools:user", displayed[1].key)
        val file = displayed.filterIsInstance<LocalTranscriptItem.WorkArtifact>().single()
        assertEquals("work-tools:user", file.attachedTo)
        assertEquals("report.md", file.artifact.reference)
    }

    @Test fun historicalPrefixesAndLegacyIntermediateAssistantRemainReadable() {
        val withoutUser = foldWorkToolsToTurnStart(buildLocalTranscript(listOf(
            tool("historical", 20),
            message("step", "progress", "历史检查仍可查看", 30),
        )))
        assertEquals("work-tools:historical-prefix", withoutUser.first().key)
        assertEquals("work-step:step", withoutUser[1].key)

        val legacy = foldWorkToolsToTurnStart(buildLocalTranscript(listOf(
            message("user", "user", "审计", 1),
            message("note", "assistant", "## 阶段性核查\n\n已定位问题。", 10),
            tool("read", 20),
            message("final", "assistant", "最终结论", 30),
        )))
        assertEquals(listOf("message:user", "work-tools:user", "work-step:note",
            "message:final"), legacy.map { it.key })
        assertEquals("## 阶段性核查\n\n已定位问题。",
            (legacy[2] as LocalTranscriptItem.WorkStep).message.content)
    }

    @Test fun noToolsDoesNotChangeExistingChatOrWorkTextTimeline() {
        val original = buildLocalTranscript(listOf(
            message("u", "user", "问题", 1),
            message("p", "progress", "正在处理", 2),
            message("a", "assistant", "答案", 3),
        ))
        assertSame(original, foldWorkToolsToTurnStart(original))
    }

    @Test fun structuredFindingRemainsVisibleAsNarrativeWithOriginalReceiptFolded() {
        val discovery = tool("discovery", 20).copy(
            toolName = "work_finding",
            content = "已记录调查线索 ID=x；状态=hypothesis；发现=调用链存在明显重复；来源回执=read-1",
        )
        val displayed = foldWorkToolsToTurnStart(buildLocalTranscript(listOf(
            message("user", "user", "检查实现", 1),
            discovery,
            message("final", "assistant", "已整理修改结论", 30),
        )))
        assertEquals(listOf("message:user", "work-tools:user",
            "work-step:work-finding:discovery", "message:final"), displayed.map { it.key })
        assertEquals(listOf("discovery"),
            (displayed[1] as LocalTranscriptItem.WorkTools).toolIds())
        val finding = (displayed[2] as LocalTranscriptItem.WorkStep).message.content
        assertTrue(finding.contains("发现新线索 · 待核查"))
        assertTrue(finding.contains("调用链存在明显重复"))
        assertEquals("message:final", displayed.last().key)
    }

    @Test fun compactPollsStillCountEveryReceipt() {
        val first = tool("first", 4)
        val second = tool("second", 5)
        val item = LocalTranscriptItem.WorkStep(first, repeatCount = 2,
            groupedMessages = listOf(second))
        assertEquals(2, workToolDisclosureCounts(listOf(item)).total)
    }

    @Test fun newestFirstListRetainsEveryOriginalReceipt() {
        val old = LocalTranscriptItem.WorkStep(tool("old", 1))
        val newest = LocalTranscriptItem.WorkStep(tool("new", 2))
        val visible = visibleWorkToolOperations(listOf(old, newest), WorkToolListFilter.ALL)
        assertEquals(listOf(newest.key, old.key), visible.map(LocalTranscriptItem::key))
        assertEquals("new", (visible.first() as LocalTranscriptItem.WorkStep).message.id)
        assertEquals("mcp__GitHub__fetch_file", latestWorkTool(listOf(old, newest))?.name)
    }

    @Test fun runningAndIssueFiltersKeepNewestFirstAndNeverHideOtherReceipts() {
        val initial = LocalTranscriptItem.WorkPendingTool(LocalToolActivityUiItem(
            callId = "pending", name = "read", phase = LocalToolUiPhase.RUNNING,
        ))
        val error = LocalTranscriptItem.WorkStep(tool("failure", 2, true))
        val settled = LocalTranscriptItem.WorkStep(tool("settled", 3))
        val other = LocalTranscriptItem.WorkPendingTool(LocalToolActivityUiItem(
            callId = "recent", name = "edit", phase = LocalToolUiPhase.DECLARED,
        ))
        val full = listOf(initial, error, settled, other)
        assertEquals(listOf(other.key, initial.key),
            visibleWorkToolOperations(full, WorkToolListFilter.RUNNING).map { it.key })
        assertEquals(listOf(error.key),
            visibleWorkToolOperations(full, WorkToolListFilter.ISSUES).map { it.key })
        assertEquals(listOf(other.key, settled.key, error.key, initial.key),
            visibleWorkToolOperations(full, WorkToolListFilter.ALL).map { it.key })
        assertEquals("edit", latestRunningWorkTool(full)?.name)
    }

    @Test fun latestSummaryChangesAsPendingCallReceivesDurableReceipt() {
        val active = LocalTranscriptItem.WorkPendingTool(LocalToolActivityUiItem(
            callId = "same", name = "read", phase = LocalToolUiPhase.RUNNING,
        ))
        val done = LocalTranscriptItem.WorkStep(tool("done", 3).copy(
            toolName = "read", toolCallId = "same",
        ))
        assertEquals(LocalToolUiPhase.RUNNING, latestWorkTool(listOf(active))?.phase)
        assertEquals(LocalToolUiPhase.COMPLETED, latestWorkTool(listOf(done))?.phase)
        assertEquals(null, latestRunningWorkTool(listOf(done)))
    }
}
