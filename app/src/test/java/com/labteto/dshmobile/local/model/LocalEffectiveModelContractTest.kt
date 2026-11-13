package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.documentedContextWindowTokens
import com.labteto.dshmobile.local.operationalInputLimitTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEffectiveModelContractTest {
    private fun profile(preset: LocalModelPreset) = LocalModelProfile(
        id = preset.provider + ":" + preset.model,
        model = preset.model,
        baseUrl = preset.baseUrl,
        provider = preset.provider,
        protocol = preset.protocol,
        credentialRef = "account-a",
    )

    @Test
    fun allExistingPresetsPreserveProtocolRuntimeCacheSamplingBudgetAndContext() {
        LocalModelPresets.entries.forEach { preset ->
            val route = profile(preset)
            val contract = LocalEffectiveModelContracts.forProfile(route, withTools = true)
            assertEquals(route.model, contract.model)
            assertEquals(route.baseUrl, contract.baseUrl)
            assertEquals(route.routeFingerprint(), contract.routeFingerprint)
            assertEquals(preset.protocol, contract.protocol)
            assertEquals(
                LocalModelPresets.runtimeCapabilitiesFor(
                    preset.model, preset.baseUrl, preset.protocol, LocalModelAuthKind.API_KEY,
                ),
                contract.runtime,
            )
            assertEquals(contract.runtime.promptCachePolicy, contract.cache)
            assertEquals(
                LocalModelPresets.chatTemperatureRangeFor(route.model, route.baseUrl),
                contract.temperature,
            )
            assertEquals(
                LocalReasoningRequestPolicy.resolve(route, withTools = true),
                contract.reasoning,
            )
            assertEquals(
                LocalModelBudgetPolicies.forRoute(route.model, route.baseUrl),
                contract.executionBudget,
            )
            assertEquals(
                operationalInputLimitTokens(route.model, route.baseUrl),
                contract.operationalInputLimitTokens,
            )
            assertEquals(
                documentedContextWindowTokens(route.model, route.baseUrl),
                contract.documentedContextWindowTokens,
            )
            assertEquals(
                LocalModelPresets.clientCapabilitiesFor(route.model, route.baseUrl),
                contract.clientModalities,
            )
            assertEquals(
                LocalModelPresets.capabilitiesFor(route.model, route.baseUrl),
                contract.documentedModalities,
            )
        }
    }

    @Test
    fun miniMaxOfficialBudgetNeverLeaksToCustomProxyOrOtherProviders() {
        val miniMax = LocalEffectiveModelContracts.forProfile(
            LocalModelProfile("mm", "MiniMax-M3", "https://api.minimax.io/v1"),
        )
        val proxy = LocalEffectiveModelContracts.forProfile(
            LocalModelProfile("proxy", "MiniMax-M3", "https://proxy.example/v1"),
        )
        val deepSeek = LocalEffectiveModelContracts.forProfile(
            LocalModelProfile("ds", "deepseek-flash", "https://api.deepseek.com"),
        )
        assertEquals(256, miniMax.executionBudget.workRequestsPerSlice)
        assertEquals(128, proxy.executionBudget.workRequestsPerSlice)
        assertEquals(128, deepSeek.executionBudget.workRequestsPerSlice)
        assertTrue(miniMax.operationalInputLimitTokens > proxy.operationalInputLimitTokens)
        assertEquals(LocalPromptCacheMode.NONE, proxy.cache.mode)
        assertEquals(LocalPromptCacheMode.PREFIX_AUTO, deepSeek.cache.mode)
        assertNull(proxy.reasoning)
    }

    @Test
    fun chatGptPlanAndApiKeyKeepDistinctProtocolAndReasoningFacts() {
        val plan = LocalModelProfile(
            id = "plan", model = "gpt-5.6", baseUrl = "https://api.openai.com/v1",
            authKind = LocalModelAuthKind.CHATGPT_PLAN,
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
            credentialRef = "oauth-account",
        )
        val api = plan.copy(id = "api", authKind = LocalModelAuthKind.API_KEY)
        val planContract = LocalEffectiveModelContracts.forProfile(plan)
        val apiContract = LocalEffectiveModelContracts.forProfile(api)
        assertEquals(LocalModelProtocol.RESPONSES, planContract.protocol)
        assertEquals(LocalModelProtocol.CHAT_COMPLETIONS, apiContract.protocol)
        assertNotEquals(planContract.routeFingerprint, apiContract.routeFingerprint)
        assertTrue(planContract.runtime.promptCacheDiagnostics)
        assertFalse(apiContract.runtime.promptCacheDiagnostics)
        assertEquals(LocalEffectiveCapabilityStatus.UNKNOWN, planContract.toolCalling.status)
        assertEquals("subscription-sharing-tools-require-account-verification", planContract.toolCalling.source)
        // Protocol schema support is distinct from the actual subscription's authorization.
        assertTrue(planContract.runtime.toolCalling)
        assertEquals(
            LocalReasoningRequestPolicy.resolve(plan),
            planContract.reasoning,
        )
    }

    @Test
    fun unknownCapabilitiesAreReportedUnknownWithoutInventingProviderSupport() {
        val unknown = LocalEffectiveModelContracts.forProfile(
            LocalModelProfile("custom", "custom-v2", "https://proxy.example/v1"),
        )
        assertEquals(LocalEffectiveCapabilityStatus.UNKNOWN, unknown.imageInput.status)
        assertEquals(LocalEffectiveCapabilityStatus.UNKNOWN, unknown.toolCalling.status)
        assertTrue(unknown.runtime.toolCalling) // Existing optimistic request behavior is preserved.
        assertNull(unknown.documentedContextWindowTokens)
        assertEquals(192_000, unknown.operationalInputLimitTokens)
        assertNull(unknown.temperature)
        assertEquals(128, unknown.executionBudget.workRequestsPerSlice)
    }

    @Test
    fun contextOverrideAndAccountIdentityAreRequestScoped() {
        val original = LocalModelProfile(
            id = "shared", model = "custom-model", baseUrl = "https://proxy.example/v1",
            credentialRef = "key-a", contextWindowTokensOverride = 128_000,
        )
        val first = LocalEffectiveModelContracts.forProfile(original)
        val second = LocalEffectiveModelContracts.forProfile(original.copy(credentialRef = "key-b"))
        assertEquals(128_000, first.documentedContextWindowTokens)
        assertNotEquals(first.routeFingerprint, second.routeFingerprint)
        assertEquals(first.operationalInputLimitTokens, second.operationalInputLimitTokens)
        assertEquals(original, original.copy())
    }
    @Test
    fun frozenRunSurfaceMatchesContractAndExposesSeparateCreativeOffers() {
        val miniMax = LocalModelProfile(
            id = "minimax-account",
            model = "MiniMax-M2.7",
            baseUrl = "https://api.minimaxi.com/v1",
            credentialRef = "account-a",
        )
        val run = miniMax.toRunModelSurface()
        val contract = LocalEffectiveModelContracts.forProfile(miniMax)
        assertEquals(contract, run.contract)
        assertEquals(contract.runtime, run.capabilities)
        assertEquals(contract.operationalInputLimitTokens, run.contract.operationalInputLimitTokens)
        assertEquals(
            setOf(LocalMediaKind.IMAGE, LocalMediaKind.VIDEO, LocalMediaKind.SPEECH, LocalMediaKind.TRANSCRIBE),
            contract.mediaOffers.map { it.kind }.toSet(),
        )
        assertFalse(LocalModelCapability.VIDEO in contract.clientModalities)
        val proxy = contract.copy(mediaOffers = emptyList())
        assertTrue(proxy.mediaOffers.isEmpty())
        val otherAccount = LocalEffectiveModelContracts.forProfile(
            miniMax.copy(id = "other", credentialRef = "account-b"),
        )
        assertNotEquals(contract.routeFingerprint, otherAccount.routeFingerprint)
    }

    @Test
    fun creativeServiceContractDoesNotAssumePlanOrProxyAuthorization() {
        val plan = LocalModelProfile(
            id = "oauth", model = "gpt-5.6", baseUrl = "https://api.openai.com/v1",
            authKind = LocalModelAuthKind.CHATGPT_PLAN, credentialRef = "plan-account",
        )
        assertTrue(LocalEffectiveModelContracts.forProfile(plan).mediaOffers.isEmpty())
        val proxy = plan.copy(authKind = LocalModelAuthKind.API_KEY, baseUrl = "https://proxy.example/v1")
        assertTrue(LocalEffectiveModelContracts.forProfile(proxy).mediaOffers.isEmpty())
        val officialKey = plan.copy(authKind = LocalModelAuthKind.API_KEY, credentialRef = "api-account")
        assertTrue(LocalEffectiveModelContracts.forProfile(officialKey).mediaOffers.isNotEmpty())
    }


}
