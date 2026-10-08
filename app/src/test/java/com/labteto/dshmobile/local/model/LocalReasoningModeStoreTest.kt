package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReasoningModeStoreTest {
    @Test fun absentPreferencePreservesProviderDefaultAndMandatoryModelsUseLow() {
        val route = LocalModelProfile("astra", "gpt-6-astra", "https://api.openai.com/v1",
            protocol = LocalModelProtocol.RESPONSES)
        val session = "astra-mode-test"
        LocalReasoningModeStore.setMode(session, LocalReasoningMode.DEFAULT)
        assertNull(LocalReasoningModeStore.effortFor(session, route))
        LocalReasoningModeStore.setMode(session, LocalReasoningMode.FAST)
        assertEquals("low", LocalReasoningModeStore.effortFor(session, route))
        LocalReasoningModeStore.setMode(session, LocalReasoningMode.DEEP)
        assertEquals("high", LocalReasoningModeStore.effortFor(session, route))
        assertFalse(LocalReasoningModeStore.isSupported(route.copy(protocol = LocalModelProtocol.CHAT_COMPLETIONS), true))
    }

    @Test fun knownAccountBoundPlanModelsUseResponsesReasoningContract() {
        val route = LocalModelProfile("plan-astra", "gpt-6.1-sol", "https://api.openai.com/v1",
            authKind = LocalModelAuthKind.CHATGPT_PLAN, protocol = LocalModelProtocol.RESPONSES,
            credentialRef = "account-a")
        assertTrue(LocalReasoningModeStore.isSupported(route, true))
        LocalReasoningModeStore.setMode("plan-reasoning-test", LocalReasoningMode.FAST)
        assertEquals("low", LocalReasoningModeStore.effortFor("plan-reasoning-test", route, true))
        assertFalse(LocalReasoningModeStore.isSupported(route.copy(model = "unknown-model")))
        assertFalse(LocalReasoningModeStore.isSupported(route.copy(credentialRef = null)))
    }

    @Test fun deepSeekFlashHasNativeSwitch() {
        val route = LocalModelProfile("ds", "deepseek-flash", "https://api.deepseek.com")
        assertTrue(LocalReasoningModeStore.isSupported(route))
        val session = "reasoning-switch-test"
        LocalReasoningModeStore.setEnabled(session, false)
        assertEquals("none", LocalReasoningModeStore.effortFor(session, route))
        LocalReasoningModeStore.setEnabled(session, true)
        assertEquals("high", LocalReasoningModeStore.effortFor(session, route))
        LocalReasoningModeStore.setMode(session, LocalReasoningMode.LOW)
        assertEquals("low", LocalReasoningModeStore.effortFor(session, route))
        LocalReasoningModeStore.setMode(session, LocalReasoningMode.MAX)
        assertEquals("max", LocalReasoningModeStore.effortFor(session, route))
        val openAi = LocalModelProfile("api", "gpt-6-luna", "https://api.openai.com/v1")
        assertNull("unsupported effort must preserve the new route default",
            LocalReasoningModeStore.effortFor(session, openAi))
        assertEquals(listOf(com.labteto.dshmobile.local.presentation.LocalReasoningUiMode.FAST, com.labteto.dshmobile.local.presentation.LocalReasoningUiMode.LOW,
            com.labteto.dshmobile.local.presentation.LocalReasoningUiMode.DEEP, com.labteto.dshmobile.local.presentation.LocalReasoningUiMode.MAX),
            com.labteto.dshmobile.local.presentation.LocalReasoningControls.availableModes(route, com.labteto.dshmobile.local.LocalUsageMode.CHAT))
    }

    @Test fun chatCompletionWithToolsIsNotExposedAsHighReasoning() {
        val sol = LocalModelProfile("sol", "gpt-6-sol", "https://api.openai.com/v1")
        assertTrue(LocalReasoningModeStore.isSupported(sol))
        assertFalse(LocalReasoningModeStore.isSupported(sol, withTools = true))
        assertNull(LocalReasoningModeStore.effortFor("work-turn", sol, withTools = true))
        val responses = sol.copy(protocol = LocalModelProtocol.RESPONSES)
        assertTrue(LocalReasoningModeStore.isSupported(responses, withTools = true))
        assertNull(LocalReasoningModeStore.effortFor("work-turn", responses, withTools = true))
    }

    @Test fun explicitReasoningCapabilityDoesNotClaimMandatoryReasoningModels() {
        val mandatory = LocalModelProfile("next", "gpt-6.1-sol", "https://api.openai.com/v1")
        assertTrue(LocalReasoningModeStore.isSupported(mandatory))
        assertNull(LocalReasoningModeStore.effortFor("mandatory", mandatory))
        val custom = LocalModelProfile("local", "gpt-6-sol", "https://localhost:1234/v1")
        assertFalse(LocalReasoningModeStore.isSupported(custom))
    }

    @Test fun unsupportedAndPlanRoutesAreNeverSentUnsafeControls() {
        val unknown = LocalModelProfile("unknown", "custom-model", "https://example.com")
        assertFalse(LocalReasoningModeStore.isSupported(unknown))
        assertNull(LocalReasoningModeStore.effortFor("other", unknown))
        val plan = LocalModelProfile("plan", "gpt-5.6-luna", "https://api.openai.com/v1",
            authKind = LocalModelAuthKind.CHATGPT_PLAN, protocol = LocalModelProtocol.RESPONSES)
        assertFalse(LocalReasoningModeStore.isSupported(plan))
    }
}
