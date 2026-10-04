package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.ui.AgentOperationKind
import com.labteto.dshmobile.ui.components.DsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun chatProjectionShowsCompactThinkingButHidesToolNoise() {
        val items = buildLocalTranscript(
            messages = listOf(
                message("u1", "user", "在吗"),
                message("r1", "reasoning", "先判断用户是在确认我是否在线"),
                message("r2", "reasoning", "保持简短回应即可"),
                message("p1", "progress", "准备调用工具"),
                message("t1", "tool", "tool output", toolName = "read"),
                message("a1", "assistant", "在。"),
            ),
            includeWorkProcess = false,
        )

        assertEquals(3, items.size)
        assertEquals("user", (items[0] as LocalTranscriptItem.Message).message.role)
        val thinking = items[1] as LocalTranscriptItem.Thinking
        assertEquals(listOf("r1", "r2"), thinking.messages.map { it.id })
        assertEquals("assistant", (items[2] as LocalTranscriptItem.Message).message.role)
        assertEquals("在。", (items[2] as LocalTranscriptItem.Message).message.content)
    }

    @Test
    fun workProcessNodesIgnoreReasoningAndCollapseStructuredOperations() {
        val nodes = buildWorkProcessNodes(
            listOf(
                message("r1", "reasoning", "先确认相关实现，再做最小修改。"),
                message("t1", "tool", "/private/project/A.kt raw content", toolName = "read"),
                message("t2", "tool", "/private/project/B.kt raw content", toolName = "read_file"),
                message("t3", "tool", "many grep matches", toolName = "grep"),
            ),
        )

        assertEquals(2, nodes.size)
        assertEquals(AgentOperationKind.Inspect, nodes[0].kind)
        assertEquals(2, nodes[0].count)
        assertEquals(AgentOperationKind.Search, nodes[1].kind)
    }

    @Test
    fun workProcessUsesModelProgressAsTheVisibleStepDescription() {
        val nodes = buildWorkProcessNodes(
            listOf(
                message("p1", "progress", "读取权威规则和当前工作过程组件"),
                message("t1", "tool", "/private/path/AGENTS.md raw body", toolName = "read"),
                message("t2", "tool", "/private/path/UI.kt raw body", toolName = "read_file"),
            ),
        )

        assertEquals(1, nodes.size)
        assertEquals("读取权威规则和当前工作过程组件", nodes.single().summary)
        assertEquals(AgentOperationKind.Inspect, nodes.single().kind)
        assertEquals(2, nodes.single().count)
        assertTrue(nodes.single().summary?.contains("/private/path") == false)
    }

    @Test
    fun eachProgressMessageStartsANewVisibleWorkStep() {
        val nodes = buildWorkProcessNodes(
            listOf(
                message("p1", "progress", "先定位展示逻辑"),
                message("t1", "tool", "matches", toolName = "grep"),
                message("p2", "progress", "再补回归测试"),
                message("t2", "tool", "updated", toolName = "edit_file"),
            ),
        )

        assertEquals(2, nodes.size)
        assertEquals("先定位展示逻辑", nodes[0].summary)
        assertEquals(AgentOperationKind.Search, nodes[0].kind)
        assertEquals("再补回归测试", nodes[1].summary)
        assertEquals(AgentOperationKind.Update, nodes[1].kind)
    }

    @Test
    fun progressAppearsBeforeItsToolFinishesSoCurrentStepIsVisibleImmediately() {
        val nodes = buildWorkProcessNodes(
            listOf(message("p1", "progress", "正在运行 Android 16 仪器测试")),
        )

        assertEquals(1, nodes.size)
        assertEquals("正在运行 Android 16 仪器测试", nodes.single().summary)
        assertEquals(0, nodes.single().count)
    }

    @Test
    fun workProcessSummaryIsWhitespaceNormalizedAndBounded() {
        val summary = localWorkProcessSummary(
            "  检查主线\n\n   然后核对组件   " + "a".repeat(LOCAL_WORK_PROCESS_SUMMARY_LIMIT),
        )

        assertTrue(summary!!.startsWith("检查主线 然后核对组件 "))
        assertTrue(summary.length <= LOCAL_WORK_PROCESS_SUMMARY_LIMIT)
    }

    @Test
    fun reasoningOnlyWorkProcessProducesNoUserFacingNode() {
        val nodes = buildWorkProcessNodes(
            listOf(message("r1", "reasoning", "这段自由推理不能出现在界面里")),
        )

        assertTrue(nodes.isEmpty())
    }

    @Test
    fun workProcessAggregateStatusKeepsFailureVisibleAfterRunStops() {
        val failed = LocalWorkProcessNode(
            operationKinds = listOf(AgentOperationKind.Execute),
            failed = true,
            count = 1,
        )
        val completed = LocalWorkProcessNode(
            operationKinds = listOf(AgentOperationKind.Search),
            count = 1,
        )

        assertEquals(DsStatus.Running, workProcessStatus(listOf(failed, completed), running = true))
        assertEquals(DsStatus.Failed, workProcessStatus(listOf(failed, completed), running = false))
        assertEquals(DsStatus.Done, workProcessStatus(listOf(completed), running = false))
    }

    @Test
    fun workProcessShowsRecentNodesUntilUserRequestsEverything() {
        val nodes = (1..12).map { index ->
            LocalWorkProcessNode(
                operationKinds = listOf(AgentOperationKind.Generic),
                count = index,
            )
        }
        val recent = visibleWorkProcessNodes(nodes, showAll = false)
        val all = visibleWorkProcessNodes(nodes, showAll = true)
        assertEquals(LOCAL_WORK_PROCESS_COLLAPSED_NODE_LIMIT, recent.size)
        assertEquals(5, recent.first().count)
        assertEquals(12, recent.last().count)
        assertEquals(12, all.size)
    }

    @Test
    fun visibleDialogueQuotaIgnoresBackgroundWorkMessages() {
        val messages = buildList {
            repeat(10) { turn ->
                add(message("u$turn", "user", "问题-$turn"))
                repeat(12) { step ->
                    val role = when (step % 3) {
                        0 -> "reasoning"
                        1 -> "progress"
                        else -> "tool"
                    }
                    add(message("bg-$turn-$step", role, "后台-$turn-$step", toolName = "read"))
                }
                add(message("a$turn", "assistant", "回答-$turn"))
            }
        }

        assertEquals(20, userVisibleDialogueMessageCount(messages))
        assertTrue(needsMoreUserVisibleDialogue(messages, target = 200))
    }

    @Test
    fun olderDialogueBackfillRestoresVisibleQuotaWithoutCountingToolNoise() {
        val live = buildList {
            repeat(10) { turn ->
                add(message("live-u$turn", "user", "当前问题-$turn"))
                repeat(16) { step ->
                    add(message("live-tool-$turn-$step", "tool", "结果", toolName = "read"))
                }
                add(message("live-a$turn", "assistant", "当前回答-$turn"))
            }
        }
        val older = buildList {
            repeat(90) { turn ->
                add(message("old-u$turn", "user", "旧问题-$turn"))
                repeat(4) { step ->
                    add(message("old-bg-$turn-$step", "progress", "旧后台-$turn-$step"))
                }
                add(message("old-a$turn", "assistant", "旧回答-$turn"))
            }
        }

        assertEquals(20, userVisibleDialogueMessageCount(live))
        val merged = mergeLocalTranscriptHistory(older, live)
        assertEquals(200, userVisibleDialogueMessageCount(merged))
        assertFalse(needsMoreUserVisibleDialogue(merged, target = 200))
    }

    @Test
    fun legacyToolAssistantDoesNotConsumeVisibleDialogueQuota() {
        val messages = listOf(
            message("u1", "user", "帮我检查"),
            message("a-process", "assistant", "我先读取文件"),
            message("t1", "tool", "raw", toolName = "read"),
            message("a-final", "assistant", "检查完成"),
        )

        assertEquals(2, userVisibleDialogueMessageCount(messages))
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
