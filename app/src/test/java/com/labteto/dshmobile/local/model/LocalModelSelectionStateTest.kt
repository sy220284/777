package com.labteto.dshmobile.local.model

import org.junit.Assert.*
import org.junit.Test

class LocalModelSelectionStateTest {
    @Test fun sameModelAndEndpointNeverMakeOtherAccountsActive() {
        val api = LocalModelProfile("api", "gpt-test", "https://api.openai.com/v1")
        val first = api.copy(id = "plan-a", authKind = LocalModelAuthKind.CHATGPT_PLAN, credentialRef = "a")
        val second = first.copy(id = "plan-b", credentialRef = "b")
        for (order in listOf(listOf(api, first, second), listOf(second, first, api))) {
            val selection = LocalModelSelectionState(order, first.id)
            assertTrue(selection.isActive(first))
            assertFalse(selection.isActive(second))
            assertFalse(selection.isActive(api))
            assertTrue(selection.copy(activeProfileId = second.id).isActive(second))
            assertFalse(selection.copy(activeProfileId = null).isActive(first))
        }
    }

    @Test fun workerProfileSurvivesProfileRefreshOnlyWhileStillAvailable() {
        val primary = LocalModelProfile("primary", "gpt-test", "https://api.openai.com/v1")
        val worker = LocalModelProfile("worker", "deepseek-chat", "https://api.deepseek.com")
        val selection = LocalModelSelectionState(
            profiles = listOf(primary, worker),
            activeProfileId = primary.id,
            workerProfileId = worker.id,
        )

        val refreshed = selection.replaceProfiles(listOf(worker, primary), primary.id)
        assertEquals(worker.id, refreshed.workerProfileId)
        assertEquals(worker, refreshed.workerProfile)

        val removed = refreshed.replaceProfiles(listOf(primary), primary.id)
        assertNull(removed.workerProfileId)
        assertNull(removed.workerProfile)
    }

}
