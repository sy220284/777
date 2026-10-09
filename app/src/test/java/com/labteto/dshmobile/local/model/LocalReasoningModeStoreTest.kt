package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalReasoningControls
import com.labteto.dshmobile.local.presentation.LocalReasoningUiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReasoningModeStoreTest {
    @Test fun newChatDefaultsToFastWithoutChangingWorkOrExplicitSelections() {
        val session = "new-chat-fast-default-20261009"
        val deepSeek = LocalModelProfile("chat-default-ds", "deepseek-flash", "https://api.deepseek.com")
        val mandatory = LocalModelProfile("chat-default-astra", "gpt-6-astra", "https://api.openai.com/v1",
            protocol = LocalModelProtocol.RESPONSES)
        val unsupported = LocalModelProfile("chat-default-custom", "custom", "https://example.com")
        // No stored preference: Chat disables optional thinking; Work still uses provider default.
        assertEquals(LocalReasoningMode.FAST, LocalReasoningModeStore.mode(session, defaultChatFast = true))
        assertEquals(LocalReasoningUiMode.FAST, LocalReasoningControls.mode(session, LocalUsageMode.CHAT))
        assertEquals(LocalReasoningUiMode.DEFAULT, LocalReasoningControls.mode(session, LocalUsageMode.WORK))
        assertEquals("none", LocalReasoningModeStore.effortFor(session, deepSeek, defaultChatFast = true))
        assertNull(LocalReasoningModeStore.effortFor(session, deepSeek))
        assertEquals("low", LocalReasoningModeStore.effortFor(session, mandatory, defaultChatFast = true))
        assertNull(LocalReasoningModeStore.effortFor(session, unsupported, defaultChatFast = true))
        // Manually restoring provider DEFAULT must be distinct from the unset Chat default.
        try {
            LocalReasoningControls.setMode(session, LocalReasoningUiMode.DEFAULT)
            assertEquals(LocalReasoningUiMode.DEFAULT, LocalReasoningControls.mode(session, LocalUsageMode.CHAT))
            assertNull(LocalReasoningModeStore.effortFor(session, deepSeek, defaultChatFast = true))
            LocalReasoningControls.setMode(session, LocalReasoningUiMode.DEEP)
            assertEquals("high", LocalReasoningModeStore.effortFor(session, deepSeek, defaultChatFast = true))
            LocalReasoningControls.setMode(session, LocalReasoningUiMode.FAST)
            assertEquals("none", LocalReasoningModeStore.effortFor(session, deepSeek, defaultChatFast = true))
        } finally {
            LocalReasoningControls.restoreDefault(session)
        }
    }

    @Test fun modernGptModelsExposeNativeMaxEffortForApiKeyAndChatGptPlan() {
        val models = listOf("gpt-5.6", "gpt-5.6-sol", "gpt-6-sol", "gpt-6-luna",
            "gpt-6-astra", "gpt-6.1-sol")
        val mandatory = setOf("gpt-6-astra", "gpt-6.1-sol")
        val session = "modern-gpt-reasoning-policy-test"
        try {
            for (model in models) {
                for (auth in LocalModelAuthKind.entries) {
                    val profile = LocalModelProfile(
                        id = "$model-$auth", model = model, baseUrl = "https://api.openai.com/v1",
                        authKind = auth, protocol = LocalModelProtocol.RESPONSES,
                        credentialRef = if (auth == LocalModelAuthKind.CHATGPT_PLAN) "account-a" else null,
                    )
                    val modes = LocalReasoningControls.availableModes(profile, LocalUsageMode.WORK)
                    assertEquals(LocalReasoningUiMode.DEFAULT, modes.first())
                    assertEquals(LocalReasoningUiMode.MAX, modes.last())
                    assertEquals(model !in mandatory, LocalReasoningUiMode.LOW in modes)
                    LocalReasoningModeStore.setMode(session, LocalReasoningMode.MAX)
                    assertEquals("max", LocalReasoningModeStore.effortFor(session, profile, withTools = true))
                    LocalReasoningModeStore.setMode(session, LocalReasoningMode.DEEP)
                    assertEquals("high", LocalReasoningModeStore.effortFor(session, profile, withTools = true))
                    LocalReasoningModeStore.setMode(session, LocalReasoningMode.FAST)
                    assertEquals(if (model in mandatory) "low" else "none",
                        LocalReasoningModeStore.effortFor(session, profile, withTools = true))
                    LocalReasoningModeStore.setMode(session, LocalReasoningMode.DEFAULT)
                    assertNull(LocalReasoningModeStore.effortFor(session, profile, withTools = true))
                }
            }
        } finally {
            LocalReasoningModeStore.setMode(session, LocalReasoningMode.DEFAULT)
        }
    }

    @Test fun modernGptChatCompletionsToolsOnlyExposeSupportedReasoningRoutes() {
        for (model in listOf("gpt-5.6", "gpt-5.6-sol")) {
            val profile = LocalModelProfile("chat-$model", model, "https://api.openai.com/v1")
            assertTrue(LocalReasoningModeStore.isSupported(profile, withTools = true))
            LocalReasoningModeStore.setMode("modern-chat-tools", LocalReasoningMode.MAX)
            assertEquals("max", LocalReasoningModeStore.effortFor("modern-chat-tools", profile, true))
        }
        for (model in listOf("gpt-6-sol", "gpt-6-luna", "gpt-6-astra", "gpt-6.1-sol")) {
            val profile = LocalModelProfile("chat-$model", model, "https://api.openai.com/v1")
            assertFalse(LocalReasoningModeStore.isSupported(profile, withTools = true))
            assertNull(LocalReasoningModeStore.effortFor("modern-chat-tools", profile, true))
        }
        LocalReasoningModeStore.setMode("modern-chat-tools", LocalReasoningMode.DEFAULT)
    }

    @Test fun allUiModesPreserveTheirDeepSeekRequestEffort() {
        val session = "reasoning-explicit-ui-mapping-test"
        val route = LocalModelProfile("mapping-ds", "deepseek-flash", "https://api.deepseek.com")
        val choices = listOf(
            LocalReasoningUiMode.DEFAULT to null,
            LocalReasoningUiMode.FAST to "none",
            LocalReasoningUiMode.LOW to "low",
            LocalReasoningUiMode.DEEP to "high",
            LocalReasoningUiMode.MAX to "max",
        )
        try {
            for ((mode, effort) in choices) {
                LocalReasoningControls.setMode(session, mode)
                assertEquals(mode, LocalReasoningControls.mode(session))
                assertEquals(effort, LocalReasoningModeStore.effortFor(session, route))
            }
        } finally {
            LocalReasoningControls.restoreDefault(session)
        }
    }

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
        assertEquals("max", LocalReasoningModeStore.effortFor(session, openAi))
        assertEquals(listOf(LocalReasoningUiMode.DEFAULT, LocalReasoningUiMode.FAST, LocalReasoningUiMode.LOW,
            LocalReasoningUiMode.DEEP, LocalReasoningUiMode.MAX),
            LocalReasoningControls.availableModes(route, LocalUsageMode.CHAT))
    }

    @Test fun switchingRoutesProjectsUnsupportedSavedEffortToDefaultWithoutLosingChoice() {
        val deepSeek = LocalModelProfile("projection-ds", "deepseek-flash", "https://api.deepseek.com")
        val openAi = LocalModelProfile("projection-api", "gpt-6-luna", "https://api.openai.com/v1")
        val session = "reasoning-route-projection-test"
        LocalReasoningControls.setMode(session, LocalReasoningUiMode.MAX)
        try {
            for (usageMode in LocalUsageMode.entries) {
                for (route in listOf(deepSeek, openAi, openAi.copy(model = "unknown-model"))) {
                    val modes = LocalReasoningControls.availableModes(route, usageMode)
                    val effective = LocalReasoningControls.effectiveMode(LocalReasoningControls.mode(session), modes)
                    val effort = LocalReasoningModeStore.effortFor(session, route, usageMode == LocalUsageMode.WORK)
                    assertEquals(if (effort == "max") LocalReasoningUiMode.MAX else LocalReasoningUiMode.DEFAULT, effective)
                }
            }
            assertEquals(LocalReasoningUiMode.MAX, LocalReasoningControls.mode(session))
            assertEquals("max", LocalReasoningModeStore.effortFor(session, deepSeek))
        } finally {
            LocalReasoningControls.restoreDefault(session)
        }
    }

    @Test fun savedLowUsesMandatoryModelsBasicPositionAndStillSendsLow() {
        val session = "reasoning-mandatory-low-test"
        val route = LocalModelProfile("basic", "gpt-6-astra", "https://api.openai.com/v1", protocol = LocalModelProtocol.RESPONSES)
        LocalReasoningControls.setMode(session, LocalReasoningUiMode.LOW)
        try {
            for (usageMode in LocalUsageMode.entries) {
                assertEquals(LocalReasoningUiMode.FAST, LocalReasoningControls.effectiveMode(
                    LocalReasoningControls.mode(session), LocalReasoningControls.availableModes(route, usageMode),
                    LocalReasoningControls.requiresBasicReasoning(route, usageMode),
                ))
                assertEquals("low", LocalReasoningModeStore.effortFor(session, route, usageMode == LocalUsageMode.WORK))
            }
        } finally {
            LocalReasoningControls.restoreDefault(session)
        }
    }

    @Test fun everySupportedRouteOffersDefaultAndRestoringItRemovesRequestOverride() {
        val session = "reasoning-restore-default-test"
        val routes = listOf(
            LocalModelProfile("restore-ds", "deepseek-flash", "https://api.deepseek.com"),
            LocalModelProfile("restore-v4", "deepseek-v4-pro", "https://api.deepseek.com"),
            LocalModelProfile("restore-api", "gpt-6-luna", "https://api.openai.com/v1", protocol = LocalModelProtocol.RESPONSES),
        )
        try {
            for (route in routes) {
                for (usageMode in LocalUsageMode.entries) {
                    val modes = LocalReasoningControls.availableModes(route, usageMode)
                    assertEquals(LocalReasoningUiMode.DEFAULT, modes.first())
                    LocalReasoningControls.setMode(session, LocalReasoningUiMode.DEEP)
                    assertEquals("high", LocalReasoningModeStore.effortFor(session, route, usageMode == LocalUsageMode.WORK))
                    LocalReasoningControls.setMode(session, modes.first())
                    assertEquals(LocalReasoningUiMode.DEFAULT, LocalReasoningControls.mode(session))
                    assertNull(LocalReasoningModeStore.effortFor(session, route, usageMode == LocalUsageMode.WORK))
                }
            }
        } finally {
            LocalReasoningControls.restoreDefault(session)
        }
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
