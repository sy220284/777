package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalModelApiKeyDraftTest {
    private val url = "https://api.anthropic.com/v1"
    private val legacy = LocalModelProfile(modelProfileId("claude-sonnet-5", url), "claude-sonnet-5-5", url,
        protocol = LocalModelProtocol.ANTHROPIC_MESSAGES, displayName = "旧账户")
    private val current = legacy.copy(id = modelProfileId(legacy.model, url), displayName = "新账户")

    @Test fun selectedMigratedIdentitySurvivesEitherListOrderAndKeepsItsKeyAndMetadata() = runBlocking {
        for (profiles in listOf(listOf(current, legacy), listOf(legacy, current))) {
            val reads = mutableListOf<String>()
            val draft = resolveLocalModelApiKeyDraft("", "claude-sonnet-5", "$url/", null, profiles,
                { id -> reads += id; if (id == legacy.id) "legacy-key" else "other-key" }, legacy.id)
            assertEquals(legacy.id, draft.profile.id)
            assertEquals("legacy-key", draft.key)
            assertEquals("旧账户", draft.profile.displayName)
            assertEquals(LocalModelProtocol.ANTHROPIC_MESSAGES, draft.profile.protocol)
            assertEquals(listOf(legacy.id), reads)
        }
    }

    @Test fun missingSelectedKeyNeverFallsThroughToTheDerivedRouteIdentity() = runBlocking {
        val reads = mutableListOf<String>()
        val failure = runCatching {
            resolveLocalModelApiKeyDraft("", legacy.model, url, null, listOf(current, legacy),
                { id -> reads += id; if (id == current.id) "other-key" else null }, legacy.id)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf(legacy.id), reads)
    }

    @Test fun deletedOrChangedDraftIdentityIsRejectedBeforeReadingOrUsingAnyKey() = runBlocking {
        for ((targetUrl, profiles) in listOf(url to listOf(current), "https://proxy.example/v1" to listOf(current, legacy))) {
            var reads = 0
            val failure = runCatching {
                resolveLocalModelApiKeyDraft("replacement", legacy.model, targetUrl, null, profiles,
                    { reads++; "other-key" }, legacy.id)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(0, reads)
        }
    }

    @Test fun explicitKeyWithoutProfileIdCreatesAnotherAccountInsteadOfOverwritingEitherExistingRoute() = runBlocking {
        var reads = 0
        val draft = resolveLocalModelApiKeyDraft(
            " replacement ",
            legacy.model,
            url,
            null,
            listOf(current, legacy),
            { reads++; "should-not-read" },
            newProfileId = { "api-third" },
        )
        assertEquals("api-third", draft.profile.id)
        assertEquals("replacement", draft.key)
        assertNotEquals(current.id, draft.profile.id)
        assertNotEquals(legacy.id, draft.profile.id)
        assertEquals(0, reads)
    }

    @Test fun blankKeyWithoutProfileIdStillRefusesToGuessBetweenSameRouteAccounts() = runBlocking {
        var reads = 0
        val failure = runCatching {
            resolveLocalModelApiKeyDraft(
                "",
                legacy.model,
                url,
                null,
                listOf(current, legacy),
                { reads++; "key" },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, reads)
    }

    @Test fun replacementKeyDoesNotReadAnotherCredentialAndKeepsTheChosenProtocol() = runBlocking {
        val draft = resolveLocalModelApiKeyDraft(" replacement ", legacy.model, url,
            LocalModelProtocol.RESPONSES, listOf(current, legacy), { error("No stored key should be read") }, legacy.id)
        assertEquals(legacy.id, draft.profile.id)
        assertEquals("replacement", draft.key)
        assertEquals(LocalModelProtocol.RESPONSES, draft.profile.protocol)
    }

    @Test fun apiKeyLookupCannotBorrowAPlanAccountsCredentialIdentity() = runBlocking {
        val plan = current.copy(id = "plan", authKind = LocalModelAuthKind.CHATGPT_PLAN, credentialRef = "account")
        val failure = runCatching {
            resolveLocalModelApiKeyDraft("key", plan.model, plan.baseUrl, null, listOf(plan), { "key" }, plan.id)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        val newRoute = resolveLocalModelApiKeyDraft("new-key", plan.model, plan.baseUrl, null, listOf(plan), { null })
        assertEquals(LocalModelAuthKind.API_KEY, newRoute.profile.authKind)
        assertNotEquals(plan.id, newRoute.profile.id)
    }

    @Test fun generatedIdentityRetriesCollisionsBeforeSaving() = runBlocking {
        var attempts = 0
        val draft = resolveLocalModelApiKeyDraft(
            "new-key",
            legacy.model,
            url,
            null,
            listOf(current, legacy),
            { error("Explicit key needs no stored credential") },
            newProfileId = {
                attempts++
                when (attempts) {
                    1 -> current.id
                    2 -> legacy.id
                    else -> "api-unique"
                }
            },
        )
        assertEquals("api-unique", draft.profile.id)
        assertEquals(3, attempts)
    }

    @Test fun cancelledCredentialReadPropagatesInsteadOfCreatingAFallbackRoute() = runBlocking {
        val cancelled = CancellationException("cancelled")
        val failure = runCatching {
            resolveLocalModelApiKeyDraft("", legacy.model, url, null, listOf(legacy), { throw cancelled }, legacy.id)
        }.exceptionOrNull()
        assertSame(cancelled, failure)
    }
    @Test fun uniqueLegacyIdentityAndNewRoutesStillWorkWithoutAnExplicitId() = runBlocking {
        val retained = resolveLocalModelApiKeyDraft("", legacy.model, url, null, listOf(legacy),
            { id -> if (id == legacy.id) "legacy-key" else error("Wrong identity") })
        assertEquals(legacy.id, retained.profile.id)
        val created = resolveLocalModelApiKeyDraft(
            "new-key",
            "custom",
            "https://proxy.example/v1/",
            LocalModelProtocol.RESPONSES,
            emptyList(),
            { error("Explicit key needs no stored credential") },
            newProfileId = { "api-custom" },
        )
        assertEquals("api-custom", created.profile.id)
        assertNotEquals(modelProfileId("custom", "https://proxy.example/v1"), created.profile.id)
        assertEquals(LocalModelProtocol.RESPONSES, created.profile.protocol)
    }

    @Test fun customContextOverrideIsValidatedAndOfficialPresetIgnoresIt() = runBlocking {
        val custom = resolveLocalModelApiKeyDraft(
            "key", "custom-model", "https://proxy.example/v1", null, emptyList(), { null },
            contextWindowTokensOverride = 500_000,
            newProfileId = { "custom" },
        )
        assertEquals(500_000, custom.profile.contextWindowTokensOverride)

        val official = resolveLocalModelApiKeyDraft(
            "key", "deepseek-flash", "https://api.deepseek.com", null, emptyList(), { null },
            contextWindowTokensOverride = 8_000,
            newProfileId = { "official" },
        )
        assertNull(official.profile.contextWindowTokensOverride)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                resolveLocalModelApiKeyDraft(
                    "key", "custom-model", "https://proxy.example/v1", null, emptyList(), { null },
                    contextWindowTokensOverride = 1_000,
                    newProfileId = { "bad" },
                )
            }
        }
        Unit
    }

}
