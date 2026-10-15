package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaAutoRouteTest {
    private fun p(id: String, model: String, host: String) =
        LocalModelProfile(id = id, model = model, baseUrl = host, authKind = LocalModelAuthKind.API_KEY)

    @Test fun prefersCurrentSupportedAccountAndPreservesExplicitChoice() {
        val openai = p("oa", "gpt-6", "https://api.openai.com/v1")
        val mini = p("mm", "MiniMax-M2.7", "https://api.minimaxi.com/v1")
        val offers = LocalMediaCatalog.offers(listOf(openai, mini))
        val auto = LocalMediaCatalog.selectOffer(offers, LocalMediaKind.VIDEO, "oa")
        assertEquals("mm", auto?.profile?.id)
        val video = offers.first { it.kind == LocalMediaKind.VIDEO }
        assertEquals(video.id, LocalMediaCatalog.selectOffer(offers, LocalMediaKind.VIDEO, "oa", video.id)?.id)
        assertEquals("oa", LocalMediaCatalog.selectOffer(offers, LocalMediaKind.SPEECH, "oa")?.profile?.id)
    }

    @Test fun missingCapabilityDoesNotFabricateProviderOrRoute() {
        val openai = p("oa", "gpt-6", "https://api.openai.com/v1")
        val offers = LocalMediaCatalog.offers(listOf(openai))
        assertNull(LocalMediaCatalog.selectOffer(offers, LocalMediaKind.VIDEO, "oa"))
        assertNull(LocalMediaCatalog.selectOffer(offers, LocalMediaKind.IMAGE, "oa", "deleted-model"))
        assertEquals("oa", LocalMediaCatalog.selectOffer(offers, LocalMediaKind.IMAGE, "oa")?.profile?.id)
    }

    @Test fun historyRecreationPreservesExactAccountAndCreatorParameters() {
        val first = p("oa1", "gpt-6", "https://api.openai.com/v1")
        val second = p("oa2", "gpt-6", "https://api.openai.com/v1")
        val offers = LocalMediaCatalog.offers(listOf(first, second))
        val chosen = offers.first { it.kind == LocalMediaKind.IMAGE && it.profile.id == "oa2" }
        val result = LocalMediaResult(
            id = "previous-image", model = chosen.model, provider = chosen.provider.name,
            kind = LocalMediaKind.IMAGE.name, createdAtMillis = 100L,
            fileName = "saved.png", sessionId = "session-a", description = "山中的小屋",
            sourceMessageId = "reply-2", profileId = chosen.profile.id,
            routeFingerprint = chosen.profile.routeFingerprint(), imageRatio = "9:16",
        )
        val draft = LocalMediaRevisionDraft.from(result)!!
        assertEquals(chosen.id, draft.originalOffer(offers)?.id)
        assertEquals("山中的小屋", draft.prompt)
        assertEquals("9:16", draft.ratio)
        assertEquals("reply-2", draft.sourceMessageId)
        assertNull(draft.originalOffer(offers.filter { it.profile.id != "oa2" }))
        assertNull(draft.originalOffer(emptyList()))
        // Re-keyed accounts must not silently inherit the old paid-creation route.
        assertNull(draft.copy(routeFingerprint = "old-credential").originalOffer(offers))
        val old = LocalMediaResult(
            id = "old", model = chosen.model, provider = chosen.provider.name,
            kind = "IMAGE", createdAtMillis = 101L,
            remoteUrl = "https://cdn.example.org/old.png", description = "旧图",
        )
        assertNull(LocalMediaRevisionDraft.from(old)!!.originalOffer(offers))
    }

    @Test fun revisionsLinkBackWithoutOverwritingOriginalAssetsOrProviderTasks() {
        val profile = p("mm", "MiniMax-M2.7", "https://api.minimaxi.com/v1")
        val offers = LocalMediaCatalog.offers(listOf(profile))
        val speech = offers.first { it.kind == LocalMediaKind.SPEECH }
        val result = LocalMediaResult(
            id = "voice-one", model = speech.model, provider = speech.provider.name,
            kind = "SPEECH", createdAtMillis = 100L, fileName = "voice-one.mp3",
            description = "原台词", sourceMessageId = "chat-reply",
            profileId = speech.profile.id, routeFingerprint = speech.profile.routeFingerprint(),
            voiceId = "male-qn-qingse",
        )
        val draft = LocalMediaRevisionDraft.from(result)!!
        assertEquals("male-qn-qingse", draft.voiceId)
        assertEquals(speech.id, draft.originalOffer(offers)?.id)
        val replacement = result.copy(
            id = "voice-two", fileName = "voice-two.mp3", description = "修改后的台词",
            revisionOf = result.id,
        )
        assertEquals("voice-one", replacement.revisionOf)
        assertEquals("voice-one.mp3", result.fileName)
        assertEquals("voice-two.mp3", replacement.fileName)
        assertTrue(replacement.description != result.description)
        val video = offers.first { it.kind == LocalMediaKind.VIDEO }
        val task = LocalMediaVideoTask(
            id = "video-one", offerId = video.id,
            routeFingerprint = video.profile.routeFingerprint(),
            provider = video.provider.name, model = video.model, profileId = video.profile.id,
            startedAtMillis = 100L, updatedAtMillis = 100L,
            phase = LocalMediaVideoPhase.SUCCEEDED, providerTaskId = "upstream-task-id",
            description = "旧分镜", sessionId = "session-a",
        )
        val videoDraft = LocalMediaRevisionDraft.from(task)
        assertEquals(video.id, videoDraft.originalOffer(offers)?.id)
        assertEquals("旧分镜", videoDraft.prompt)
        assertEquals("video-one", videoDraft.originId)
    }

}
