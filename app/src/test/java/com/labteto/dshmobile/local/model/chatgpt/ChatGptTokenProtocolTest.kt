package com.labteto.dshmobile.local.model.chatgpt

import java.io.IOException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptTokenProtocolTest {
    @Test
    fun officialTokenResponseAcceptsOpaqueNestedAuthMetadata() {
        val token = parseChatGptTokenResponse(Json, """
            {
              "access_token":"access",
              "refresh_token":"rotated-refresh",
              "id_token":"id",
              "token_type":"Bearer",
              "expires_in":3600,
              "scope":"openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
              "earliest_refresh_at":1790032532,
              "https://api.openai.com/auth":{"per_user_salt":"opaque","encrypted_auth_metadata":"opaque"}
            }
        """.trimIndent())
        assertEquals("access", token.accessToken)
        assertEquals("rotated-refresh", token.refreshToken)
        assertEquals(3600L, token.expiresInSeconds)
        assertEquals(1790032532L, token.earliestRefreshAtEpochSeconds)
        assertTrue(token.scopes.contains(CHATGPT_PLAN_SCOPE))
        assertTrue(token.scopes.contains(CHATGPT_RESOURCE_INVOKE_SCOPE))
    }

    @Test
    fun supportsPreviousResponseWithoutOptionalFields() {
        val token = parseChatGptTokenResponse(Json, """{"access_token":"new","expires_in":3600}""")
        assertNull(token.refreshToken)
        assertNull(token.idToken)
        assertTrue(token.scopes.isEmpty())
        assertEquals("Bearer", token.tokenType)
    }

    @Test
    fun invalidGrantInObjectErrorRemainsRecoverable() {
        val failure = parseChatGptTokenFailure(
            Json,
            """{"error":{"code":"invalid_grant","message":"one-time code expired"}}""",
            400,
            "req_123",
        )
        assertEquals("invalid_grant", failure.oauthCode)
        assertTrue(shouldRetryChatGptAuthorization(failure.oauthCode, allowed = true))
        assertTrue(failure.message.orEmpty().contains("HTTP 400"))
        assertTrue(failure.message.orEmpty().contains("object-error"))
        assertTrue(failure.message.orEmpty().contains("req_123"))
        assertFalse(failure.message.orEmpty().contains("one-time code expired"))
    }

    @Test
    fun classicOAuthStringErrorAlsoRemainsRecoverable() {
        val failure = parseChatGptTokenFailure(
            Json, """{"error":"invalid_grant","error_description":"expired"}""", 400, null,
        )
        assertEquals("invalid_grant", failure.oauthCode)
        assertTrue(failure.message.orEmpty().contains("scalar-error"))
    }

    @Test
    fun unexpectedErrorsKeepStatusAndResponseShape() {
        val detailOnly = parseChatGptTokenFailure(Json, """{"detail":"temporarily unavailable"}""", 503, null)
        assertNull(detailOnly.oauthCode)
        assertTrue(detailOnly.message.orEmpty().contains("HTTP 503"))
        assertTrue(detailOnly.message.orEmpty().contains("other-object"))
        val malformed = parseChatGptTokenFailure(Json, "<html>error</html>", 502, null)
        assertTrue(malformed.message.orEmpty().contains("invalid-json"))
        assertNull(malformed.oauthCode)
    }

    @Test
    fun nestedFieldsCannotTriggerJsonPrimitiveCastException() {
        val invalidPayloads = listOf(
            """{"access_token":{},"expires_in":3600}""",
            """{"access_token":"ok","expires_in":[] }""",
            """{"access_token":"ok","expires_in":3600,"scope":{}}""",
            """{"access_token":"ok","expires_in":3600,"earliest_refresh_at":[]}""",
            """{"access_token":"ok","expires_in":3600,"token_type":["Bearer"]}""",
            """{"access_token":"ok","expires_in":3600,"refresh_token":{}}""",
        )
        invalidPayloads.forEach { payload ->
            val error = runCatching { parseChatGptTokenResponse(Json, payload) }.exceptionOrNull()
            assertTrue("expected protocol error for $payload, got $error", error is IOException)
            assertFalse(error?.message.orEmpty().contains("is not a JsonPrimitive"))
        }
    }

    @Test
    fun expiryMustBePositiveNumberAndTimeMayBeIso8601() {
        for (payload in listOf(
            """{"access_token":"ok"}""",
            """{"access_token":"ok","expires_in":"3600"}""",
            """{"access_token":"ok","expires_in":0}""",
        )) {
            assertTrue(runCatching { parseChatGptTokenResponse(Json, payload) }.exceptionOrNull() is IOException)
        }
        val token = parseChatGptTokenResponse(
            Json, """{"access_token":"ok","expires_in":3600,"earliest_refresh_at":"2026-11-12T00:00:00Z"}""",
        )
        assertTrue(checkNotNull(token.earliestRefreshAtEpochSeconds) > 0L)
    }
}
