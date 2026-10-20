package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.model.LocalMediaCreationJob
import com.labteto.dshmobile.local.model.LocalMediaResult
import com.labteto.dshmobile.local.model.LocalMediaVideoPhase
import com.labteto.dshmobile.local.model.LocalMediaVideoTask
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalReplyCompositionTest {
    private fun message(id: String, role: String = "assistant", at: Long = 1) =
        LocalTranscriptItem.Message(LocalHarnessMessage(id, role, id, createdAt = at))
    private fun result(id: String, owner: String?) =
        LocalTranscriptItem.MediaResult(LocalMediaResult(id, "model", "provider", "IMAGE",
            createdAtMillis = 12, textOutput = "asset", sessionId = "session",
            sourceMessageId = owner))

    @Test fun mediaBelongsToExplicitSourceMessageAndStaysAfterIt() {
        val user = message("u", "user", 1)
        val answer = message("a", "assistant", 2)
        val image = result("img", "a")
        val after = message("later", "user", 30)
        val actual = composeLocalReplyAttachments(listOf(user, answer, after, image))
        assertEquals(listOf("message:u", "message:a", "media-result:img", "message:later"),
            actual.map { it.key })
        assertEquals("message:a", (actual[2] as LocalTranscriptItem.MediaResult).attachedTo)
    }

    @Test fun missingOrLegacySourceRemainsIndependentWithoutGuessing() {
        val answer = message("a")
        val assignedElsewhere = result("offscreen", "missing")
        val legacy = result("legacy", null)
        val actual = composeLocalReplyAttachments(listOf(answer, assignedElsewhere, legacy))
        assertEquals(listOf(answer.key, assignedElsewhere.key, legacy.key), actual.map { it.key })
        assertNull((actual[1] as LocalTranscriptItem.MediaResult).attachedTo)
        assertNull((actual[2] as LocalTranscriptItem.MediaResult).attachedTo)
    }

    @Test fun pendingAndCompletedGenerationReuseTheirExistingSlot() {
        val answer = message("a")
        val pending = LocalTranscriptItem.CreationJob(LocalMediaCreationJob(
            id = "same", sessionId = "session", kind = "IMAGE", provider = "provider",
            model = "model", prompt = "image", createdAtMillis = 5,
            sourceMessageId = "a"))
        val complete = result("same", "a")
        val before = composeLocalReplyAttachments(listOf(answer, pending))
        val after = composeLocalReplyAttachments(listOf(answer, complete))
        assertEquals(before[1].key, after[1].key)
        assertEquals("message:a", (before[1] as LocalTranscriptItem.CreationJob).attachedTo)
        assertEquals("message:a", (after[1] as LocalTranscriptItem.MediaResult).attachedTo)
    }

    @Test fun videoUsesSameReplyOwnershipAndItsOwnStableAssetKey() {
        val answer = message("a")
        val video = LocalTranscriptItem.VideoTask(LocalMediaVideoTask(
            id = "v1", offerId = "offer", routeFingerprint = "route",
            provider = "provider", model = "model", profileId = "profile",
            startedAtMillis = 3, updatedAtMillis = 4, phase = LocalMediaVideoPhase.PROCESSING,
            sessionId = "session", sourceMessageId = "a"))
        val actual = composeLocalReplyAttachments(listOf(answer, video))
        assertEquals(listOf("message:a", "media-video:v1"), actual.map { it.key })
        assertEquals("message:a", (actual[1] as LocalTranscriptItem.VideoTask).attachedTo)
    }

    @Test fun workArtifactAttachesOnlyToItsVerifiedSourceToolCall() {
        val step = LocalTranscriptItem.WorkStep(LocalHarnessMessage(
            id = "step", role = "tool", content = "done", toolName = "read",
            createdAt = 7, toolCallId = "tool-1"))
        val matching = LocalTranscriptItem.WorkArtifact(LocalArtifactUiItem(
            reference = "report.md", category = "file", sourceCallId = "tool-1",
            asOfSequence = 4))
        val orphan = LocalTranscriptItem.WorkArtifact(LocalArtifactUiItem(
            reference = "legacy.md", category = "file", sourceCallId = null,
            asOfSequence = 5))
        val actual = composeLocalReplyAttachments(listOf(step, orphan, matching))
        assertEquals(listOf(step.key, matching.key, orphan.key), actual.map { it.key })
        assertEquals(step.key, (actual[1] as LocalTranscriptItem.WorkArtifact).attachedTo)
        assertNull((actual[2] as LocalTranscriptItem.WorkArtifact).attachedTo)
    }
}
