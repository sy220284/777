package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelRouteCatalogTest {
    private val parent = LocalModelProfile("parent", "deepseek-flash", "https://api.deepseek.com")
    private val api = LocalModelProfile("openai-api", "gpt-test", "https://proxy.example/v1",
        protocol = LocalModelProtocol.RESPONSES)
    private val plan = LocalModelProfile("openai-plan", "gpt-test", "https://api.openai.com/v1",
        authKind = LocalModelAuthKind.CHATGPT_PLAN, protocol = LocalModelProtocol.RESPONSES,
        credentialRef = "account-one")

    @Test fun crossProviderChildKeepsItsOwnAddressProtocolAndCredentialIdentity() {
        val selected = selectRunModelProfile(listOf(parent, api, plan), parent, plan.id)
        assertSame(plan, selected)
        assertEquals("https://api.openai.com/v1", selected.baseUrl)
        assertEquals(LocalModelAuthKind.CHATGPT_PLAN, selected.authKind)
        assertEquals("account-one", selected.credentialRef)
        assertEquals(LocalModelProtocol.RESPONSES, selected.protocol)
        assertSame(parent, selectRunModelProfile(listOf(parent, api, plan), parent, null))
    }

    @Test fun explicitProfileIdDisambiguatesSameModelAndEndpoint() {
        val secondPlan = plan.copy(id = "openai-plan-two", credentialRef = "account-two")
        assertSame(
            plan,
            selectModelRouteProfile(listOf(plan, secondPlan), plan.id, plan.model, plan.baseUrl),
        )
        val error = runCatching {
            selectModelRouteProfile(listOf(plan, secondPlan), null, plan.model, plan.baseUrl)
        }.exceptionOrNull()
        assertEquals("MODEL_PROFILE_ID_REQUIRED", (error as LocalModelException).code)
    }

    @Test fun uniqueLegacyModelNameStillWorksAcrossProviders() {
        assertSame(api, selectRunModelProfile(listOf(parent, api), parent, " gpt-test "))
    }

    @Test fun ambiguousApiAndPlanNamesNeverSilentlyChooseABillingSource() {
        val error = runCatching { selectRunModelProfile(listOf(api, plan), api, "gpt-test") }.exceptionOrNull()
        assertTrue(error is LocalModelException)
        assertEquals("SUBAGENT_MODEL_ROUTE_UNAVAILABLE", (error as LocalModelException).code)
    }

    @Test fun unknownSelectionNeverFallsBackToTheParentCredential() {
        val error = runCatching { selectRunModelProfile(listOf(parent), parent, "missing") }.exceptionOrNull()
        assertTrue(error is LocalModelException)
    }

    @Test fun missingActiveRouteIsRejectedBeforeRunningAChild() {
        val error = runCatching { selectRunModelProfile(emptyList(), null, " ") }.exceptionOrNull()
        assertEquals("NO_MODEL_CREDENTIAL", (error as LocalModelException).code)
    }
}
