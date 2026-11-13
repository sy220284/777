package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReplyTimelineBoundaryTest {
    private val answer = LocalTranscriptItem.Message(
        LocalHarnessMessage("final", "assistant", "已经完成最终答复", createdAt = 1L))
    private val artifact = LocalTranscriptItem.WorkArtifact(
        LocalArtifactUiItem(reference = "out/report.md", category = "file",
            sourceCallId = null, asOfSequence = 2L))
    private val step = LocalTranscriptItem.WorkStep(
        LocalHarnessMessage("new-progress", "progress", "正在执行新的工具步骤", createdAt = 3L))

    @Test fun trailingWorkArtifactMustNotReviveCompletedAssistantStreamingPreview() {
        val timeline = listOf(answer, artifact)
        assertFalse(shouldShowWorkStreamingTail(
            running = true, usageMode = LocalUsageMode.WORK,
            lastItem = localReplyStreamingBoundary(timeline)))
    }

    @Test fun aSubsequentWorkStepStillAllowsStreamingPreview() {
        val timeline = listOf(answer, artifact, step)
        assertTrue(shouldShowWorkStreamingTail(
            running = true, usageMode = LocalUsageMode.WORK,
            lastItem = localReplyStreamingBoundary(timeline)))
    }

    @Test fun streamingDedupReadsRawDurableProgressFromThisUserTurn() {
        val history = listOf(
            LocalHarnessMessage("user-1", "user", "第一轮", createdAt = 1),
            LocalHarnessMessage("progress-1", "progress", "第一轮已完成", createdAt = 2),
            LocalHarnessMessage("user-2", "user", "第二轮", createdAt = 3),
        )
        org.junit.Assert.assertNull(latestDurableWorkNarrativeInCurrentTurn(history))
        val current = history + listOf(
            LocalHarnessMessage("progress-2", "progress", "正在写入最终报告", createdAt = 4),
            LocalHarnessMessage("tool-2", "tool", "内部工具回执", createdAt = 5),
        )
        val durable = latestDurableWorkNarrativeInCurrentTurn(current)
        org.junit.Assert.assertEquals("正在写入最终报告", durable)
        org.junit.Assert.assertEquals("", inlineWorkPreviewText("正在写入最终报告", durable))
        org.junit.Assert.assertEquals("，请稍候",
            inlineWorkPreviewText("正在写入最终报告，请稍候", durable))
    }

    @Test fun chatRegenerationCanStillStreamAgainstPreviousAssistant() {
        assertTrue(shouldShowWorkStreamingTail(
            running = true, usageMode = LocalUsageMode.CHAT,
            lastItem = localReplyStreamingBoundary(listOf(answer, artifact))))
    }
}
