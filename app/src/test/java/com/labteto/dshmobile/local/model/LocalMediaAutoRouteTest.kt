package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("oa", LocalMediaCatalog.selectOffer(offers, LocalMediaKind.IMAGE, "oa", "deleted-model")?.profile?.id)
    }
}
