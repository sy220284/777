package com.labteto.dshmobile.ui.screens.settings

import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelSelectionState
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountSummary
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAuthPhase
import com.labteto.dshmobile.local.model.chatgpt.ChatGptModelOption
import com.labteto.dshmobile.local.model.chatgpt.ChatGptUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelProfileFiltersTest {
    @Test
    fun modelAndAccountShareOneNavigationDestination() {
        assertTrue(SettingsDestination.entries.none { it.name == "ACCOUNT" })
        assertEquals(SettingsDestination.ROOT, SettingsDestination.MODELS.parentDestination())
        assertEquals(SettingsDestination.ROOT, SettingsDestination.MODEL_PERFORMANCE.parentDestination())
        assertEquals(1, SettingsDestination.MODEL_PERFORMANCE.navigationDepth())
    }

    private val api = LocalModelProfile(id = "api", model = "same", baseUrl = "https://example.com")
    private val planA = LocalModelProfile(
        id = "plan-a", model = "same", baseUrl = "https://api.openai.com/v1",
        authKind = LocalModelAuthKind.CHATGPT_PLAN, credentialRef = "account-a", displayName = "套餐 A",
    )
    private val planB = LocalModelProfile(
        id = "plan-b", model = "other", baseUrl = "https://api.openai.com/v1",
        authKind = LocalModelAuthKind.CHATGPT_PLAN, credentialRef = "account-b",
    )

    private fun state(
        enabled: Boolean = true,
        signedIn: Boolean = true,
        phase: ChatGptAuthPhase = ChatGptAuthPhase.CONNECTED,
        models: List<ChatGptModelOption> = listOf(ChatGptModelOption("same", "套餐 A")),
    ) = ChatGptUiState(
        phase = phase,
        accounts = listOf(ChatGptAccountSummary("account-a", "client", null, null, signedIn, enabled)),
        selectedAccountId = "account-a",
        models = models,
    )

    @Test
    fun apiModelSettingsNeverShowsPlanModelsEvenWhenNoApiModelExists() {
        assertEquals(listOf(api), listOf(planA, api, planB).apiModelSettingsProfiles())
        assertTrue(listOf(planA, planB).apiModelSettingsProfiles().isEmpty())
    }

    @Test
    fun accountPageShowsOnlyCurrentAccountCatalogAndKeepsActiveIdentity() {
        val selection = LocalModelSelectionState(
            profiles = listOf(api, planB, planA),
            activeProfileId = planA.id,
        )
        val rows = chatGptAccountModelRows(state(), selection)
        assertEquals(1, rows.size)
        assertEquals("same", rows.single().slug)
        assertEquals("套餐 A", rows.single().displayName)
        assertEquals(planA.id, rows.single().profileId)
        assertTrue(rows.single().active)
    }

    @Test
    fun newlyDiscoveredModelAppearsBeforeProfileSyncCompletes() {
        val rows = chatGptAccountModelRows(
            state(models = listOf(ChatGptModelOption("new", "新模型"))),
            LocalModelSelectionState(profiles = listOf(api, planA)),
        )
        assertEquals("新模型", rows.single().displayName)
        assertNull(rows.single().profileId)
        assertFalse(rows.single().active)
    }

    @Test
    fun transientCatalogFailureRetainsLastSyncedModelsForDisplay() {
        val rows = chatGptAccountModelRows(
            state(phase = ChatGptAuthPhase.UNVERIFIED, models = emptyList()),
            LocalModelSelectionState(profiles = listOf(planB, planA)),
        )
        assertEquals(listOf("same"), rows.map { it.slug })
        assertEquals(planA.id, rows.single().profileId)
    }

    @Test
    fun disabledOrSignedOutAccountNeverShowsCachedPlanModels() {
        val selection = LocalModelSelectionState(profiles = listOf(planA))
        assertTrue(chatGptAccountModelRows(state(enabled = false), selection).isEmpty())
        assertTrue(chatGptAccountModelRows(state(signedIn = false), selection).isEmpty())
        assertTrue(chatGptAccountModelRows(ChatGptUiState(), selection).isEmpty())
    }
}
