package com.labteto.dshmobile.local.model.chatgpt

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptAuthorizationRecoveryTest {
    @Test
    fun invalidGrantGetsExactlyOneFreshAuthorizationOpportunity() {
        assertTrue(shouldRetryChatGptAuthorization("invalid_grant", allowed = true))
        assertFalse(shouldRetryChatGptAuthorization("invalid_grant", allowed = false))
    }

    @Test
    fun otherOAuthFailuresNeverEnterAutomaticAuthorizationLoop() {
        assertFalse(shouldRetryChatGptAuthorization("access_denied", allowed = true))
        assertFalse(shouldRetryChatGptAuthorization(null, allowed = true))
    }

    @Test
    fun normalSignInDoesNotForceConsentButExplicitPlanEnableDoes() {
        assertEquals(null, chatGptAuthorizationPrompt(requestPlanConsent = false))
        assertEquals("consent", chatGptAuthorizationPrompt(requestPlanConsent = true))
    }

    @Test
    fun signedInIdentityWithoutPlanScopeIsPreservedButCannotRoutePlanModels() {
        val summary = ChatGptAccountSummary(
            id = "account",
            clientId = "oaiapp_account",
            email = "user@example.com",
            displayName = "User",
            signedIn = true,
            sharingEnabled = false,
        )
        val state = ChatGptUiState(
            phase = ChatGptAuthPhase.CONNECTED,
            accounts = listOf(summary),
            selectedAccountId = summary.id,
        )

        assertTrue(state.signedIn)
        assertFalse(state.connected)
    }

    @Test
    fun signedInIdentityWithPlanScopeIsConnectedForModelRouting() {
        val summary = ChatGptAccountSummary(
            id = "account",
            clientId = "oaiapp_account",
            email = "user@example.com",
            displayName = "User",
            signedIn = true,
            sharingEnabled = true,
        )
        val state = ChatGptUiState(
            phase = ChatGptAuthPhase.CONNECTED,
            accounts = listOf(summary),
            selectedAccountId = summary.id,
        )

        assertTrue(state.signedIn)
        assertTrue(state.connected)
    }

    @Test
    fun terminalRefreshFailuresInvalidateOnlyTheRenewableSession() {
        listOf(
            "invalid_grant",
            "invalid_refresh_token",
            "token_expired",
            "refresh_token_expired",
            "refresh_token_invalidated",
            "refresh_token_reused",
        ).forEach { assertTrue(shouldInvalidateChatGptRefreshToken(it)) }
    }

    @Test
    fun temporaryRefreshFailuresKeepCredentialsForARealRetry() {
        listOf(null, "temporarily_unavailable", "server_error", "invalid_client").forEach {
            assertFalse(shouldInvalidateChatGptRefreshToken(it))
        }
    }

    @Test
    fun closingCallbackListenerUnblocksAnAbandonedAuthorization() = runBlocking {
        val listener = ChatGptOAuthCallbackServer().open()
        try {
            val waiting = async {
                runCatching { listener.await(timeoutMillis = 10_000L) }.exceptionOrNull()
            }
            listener.close()
            assertNotNull(withTimeout(1_000L) { waiting.await() })
        } finally {
            listener.close()
        }
    }

    @Test
    fun issuedClientIdKeepsSameSubjectRegistrationsDistinctAndStable() {
        val first = ChatGptAccountStore.accountId("oaiapp_first", "subject")
        assertEquals(first, ChatGptAccountStore.accountId("oaiapp_first", "subject"))
        assertNotEquals(first, ChatGptAccountStore.accountId("oaiapp_second", "subject"))
    }

    @Test
    fun planBindingRequiresExactRegistrationAndInferenceScopes() {
        val id = ChatGptAccountStore.accountId("oaiapp_first", "subject")
        val valid = ChatGptAccountRecord(
            id = id,
            clientId = "oaiapp_first",
            issuer = CHATGPT_ISSUER,
            subject = "subject",
            hostId = "urn:uuid:test",
            idToken = "id-token",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            scopes = setOf(CHATGPT_PLAN_SCOPE, CHATGPT_RESOURCE_INVOKE_SCOPE),
            accessTokenExpiresAtEpochSeconds = Long.MAX_VALUE,
            savedAtEpochSeconds = 1L,
        )
        assertTrue(isUsableChatGptPlanBinding(id, valid))
        assertFalse(isUsableChatGptPlanBinding(id, valid.copy(clientId = CHATGPT_DYNAMIC_CLIENT_ID)))
        assertFalse(isUsableChatGptPlanBinding(id, valid.copy(scopes = setOf(CHATGPT_PLAN_SCOPE))))
        assertFalse(isUsableChatGptPlanBinding("other-account", valid))
    }
}
