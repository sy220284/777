package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.AgentOperationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranscriptPresentationTest {
    @Test
    fun creationReceiptStaysInChatAndBecomesResultAtSameStableKey() {
        val job = com.labteto.dshmobile.local.model.LocalMediaCreationJob(
            id = "operation-1", sessionId = "session-a", kind = "IMAGE",
            provider = "MINIMAX", model = "image-01", prompt = "海边",
            createdAtMillis = 50L,
        )
        val waiting = mergeLocalMediaTimeline(emptyList(), "session-a", emptyList(), emptyList(), listOf(job))
        assertEquals(listOf("media-result:operation-1"), waiting.map { it.key })
        assertTrue(waiting.first() is LocalTranscriptItem.CreationJob)
        val completed = com.labteto.dshmobile.local.model.LocalMediaResult(
            id = job.id, provider = job.provider, model = job.model, kind = job.kind,
            createdAtMillis = job.createdAtMillis + 15L,
            remoteUrl = "https://cdn.example.com/success.png", sessionId = job.sessionId,
        )
        val delivered = mergeLocalMediaTimeline(emptyList(), "session-a", listOf(completed),
            emptyList(), listOf(job.copy(
                phase = com.labteto.dshmobile.local.model.LocalMediaCreationPhase.SUCCEEDED,
            )))
        assertEquals(waiting.map { it.key }, delivered.map { it.key })
        assertTrue(delivered.first() is LocalTranscriptItem.MediaResult)
        assertTrue(mergeLocalMediaTimeline(emptyList(), "session-b", listOf(completed),
            emptyList(), listOf(job)).isEmpty())
    }

    @Test
    fun workArtifactsAppearInConversationWithoutDuplicateFiles() {
        val transcript = listOf(LocalTranscriptItem.Message(
            LocalHarnessMessage("answer", "assistant", "文件已生成", createdAt = 10L),
        ))
        val artifact = com.labteto.dshmobile.local.presentation.LocalArtifactUiItem(
            reference = "reports/analysis.md",
            category = "file",
            sourceCallId = "write-file",
            asOfSequence = 10L,
            currentlyAvailable = true,
        )
        val repeated = artifact.copy(asOfSequence = 30L)
        val out = appendWorkArtifactsToTranscript(transcript, listOf(artifact, repeated))
        assertEquals(2, out.size)
        assertEquals("message:answer", out.first().key)
        assertEquals(repeated, (out.last() as LocalTranscriptItem.WorkArtifact).artifact)
        assertEquals(out.map { it.key }, appendWorkArtifactsToTranscript(transcript, listOf(artifact)).map { it.key })
    }

    @Test
    fun mediaTimelineAppearsInlineInOwningSessionOnlyAndRetainsStableIdentity() {
        val chat = listOf(
            LocalTranscriptItem.Message(LocalHarnessMessage("u", "user", "画一幅", createdAt = 100L)),
            LocalTranscriptItem.Message(LocalHarnessMessage("a", "assistant", "好的", createdAt = 300L)),
        )
        val image = com.labteto.dshmobile.local.model.LocalMediaResult(
            id = "image-a", provider = "MINIMAX", model = "image-01", kind = "IMAGE",
            createdAtMillis = 200L, remoteUrl = "https://cdn.example.com/a.png",
            sessionId = "session-a",
        )
        val otherSession = image.copy(id = "image-b", sessionId = "session-b")
        val video = com.labteto.dshmobile.local.model.LocalMediaVideoTask(
            id = "video-a", offerId = "offer", routeFingerprint = "route",
            provider = "MINIMAX", model = "Hailuo", profileId = "profile",
            startedAtMillis = 250L, updatedAtMillis = 350L,
            phase = com.labteto.dshmobile.local.model.LocalMediaVideoPhase.PROCESSING,
            sessionId = "session-a",
        )
        val combined = mergeLocalMediaTimeline(chat, "session-a", listOf(image, otherSession), listOf(video))
        assertEquals(listOf(
            "message:u", "media-result:image-a", "media-video:video-a", "message:a",
        ), combined.map { it.key })
        val completed = video.copy(
            phase = com.labteto.dshmobile.local.model.LocalMediaVideoPhase.SUCCEEDED,
            downloadUrl = "https://cdn.example.com/a.mp4",
        )
        val refreshed = mergeLocalMediaTimeline(chat, "session-a", listOf(image), listOf(completed))
        assertEquals(combined.map { it.key }, refreshed.map { it.key })
        assertEquals(completed, (refreshed[2] as LocalTranscriptItem.VideoTask).task)
        assertEquals(chat, mergeLocalMediaTimeline(chat, "session-c", listOf(image), listOf(video)))
        assertEquals(chat, mergeLocalMediaTimeline(chat, "", listOf(image), listOf(video)))
    }

    @Test
    fun durableWorkFinalAnswerSuppressesTransientDuplicateTail() {
        val answer = LocalTranscriptItem.Message(message("final", "assistant", "任务已完成"))
        val progress = LocalTranscriptItem.WorkProcess(listOf(message("p1", "progress", "处理中")))
        assertFalse(shouldShowWorkStreamingTail(true, com.labteto.dshmobile.local.LocalUsageMode.WORK, answer))
        assertTrue(shouldShowWorkStreamingTail(true, com.labteto.dshmobile.local.LocalUsageMode.WORK, progress))
        assertTrue(shouldShowWorkStreamingTail(true, com.labteto.dshmobile.local.LocalUsageMode.CHAT, answer))
        assertFalse(shouldShowWorkStreamingTail(false, com.labteto.dshmobile.local.LocalUsageMode.WORK, progress))
    }

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
    fun inlineConversationMergesToolResultIntoItsNarratedStage() {
        val projected = conversationWorkProcessNodes(buildWorkProcessNodes(listOf(
            message("p1", "progress", "先检查相关文件"),
            message("t1", "tool", "/private/path/raw.txt", toolName = "read"),
            message("p2", "progress", "发现问题，开始修复"),
            message("t2", "tool", "ok", toolName = "edit_file"),
        )))
        assertEquals(2, projected.size)
        assertEquals(listOf("先检查相关文件", "发现问题，开始修复"),
            projected.map { it.summary })
        assertEquals(AgentOperationKind.Inspect, projected[0].kind)
        assertEquals(AgentOperationKind.Update, projected[1].kind)
        assertEquals(listOf(1, 1), projected.map { it.count })
        assertTrue(projected.all { it.toolContent == null })
    }

    @Test
    fun multipleToolOutcomesUseOneStepAndPreserveFailure() {
        val projected = conversationWorkProcessNodes(buildWorkProcessNodes(listOf(
            message("p1", "progress", "核对两份文件并测试"),
            message("t1", "tool", "ok", toolName = "read"),
            message("t2", "tool", "工具执行失败", toolName = "bash"),
        )))
        assertEquals(1, projected.size)
        assertEquals(2, projected.single().count)
        assertEquals(listOf(AgentOperationKind.Inspect, AgentOperationKind.Execute),
            projected.single().operationKinds)
        assertTrue(projected.single().failed)
        assertEquals(null, projected.single().toolContent)
    }

    @Test
    fun semanticStageRetainsItsOwnOperationKindsAfterMergingEarlierTools() {
        val merged = semanticWorkProcessNodes(listOf(
            LocalWorkProcessNode(operationKinds = listOf(AgentOperationKind.Inspect), count = 1),
            LocalWorkProcessNode(summary = "检查完成，进入执行", operationKinds = listOf(AgentOperationKind.Execute), count = 1),
        ))
        assertEquals(1, merged.size)
        assertEquals(listOf(AgentOperationKind.Inspect, AgentOperationKind.Execute), merged.single().operationKinds)
        assertEquals(2, merged.single().count)
    }

    @Test
    fun workStepAlsoSupportsToolBeforeNarrationWithoutRepeatingGenericLabel() {
        val projected = conversationWorkProcessNodes(buildWorkProcessNodes(listOf(
            message("t1", "tool", "ok", toolName = "bash"),
            message("t2", "tool", "ok", toolName = "read"),
            message("p1", "progress", "完成两项检查，正在核对结果"),
        )))
        assertEquals(1, projected.size)
        assertEquals("完成两项检查，正在核对结果", projected.single().summary)
        assertEquals(2, projected.single().count)
        assertEquals(null, projected.single().toolContent)
    }

    @Test
    fun knownToolProvidersShowFriendlyNamesWithoutLeakingCommands() {
        assertEquals("GitHub", userFacingWorkToolProvider("mcp__GitHub__fetch"))
        assertEquals("Figma", userFacingWorkToolProvider("mcp__Figma__use_figma"))
        assertEquals(null, userFacingWorkToolProvider("bash"))
        assertEquals(null, userFacingWorkToolProvider("custom_secret_runner"))
    }

    @Test
    fun inlineWorkStreamingDoesNotDuplicateCommittedNarration() {
        assertFalse(shouldShowInlineWorkPreview("正在检查", "正在检查项目的最新代码"))
        assertTrue(shouldShowInlineWorkPreview("开始修复新问题", "正在检查项目的最新代码"))
        assertFalse(shouldShowInlineWorkPreview("  ", null))
        assertTrue(shouldShowInlineWorkPreview("现在重新运行测试", null))
    }

    @Test
    fun genericExecutionLabelsAreNotTreatedAsNarratedStages() {
        assertEquals(null, localWorkProcessSummary("运行任务步骤"))
        assertEquals(null, localWorkProcessSummary("处理当前步骤"))
        assertEquals("已经定位问题", localWorkProcessSummary("已经定位问题"))
    }

    @Test
    fun legacyMiniMaxThinkingDoesNotShowAsWorkProgressOrInlinePreview() {
        assertEquals(null, localWorkProcessSummary("<think>用户要看仓库更新，先按系统提示检查目录"))
        assertEquals("已确认新提交", localWorkProcessSummary("<think>内部分析</think>已确认新提交"))
        assertEquals(null, localWorkProcessSummary("用户要我先看系统提示"))
        assertEquals("已核对仓库更新", localWorkProcessSummary("已核对仓库更新"))
        assertEquals("", inlineWorkPreviewText("<think>用户需要我先分析", null))
        assertEquals("现在开始检查", inlineWorkPreviewText("<think>分析完成</think>现在开始检查", null))
        assertEquals("已经完成", inlineWorkPreviewText("<think>分析完成</think>已经完成", "<think>旧思考</think>"))
    }

    @Test
    fun inlineWorkStreamDisplaysOnlyUncommittedSuffix() {
        assertEquals("下一段", inlineWorkPreviewText("已有内容下一段", "已有内容"))
        assertEquals("", inlineWorkPreviewText("已有内容", "已有内容"))
        assertEquals("", inlineWorkPreviewText("已有", "已有内容"))
        assertEquals("不同阶段", inlineWorkPreviewText("不同阶段", "已有内容"))
    }

    @Test
    fun semanticMilestonesKeepToolOutcomesAndUnlabelledOperations() {
        val nodes = semanticWorkProcessNodes(buildWorkProcessNodes(listOf(
            message("p1", "progress", "读取文件"),
            message("t1", "tool", "private raw content", toolName = "read"),
            message("p2", "progress", "执行验证"),
            message("t2", "tool", "工具执行失败", toolName = "bash"),
        )))
        assertEquals(2, nodes.size)
        assertEquals(1, nodes[0].count)
        assertEquals(AgentOperationKind.Inspect, nodes[0].kind)
        assertTrue(nodes[1].failed)
        assertTrue(nodes.all { it.toolContent == null })
        val unlabelled = semanticWorkProcessNodes(buildWorkProcessNodes(listOf(
            message("t3", "tool", "done", toolName = "read"),
            message("t4", "tool", "done", toolName = "grep"),
        )))
        assertEquals(2, unlabelled.size)
    }

    @Test
    fun workProcessNodesIgnoreReasoningAndKeepEachToolResult() {
        val nodes = buildWorkProcessNodes(
            listOf(
                message("r1", "reasoning", "先确认相关实现，再做最小修改。"),
                message("t1", "tool", "/private/project/A.kt raw content", toolName = "read"),
                message("t2", "tool", "/private/project/B.kt raw content", toolName = "read_file"),
                message("t3", "tool", "many grep matches", toolName = "grep"),
            ),
        )

        assertEquals(3, nodes.size)
        assertEquals(AgentOperationKind.Inspect, nodes[0].kind)
        assertEquals("/private/project/A.kt raw content", nodes[0].toolContent)
        assertEquals("/private/project/B.kt raw content", nodes[1].toolContent)
        assertEquals(AgentOperationKind.Search, nodes[2].kind)
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

        assertEquals(3, nodes.size)
        assertEquals("读取权威规则和当前工作过程组件", nodes[0].summary)
        assertEquals(AgentOperationKind.Inspect, nodes[1].kind)
        assertEquals("/private/path/AGENTS.md raw body", nodes[1].toolContent)
        assertEquals("/private/path/UI.kt raw body", nodes[2].toolContent)
        assertTrue(nodes[0].summary?.contains("/private/path") == false)
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

        assertEquals(4, nodes.size)
        assertEquals("先定位展示逻辑", nodes[0].summary)
        assertEquals(AgentOperationKind.Search, nodes[1].kind)
        assertEquals("再补回归测试", nodes[2].summary)
        assertEquals(AgentOperationKind.Update, nodes[3].kind)
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
    fun technicalProgressCannotExposeRawCommandsOrPaths() {
        assertEquals(null, localWorkProcessSummary("git status --short"))
        assertEquals(null, localWorkProcessSummary("/private/path/config.json"))
        assertEquals("已经读取 … 并校验", localWorkProcessSummary(
            "已经读取 /private/path/config.json 并校验",
        ))
        assertEquals("准备继续", localWorkProcessSummary("准备继续"))
    }

    @Test
    fun reasoningOnlyWorkProcessProducesNoUserFacingNode() {
        val nodes = buildWorkProcessNodes(
            listOf(message("r1", "reasoning", "这段自由推理不能出现在界面里")),
        )

        assertTrue(nodes.isEmpty())
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
    @Test
    fun structuredSuccessDoesNotTreatErrorDocumentationAsFailedTool() {
        val nodes = buildWorkProcessNodes(listOf(LocalHarnessMessage(id = "tool", role = "tool",
            content = "说明：[NETWORK_ERROR] 是错误代码", createdAt = 1L, toolName = "read_file", toolIsError = false)))
        assertFalse(nodes.single().failed)
    }
    @Test
    fun workTimelineNarrationKeepsStageSummaryAndOptionalExplanatoryText() {
        val note = localWorkProcessNarration(
            "已经找到造成页面卡住的核心问题。需要修正执行流展示顺序，避免阶段成果再次被隐藏。"
        )
        assertEquals("已经找到造成页面卡住的核心问题。", note?.first)
        assertEquals("需要修正执行流展示顺序，避免阶段成果再次被隐藏。", note?.second)
        assertEquals(null, localWorkProcessNarration("git status --short"))
        assertEquals(null, localWorkProcessNarration("我需要先检查内部提示"))
        assertEquals(null, localWorkProcessNarration("/private/secret/config.json"))
    }

    @Test
    fun narrativeDetailsOmitCommandLinesAndPrivatePaths() {
        val note = localWorkProcessNarration(
            "已经完成第一轮核查，发现了一个重要问题。\ngit status --short\n详见 /private/secret/file.txt 的异常记录。"
        )
        assertEquals("已经完成第一轮核查，发现了一个重要问题。", note?.first)
        assertEquals("详见 … 的异常记录。", note?.second)
        assertTrue(note?.second?.contains("git status") == false)
        assertTrue(note?.second?.contains("/private/secret") == false)
    }

    @Test
    fun workTimelinePreservesAllStagesAndProviderForMergedToolResult() {
        val messages = (1..9).flatMap { index ->
            listOf(
                message("p$index", "progress", "阶段$index 已核查"),
                message("t$index", "tool", "result", toolName = "mcp__GitHub__fetch"),
            )
        }
        val timeline = conversationWorkProcessNodes(buildWorkProcessNodes(messages))
        assertEquals(9, timeline.size)
        assertEquals("阶段1 已核查", timeline.first().summary)
        assertEquals("阶段9 已核查", timeline.last().summary)
        assertEquals("GitHub", userFacingWorkToolProvider(timeline.first().toolName))
        assertTrue(timeline.all { it.toolContent == null })
    }

    @Test
    fun workDisplayProjectsIndividualLazyItemsWithoutChangingDurableTranscript() {
        val source = listOf(
            message("u1", "user", "检查"),
            message("p1", "progress", "检查相关分支"),
            message("t1", "tool", "result", toolName = "read"),
            message("t2", "tool", "result", toolName = "edit_file"),
            message("p2", "progress", "发现了一个需要修复的问题"),
            message("a1", "assistant", "检查完毕"),
        )
        val durable = buildLocalTranscript(source)
        assertEquals(3, durable.size)
        assertEquals(4, (durable[1] as LocalTranscriptItem.WorkProcess).messages.size)
        val display = buildLocalDisplayTranscript(durable)
        assertEquals(6, display.size)
        assertEquals(
            listOf("work-step:p1", "work-step:t1", "work-step:t2", "work-step:p2"),
            display.filterIsInstance<LocalTranscriptItem.WorkStep>().map { it.key },
        )
        assertEquals("检查完毕", (display.last() as LocalTranscriptItem.Message).message.content)
    }

    @Test
    fun repeatedPollingCompactsOnlyAdjacentEquivalentChecks() {
        val work = LocalTranscriptItem.WorkProcess(listOf(
            message("t1", "tool", "running", toolName = "mcp__GitHub__fetch_workflow_run_jobs"),
            message("t2", "tool", "running", toolName = "mcp__GitHub__fetch_workflow_run_jobs"),
            message("t3", "tool", "output", toolName = "mcp__GitHub__fetch_file"),
            message("t4", "tool", "output", toolName = "mcp__GitHub__fetch_file"),
            message("p1", "progress", "发现关键问题"),
            message("t5", "tool", "output", toolName = "mcp__GitHub__fetch_workflow_run_jobs"),
        ))
        val projected = buildLocalDisplayTranscript(listOf(work))
            .filterIsInstance<LocalTranscriptItem.WorkStep>()
        assertEquals(5, projected.size)
        assertEquals(2, projected.first().repeatCount)
        assertEquals(1, projected[1].repeatCount)
        assertEquals(1, projected[2].repeatCount)
        assertEquals("work-step:t5", projected.last().key)
    }

    @Test
    fun narrativeProjectionKeepsParagraphsButRedactsCommandsAndReasoning() {
        val content = "发现阻塞。需要先修正日志。\ngit status --short\n已核查 /private/test/config.json 的异常"
        val visible = localWorkTimelineText(content)!!
        assertTrue(visible.contains("发现阻塞。需要先修正日志。"))
        assertTrue(visible.contains("已核查 … 的异常"))
        assertFalse(visible.contains("git status"))
        assertFalse(visible.contains("/private/test/config.json"))
        assertEquals(null, localWorkTimelineText("我需要先检查内部提示"))
        assertEquals(null, localWorkTimelineText("/private/test/config.json"))
        assertTrue(isWorkTimelineMilestone("发现一个异常"))
        assertFalse(isWorkTimelineMilestone("正在读取代码"))
    }

    @Test
    fun longWorkDisplayKeepsAllOperationsWithStableKeys() {
        val durable = LocalTranscriptItem.WorkProcess(
            (1..250).map { message("tool-$it", "tool", "result-$it", toolName = "read_file") },
        )
        val visible = buildLocalDisplayTranscript(listOf(durable))
        assertEquals(250, visible.size)
        assertEquals(250, visible.map { it.key }.distinct().size)
        assertEquals("work-step:tool-1", visible.first().key)
        assertEquals("work-step:tool-250", visible.last().key)
    }

    @Test
    fun timelineTailIndexIncludesPendingPlanReviewAndHistoryLoadingRows() {
        assertEquals(
            12,
            localTranscriptLastListIndex(
                transcriptItemCount = 11, showPagingRow = true,
                showEmptyWorkbench = false, showEmptyGroupSetup = false,
                showPlanReview = true, showStreamingTail = false,
            ),
        )
        assertEquals(
            13,
            localTranscriptLastListIndex(
                transcriptItemCount = 11, showPagingRow = true,
                showEmptyWorkbench = false, showEmptyGroupSetup = false,
                showPlanReview = true, showStreamingTail = true,
            ),
        )
    }

    @Test
    fun timelineTailIndexIncludesEmptyStateAndDoesNotInventItems() {
        assertEquals(
            1,
            localTranscriptLastListIndex(
                transcriptItemCount = 0, showPagingRow = false,
                showEmptyWorkbench = true, showEmptyGroupSetup = false,
                showPlanReview = false, showStreamingTail = true,
            ),
        )
        assertEquals(
            2,
            localTranscriptLastListIndex(
                transcriptItemCount = 0, showPagingRow = true,
                showEmptyWorkbench = true, showEmptyGroupSetup = false,
                showPlanReview = false, showStreamingTail = true,
            ),
        )
        assertEquals(
            0,
            localTranscriptLastListIndex(
                transcriptItemCount = 0, showPagingRow = false,
                showEmptyWorkbench = false, showEmptyGroupSetup = true,
                showPlanReview = false, showStreamingTail = false,
            ),
        )
        assertEquals(
            -1,
            localTranscriptLastListIndex(
                transcriptItemCount = 0, showPagingRow = false,
                showEmptyWorkbench = false, showEmptyGroupSetup = false,
                showPlanReview = false, showStreamingTail = false,
            ),
        )
    }

}
