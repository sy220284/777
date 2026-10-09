package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.LocalReasoningMode
import com.labteto.dshmobile.local.model.LocalReasoningModeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptModelProfilesTest {
    @Test
    fun accountCatalogRoutesModernGptEffortsThroughPlanResponses() {
        val modelIds = listOf("gpt-5.6-sol", "gpt-6-sol", "gpt-6-luna",
            "gpt-6-astra", "gpt-6.1-sol")
        val accountA = chatGptPlanProfiles("account-a", modelIds.map { ChatGptModelOption(it, it) })
        val accountB = chatGptPlanProfiles("account-b", listOf(ChatGptModelOption("gpt-6-sol", "Sol")))
        val session = "plan-account-reasoning-test"
        try {
            LocalReasoningModeStore.setMode(session, LocalReasoningMode.MAX)
            for (profile in accountA + accountB) {
                assertEquals(LocalModelProtocol.RESPONSES, profile.protocol)
                assertTrue(LocalReasoningModeStore.isSupported(profile, withTools = true))
                assertEquals("max", LocalReasoningModeStore.effortFor(session, profile, withTools = true))
            }
            assertTrue(accountA.first { it.model == "gpt-6-sol" }.id != accountB.single().id)
            val missingBinding = accountA.first().copy(credentialRef = null)
            assertTrue(!LocalReasoningModeStore.isSupported(missingBinding, withTools = true))
        } finally {
            LocalReasoningModeStore.setMode(session, LocalReasoningMode.DEFAULT)
        }
    }

    @Test
    fun everyRefreshedCatalogModelBecomesAPlanResponsesProfileWithoutNameAllowlist() {
        val profiles = chatGptPlanProfiles(
            accountId = "account-a",
            models = listOf(
                ChatGptModelOption("gpt-5.6", "GPT 5.6"),
                ChatGptModelOption("future-family-x", "Future X"),
                ChatGptModelOption("totally-new-name", "New"),
            ),
        )

        assertEquals(
            listOf("gpt-5.6", "future-family-x", "totally-new-name"),
            profiles.map { it.model },
        )
        assertTrue(profiles.all { it.authKind == LocalModelAuthKind.CHATGPT_PLAN })
        assertTrue(profiles.all { it.protocol == LocalModelProtocol.RESPONSES })
        assertTrue(profiles.all { it.credentialRef == "account-a" })
        assertTrue(profiles.all { it.baseUrl == CHATGPT_RESOURCE })
    }

    @Test
    fun sameModelForDifferentAccountsKeepsCredentialIdentitySeparated() {
        val option = listOf(ChatGptModelOption("shared-model", "Shared"))

        val first = chatGptPlanProfiles("account-a", option).single()
        val second = chatGptPlanProfiles("account-b", option).single()

        assertTrue(first.id != second.id)
        assertEquals("account-a", first.credentialRef)
        assertEquals("account-b", second.credentialRef)
    }

    @Test
    fun retiringPlanAccessRemovesOnlyThatAccountsProfiles() {
        val accountA = chatGptPlanProfiles(
            "account-a",
            listOf(ChatGptModelOption("shared-model", "Shared")),
        )
        val accountB = chatGptPlanProfiles(
            "account-b",
            listOf(ChatGptModelOption("shared-model", "Shared")),
        )
        val apiProfile = com.labteto.dshmobile.local.model.LocalModelProfile(
            id = "api",
            model = "shared-model",
            baseUrl = "https://example.test/v1",
        )

        val retired = refreshChatGptPlanProfiles(
            existing = accountA + accountB + apiProfile,
            accountId = "account-a",
            models = emptyList(),
        )

        assertTrue(retired.none { it.credentialRef == "account-a" })
        assertTrue(retired.any { it.credentialRef == "account-b" })
        assertTrue(retired.any { it.id == apiProfile.id })
    }

    @Test
    fun refreshedCatalogReplacesOnlyTheSelectedAccountsPlanModels() {
        val accountAOld = chatGptPlanProfiles(
            "account-a",
            listOf(
                ChatGptModelOption("old-model", "Old"),
                ChatGptModelOption("keep-model", "Keep"),
            ),
        )
        val accountB = chatGptPlanProfiles(
            "account-b",
            listOf(ChatGptModelOption("other-account-model", "Other")),
        )
        val apiProfile = com.labteto.dshmobile.local.model.LocalModelProfile(
            id = "api",
            model = "api-model",
            baseUrl = "https://example.test/v1",
        )

        val refreshed = refreshChatGptPlanProfiles(
            existing = accountAOld + accountB + apiProfile,
            accountId = "account-a",
            models = listOf(
                ChatGptModelOption("keep-model", "Keep renamed"),
                ChatGptModelOption("new-model", "New"),
            ),
        )

        assertEquals(
            setOf("keep-model", "new-model"),
            refreshed.filter { it.credentialRef == "account-a" }.map { it.model }.toSet(),
        )
        assertTrue(refreshed.none { it.model == "old-model" })
        assertTrue(refreshed.any { it.credentialRef == "account-b" && it.model == "other-account-model" })
        assertTrue(refreshed.any { it.id == apiProfile.id })
    }

}
