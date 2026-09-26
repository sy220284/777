package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentModelProtocol
import com.labteto.dshmobile.harness.agent.AgentModelRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelRouteTest {
    @Test
    fun officialDeepSeekUsesAnthropicMessagesProtocol() {
        val route = resolveLocalModelRoute("https://api.deepseek.com", "deepseek-flash")

        assertEquals("deepseek", route.provider)
        assertEquals(AgentModelProtocol.ANTHROPIC_MESSAGES, route.protocol)
        assertEquals("deepseek-flash", route.model)
    }

    @Test
    fun compatibleEndpointKeepsOpenAiChatProtocol() {
        val route = resolveLocalModelRoute("https://example.com/v1", "custom-model")

        assertEquals("openai-compatible", route.provider)
        assertEquals(AgentModelProtocol.OPENAI_CHAT, route.protocol)
    }

    @Test
    fun persistedSessionRouteWinsOverGlobalFallback() {
        val persisted = AgentModelRoute(
            provider = "deepseek",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-v4-pro",
            protocol = AgentModelProtocol.ANTHROPIC_MESSAGES,
        )
        val route = resolveSessionModelRoute(
            LocalHarnessSession(id = "s", modelRoute = persisted),
            fallbackBaseUrl = "https://example.com/v1",
            fallbackModel = "other",
        )

        assertEquals(persisted, route)
    }
}
