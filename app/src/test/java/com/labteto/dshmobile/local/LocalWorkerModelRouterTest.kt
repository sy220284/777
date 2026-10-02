package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelSelectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalWorkerModelRouterTest {
    private val primaryPlan = LocalModelProfile(
        id = "plan",
        model = "gpt-5.6",
        baseUrl = "https://api.openai.com/v1",
        provider = "OpenAI",
        authKind = LocalModelAuthKind.CHATGPT_PLAN,
        protocol = LocalModelProtocol.RESPONSES,
    )
    private val worker = LocalModelProfile(
        id = "worker",
        model = "deepseek-chat",
        baseUrl = "https://api.deepseek.com",
        provider = "DeepSeek",
    )

    @Test
    fun explicitSelectionWinsPersistedWorker() {
        val state = state(listOf(primaryPlan, worker), worker.id)
        assertEquals("manual", LocalWorkerModelRouter.resolve(" manual ", state))
    }

    @Test
    fun persistedWorkerWinsAutomaticFallback() {
        val state = state(listOf(primaryPlan, worker), worker.id)
        assertEquals(worker.id, LocalWorkerModelRouter.resolve(null, state))
    }

    @Test
    fun singleApiKeyWorkerIsSafeFallbackForPlanParent() {
        val state = state(listOf(primaryPlan, worker), null)
        assertEquals(worker.id, LocalWorkerModelRouter.resolve(null, state))
    }

    @Test
    fun ambiguousApiKeyWorkersDoNotGuessBillingIdentity() {
        val second = worker.copy(id = "worker-2", model = "deepseek-reasoner")
        val state = state(listOf(primaryPlan, worker, second), null)
        assertNull(LocalWorkerModelRouter.resolve(null, state))
    }

    @Test
    fun apiKeyParentWithoutWorkerInheritsParentRoute() {
        val state = LocalHarnessState(
            modelSelection = LocalModelSelectionState(
                profiles = listOf(worker),
                activeProfileId = worker.id,
            ),
        )
        assertNull(LocalWorkerModelRouter.resolve(null, state))
    }

    private fun state(
        profiles: List<LocalModelProfile>,
        workerProfileId: String?,
    ): LocalHarnessState = LocalHarnessState(
        modelSelection = LocalModelSelectionState(
            profiles = profiles,
            activeProfileId = primaryPlan.id,
            workerProfileId = workerProfileId,
        ),
    )
}
