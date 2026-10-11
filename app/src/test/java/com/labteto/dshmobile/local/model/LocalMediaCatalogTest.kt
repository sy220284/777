package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaCatalogTest {
    private fun profile(
        model: String,
        endpoint: String,
        id: String = model,
        auth: LocalModelAuthKind = LocalModelAuthKind.API_KEY,
    ) = LocalModelProfile(id = id, model = model, baseUrl = endpoint, authKind = auth)

    @Test fun creativeCapabilitiesDoNotDependOnActiveChatModelModalities() {
        val textOnly = profile("MiniMax-M2.7", "https://api.minimaxi.com/v1")
        assertFalse(LocalModelCapability.VIDEO in LocalModelPresets.clientCapabilitiesFor(textOnly.model, textOnly.baseUrl))
        val options = LocalMediaCatalog.offers(listOf(textOnly))
        assertEquals(
            setOf(LocalMediaKind.IMAGE, LocalMediaKind.VIDEO, LocalMediaKind.SPEECH, LocalMediaKind.TRANSCRIBE),
            options.map { it.kind }.toSet(),
        )
        assertEquals("image-01", options.first { it.kind == LocalMediaKind.IMAGE }.model)
    }

    @Test fun multipleProvidersExposeOnlyTheirImplementedOperations() {
        val qwen = profile("qwen3.8-max", "https://dashscope.aliyuncs.com/compatible-mode/v1")
        val openAI = profile("gpt-6", "https://api.openai.com/v1")
        val gemini = profile("gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1beta/openai")
        val all = LocalMediaCatalog.offers(listOf(qwen, openAI, gemini))
        assertEquals(setOf(LocalMediaKind.IMAGE), all.filter { it.provider == LocalMediaProvider.QWEN }.map { it.kind }.toSet())
        assertEquals(setOf(LocalMediaKind.IMAGE), all.filter { it.provider == LocalMediaProvider.GEMINI }.map { it.kind }.toSet())
        assertEquals(setOf(LocalMediaKind.IMAGE, LocalMediaKind.SPEECH, LocalMediaKind.TRANSCRIBE),
            all.filter { it.provider == LocalMediaProvider.OPENAI }.map { it.kind }.toSet())
        assertFalse(all.any { it.provider != LocalMediaProvider.MINIMAX && it.kind == LocalMediaKind.VIDEO })
        assertTrue(all.any { it.model == "gpt-image-2.5-flare" })
        assertTrue(all.any { it.model == "qwen-image-3.0" })
        assertTrue(all.any { it.model == "gemini-3.1-flash-image" })
    }

    @Test fun avoidTokenPlansCustomHostsAndImplicitProxyCapabilities() {
        val oauth = profile("gpt-6", "https://api.openai.com/v1", auth = LocalModelAuthKind.CHATGPT_PLAN)
        val proxy = profile("gpt-6", "https://openai-proxy.example/v1")
        val insecure = profile("gpt-6", "http://api.openai.com/v1")
        val alternatePort = profile("gpt-6", "https://api.openai.com:8443/v1")
        assertTrue(LocalMediaCatalog.offers(listOf(oauth, proxy, insecure, alternatePort)).isEmpty())
        val forgery = LocalMediaOffer(proxy, LocalMediaProvider.OPENAI, LocalMediaKind.IMAGE, "gpt-image-2.5-flare", "GPT Image")
        assertTrue(runCatching { LocalMediaCatalog.requireValid(forgery) }.isFailure)
    }

    @Test fun accountIdentityAndModelIdArePartOfTheCreativeRoute() {
        val first = profile("gpt-6", "https://api.openai.com/v1", id = "account-a")
        val second = profile("gpt-6", "https://api.openai.com/v1", id = "account-b")
        val offerings = LocalMediaCatalog.offers(listOf(first, second))
        assertEquals(10, offerings.size)
        assertEquals(offerings.size, offerings.map { it.id }.distinct().size)
        assertTrue(offerings.all { runCatching { LocalMediaCatalog.requireValid(it) }.isSuccess })
        assertTrue(runCatching {
            LocalMediaCatalog.requireValid(offerings.first().copy(model = "not-an-official-model"))
        }.isFailure)
    }

    @Test fun qwenWorkspaceDomainsResolveWithoutGuessingCustomProxy() {
        val workspace = profile("qwen3.8-max", "https://12345.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
        assertEquals(LocalMediaProvider.QWEN, LocalMediaCatalog.providerFor(workspace))
        assertEquals(null, LocalMediaCatalog.providerFor(profile("custom", "https://maas.aliyuncs.com/v1")))
    }
}
