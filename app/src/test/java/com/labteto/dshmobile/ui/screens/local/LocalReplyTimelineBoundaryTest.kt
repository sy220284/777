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
    private val step = LocalTranscriptItem.WorkNode(
        LocalWorkProcessNode(summary = "正在执行新的工具步骤", eventId = "new-progress"))

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

    @Test fun chatRegenerationCanStillStreamAgainstPreviousAssistant() {
        assertTrue(shouldShowWorkStreamingTail(
            running = true, usageMode = LocalUsageMode.CHAT,
            lastItem = localReplyStreamingBoundary(listOf(answer, artifact))))
    }
}
