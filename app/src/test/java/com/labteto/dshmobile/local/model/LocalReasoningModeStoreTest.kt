package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReasoningModeStoreTest {
    @Test fun deepSeekFlashHasNativeSwitch() {
        val route = LocalModelProfile("ds", "deepseek-flash", "https://api.deepseek.com")
        assertTrue(LocalReasoningModeStore.isSupported(route))
        val session = "reasoning-switch-test"
        LocalReasoningModeStore.setEnabled(session, false)
        assertEquals("none", LocalReasoningModeStore.effortFor(session, route))
        LocalReasoningModeStore.setEnabled(session, true)
        assertEquals("high", LocalReasoningModeStore.effortFor(session, route))
    }

    @Test fun chatCompletionWithToolsIsNotExposedAsHighReasoning() {
        val sol = LocalModelProfile("sol", "gpt-6-sol", "https://api.openai.com/v1")
        assertTrue(LocalReasoningModeStore.isSupported(sol))
        assertFalse(LocalReasoningModeStore.isSupported(sol, withTools = true))
        assertNull(LocalReasoningModeStore.effortFor("work-turn", sol, withTools = true))
        val responses = sol.copy(protocol = LocalModelProtocol.RESPONSES)
        assertTrue(LocalReasoningModeStore.isSupported(responses, withTools = true))
        assertEquals("high", LocalReasoningModeStore.effortFor("work-turn", responses, withTools = true))
    }

    @Test fun explicitReasoningCapabilityDoesNotClaimMandatoryReasoningModels() {
        val mandatory = LocalModelProfile("next", "gpt-6.1-sol", "https://api.openai.com/v1")
        assertFalse(LocalReasoningModeStore.isSupported(mandatory))
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
