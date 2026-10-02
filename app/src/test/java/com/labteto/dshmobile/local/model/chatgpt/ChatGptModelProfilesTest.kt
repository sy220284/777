package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptModelProfilesTest {
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
        val apiProfile = com.labteto.dshmobile.local.LocalModelProfile(
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
