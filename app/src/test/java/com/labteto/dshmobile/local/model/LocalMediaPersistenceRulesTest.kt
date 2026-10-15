package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaPersistenceRulesTest {
    @Test fun pendingCreationReceiptSurvivesRestartWithoutRepeatingPaidRequest() {
        val job = LocalMediaCreationJob(
            id = "operation", sessionId = "chat-one", kind = "SPEECH",
            provider = "OPENAI", model = "gpt-4o-mini-tts",
            prompt = "朗读一下", createdAtMillis = 100L, sourceMessageId = "source-1",
        )
        val stored = Json.encodeToString(LocalMediaCreationJobList.serializer(), LocalMediaCreationJobList(listOf(job)))
        val restored = Json.decodeFromString(LocalMediaCreationJobList.serializer(), stored)
        val recovered = LocalMediaCreationJobRecovery.recover(restored.jobs)
        assertEquals(LocalMediaCreationPhase.OUTCOME_UNKNOWN, recovered.single().phase)
        assertEquals("chat-one", recovered.single().sessionId)
        assertEquals(job.id, recovered.single().id)
        assertEquals("source-1", recovered.single().sourceMessageId)
        assertEquals(recovered, LocalMediaCreationJobRecovery.recover(recovered))
        val done = LocalMediaCreationJobRecovery.recover(listOf(job.copy(phase = LocalMediaCreationPhase.SUCCEEDED)))
        assertEquals(LocalMediaCreationPhase.SUCCEEDED, done.single().phase)
    }

    @Test fun mediaResultsKeepSessionAndTranscribedTextAcrossSerialization() {
        val transcript = LocalMediaResult(
            id = "asr", model = "whisper-1", provider = "OPENAI", kind = "TRANSCRIBE",
            createdAtMillis = 500L, textOutput = "语音内容", sessionId = "chat-1",
        )
        val image = LocalMediaResult(
            id = "image", model = "image-01", provider = "MINIMAX", kind = "IMAGE",
            createdAtMillis = 300L, remoteUrl = "https://cdn.example.com/image.png",
            sessionId = "chat-1", description = "山间茶室", sourceMessageId = "reply-5",
        )
        val encoded = Json.encodeToString(LocalMediaResultList.serializer(), LocalMediaResultList(listOf(image, transcript)))
        val restored = Json.decodeFromString(LocalMediaResultList.serializer(), encoded).results
        assertEquals(listOf(image, transcript), restored)
        assertEquals("reply-5", restored.first().sourceMessageId)
        assertTrue(runCatching { transcript.copy(remoteUrl = "https://invalid.example/duplicate") }.isFailure)

        // Old global archive results must never appear in an unrelated conversation.
        val legacy = Json.decodeFromString(LocalMediaResult.serializer(),
            """{"id":"old","model":"image-01","provider":"MINIMAX","kind":"IMAGE","createdAtMillis":1,"remoteUrl":"https://cdn.example.com/old.png"}""")
        assertNull(legacy.sessionId)
    }

    @Test fun savedCreatorAccountParametersAndRevisionLineageSurviveProcessRestore() {
        val previous = LocalMediaResult(
            id = "first", provider = "OPENAI", model = "gpt-image-2.5-flare",
            kind = "IMAGE", createdAtMillis = 100L, fileName = "first.png",
            description = "保留完整的原图提示词", sessionId = "session-1",
            sourceMessageId = "message-1", profileId = "openai-account-2",
            routeFingerprint = "frozen-route", imageRatio = "9:16",
        )
        val later = previous.copy(
            id = "second", fileName = "second.png",
            revisionOf = "first", description = "重新出图的修改版提示词",
        )
        val serialized = Json.encodeToString(
            LocalMediaResultList.serializer(), LocalMediaResultList(listOf(previous, later)),
        )
        val restored = Json.decodeFromString(LocalMediaResultList.serializer(), serialized).results
        assertEquals(listOf(previous, later), restored)
        assertEquals(previous.id, restored.last().revisionOf)
        assertEquals("9:16", LocalMediaRevisionDraft.from(restored.first())?.ratio)
        assertEquals("openai-account-2", restored.first().profileId)
        assertEquals("first.png", restored.first().fileName)
        assertEquals("second.png", restored.last().fileName)

        val voice = LocalMediaResult(
            id = "voice-2", provider = "OPENAI", model = "gpt-4o-mini-tts",
            kind = "SPEECH", createdAtMillis = 300L, fileName = "voice.mp3",
            profileId = "openai-account-2", routeFingerprint = "frozen-route",
            voiceId = "alloy", revisionOf = "voice-1",
        )
        val decodedVoice = Json.decodeFromString(
            LocalMediaResult.serializer(), Json.encodeToString(LocalMediaResult.serializer(), voice),
        )
        assertEquals("alloy", LocalMediaRevisionDraft.from(decodedVoice)?.voiceId)
        assertEquals("voice-1", decodedVoice.revisionOf)

        // Older records remain readable but cannot silently acquire an original account.
        val legacy = Json.decodeFromString(LocalMediaResult.serializer(),
            """{"id":"legacy","model":"image-01","provider":"MINIMAX","kind":"IMAGE","createdAtMillis":1,"remoteUrl":"https://cdn.example.com/x.png"}"""
        )
        assertNull(legacy.profileId)
        assertNull(legacy.routeFingerprint)
        assertNull(legacy.revisionOf)
    }

    @Test fun videoTasksRetainOriginalSessionWhenPollingAndRestoring() {
        val pending = job(LocalMediaVideoPhase.SUBMITTING).copy(
            sessionId = "chat-1", description = "视频提示词", sourceMessageId = "reply-6",
        )
        val recorded = Json.decodeFromString(LocalMediaVideoTask.serializer(),
            Json.encodeToString(LocalMediaVideoTask.serializer(), pending))
        val queued = LocalMediaVideoTransitions.accepted(recorded, "task-id", 200L)
        val finished = LocalMediaVideoTransitions.polled(
            queued, LocalMediaVideoPhase.SUCCEEDED, "https://cdn.example.com/movie.mp4", 300L
        )
        assertEquals("chat-1", finished.sessionId)
        assertEquals("视频提示词", finished.description)
        assertEquals("reply-6", finished.sourceMessageId)
        assertEquals(pending.id, finished.id)
    }

    private fun job(phase: LocalMediaVideoPhase, id: String? = null) = LocalMediaVideoTask(
        id = "local-1",
        offerId = "profile-a:MINIMAX:VIDEO:MiniMax-Hailuo-2.3",
        routeFingerprint = "frozen-route-fingerprint",
        provider = "MINIMAX",
        model = "MiniMax-Hailuo-2.3",
        profileId = "profile-a",
        startedAtMillis = 100L,
        updatedAtMillis = 100L,
        phase = phase,
        providerTaskId = id,
    )

    @Test fun crashAfterSubmissionStartsDoesNotRecreateChargeableJob() {
        val before = listOf(job(LocalMediaVideoPhase.SUBMITTING), job(LocalMediaVideoPhase.QUEUED, "remote-2"))
        val serialized = Json.encodeToString(LocalMediaVideoTaskList.serializer(), LocalMediaVideoTaskList(before))
        val decoded = Json.decodeFromString(LocalMediaVideoTaskList.serializer(), serialized)
        val restored = LocalMediaVideoTransitions.recover(decoded.tasks)
        assertEquals(LocalMediaVideoPhase.OUTCOME_UNKNOWN, restored[0].phase)
        assertNull(restored[0].providerTaskId)
        assertTrue(restored[0].lastError.orEmpty().contains("勿直接重复生成"))
        assertEquals(LocalMediaVideoPhase.QUEUED, restored[1].phase)
        assertEquals("remote-2", restored[1].providerTaskId)
        assertEquals(restored, LocalMediaVideoTransitions.recover(restored))
    }

    @Test fun providerIdCanOnlyAttachToOriginalPendingJob() {
        val accepted = LocalMediaVideoTransitions.accepted(
            job(LocalMediaVideoPhase.SUBMITTING), "remote-123", 200L
        )
        assertEquals(LocalMediaVideoPhase.QUEUED, accepted.phase)
        assertEquals("remote-123", accepted.providerTaskId)
        assertEquals(200L, accepted.updatedAtMillis)
        assertTrue(runCatching {
            LocalMediaVideoTransitions.accepted(accepted, "duplicate", 300L)
        }.isFailure)
        assertTrue(runCatching {
            LocalMediaVideoTransitions.accepted(job(LocalMediaVideoPhase.OUTCOME_UNKNOWN), "replayed", 300L)
        }.isFailure)
    }

    @Test fun completedJobsCannotBeOverwrittenByStalePolls() {
        val queued = job(LocalMediaVideoPhase.QUEUED, "remote-123")
        val running = LocalMediaVideoTransitions.polled(queued, LocalMediaVideoPhase.PROCESSING, null, 200L)
        val done = LocalMediaVideoTransitions.polled(
            running, LocalMediaVideoPhase.SUCCEEDED, "https://files.example.com/movie.mp4", 300L
        )
        assertEquals(LocalMediaVideoPhase.SUCCEEDED, done.phase)
        assertEquals("https://files.example.com/movie.mp4", done.downloadUrl)
        assertEquals(done, LocalMediaVideoTransitions.polled(done, LocalMediaVideoPhase.PROCESSING, null, 400L))
        assertTrue(runCatching {
            LocalMediaVideoTransitions.polled(queued, LocalMediaVideoPhase.SUCCEEDED, null, 500L)
        }.isFailure)
        assertTrue(runCatching {
            LocalMediaVideoTransitions.polled(queued, LocalMediaVideoPhase.SUCCEEDED, "http://unsafe.test", 500L)
        }.isFailure)
    }

    @Test fun accountRouteChangeDoesNotGrantReadAccessToOldVideoTask() {
        val existing = job(LocalMediaVideoPhase.QUEUED, "remote-123")
        val profile = LocalModelProfile(
            id = "profile-a", model = "MiniMax-M2.7", baseUrl = "https://api.minimaxi.com/v1"
        )
        val offer = LocalMediaCatalog.offers(listOf(profile)).first { it.kind == LocalMediaKind.VIDEO }
        assertFalse(existing.belongsTo(offer))
    }

    @Test fun generatedMediaIndexKeepsFileAndTemporaryLinkDistinct() {
        val file = LocalMediaResult(
            id = "f1", model = "speech-2.8-hd", provider = "MINIMAX",
            kind = "SPEECH", createdAtMillis = 100L, fileName = "voice-123.mp3"
        )
        val link = LocalMediaResult(
            id = "u1", model = "image-01", provider = "MINIMAX",
            kind = "IMAGE", createdAtMillis = 200L, remoteUrl = "https://cdn.example.com/temporary.png"
        )
        val copy = Json.decodeFromString(LocalMediaResultList.serializer(),
            Json.encodeToString(LocalMediaResultList.serializer(), LocalMediaResultList(listOf(file, link)))
        )
        assertEquals(2, copy.results.size)
        assertNull(copy.results[0].remoteUrl)
        assertNull(copy.results[1].fileName)
        assertTrue(runCatching { link.copy(fileName = "fake.png") }.isFailure)
    }
}
