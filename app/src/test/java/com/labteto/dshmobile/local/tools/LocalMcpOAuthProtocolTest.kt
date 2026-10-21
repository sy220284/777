package com.labteto.dshmobile.local.tools

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl

class LocalMcpOAuthProtocolTest {
    @Test fun pkceUsesRfc7636S256Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            LocalMcpOAuthProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun callbackBindsStateAndExactIssuerIncludingErrors() {
        val params = mapOf("state" to "expected", "iss" to "https://issuer.example", "code" to "code")
        assertEquals("code", LocalMcpOAuthProtocol.validateCallback(params, "expected", "https://issuer.example", true))
        assertThrows(IllegalArgumentException::class.java) {
            LocalMcpOAuthProtocol.validateCallback(params, "wrong", "https://issuer.example", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalMcpOAuthProtocol.validateCallback(params, "expected", "https://issuer.example/", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalMcpOAuthProtocol.validateCallback(params - "iss", "expected", "https://issuer.example", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalMcpOAuthProtocol.validateCallback(params + ("error" to "access_denied"),
                "expected", "https://wrong.example", false)
        }
    }
    @Test fun issuerDiscoveryKeepsSpecifiedOAuthAndOidcOrder() {
        assertEquals(listOf(
            "https://auth.example/.well-known/oauth-authorization-server/tenant",
            "https://auth.example/.well-known/openid-configuration/tenant",
            "https://auth.example/tenant/.well-known/openid-configuration"),
            LocalMcpOAuthProtocol.issuerMetadataUrls("https://auth.example/tenant"))
    }
    @Test fun discoveryRegistrationTokenRotationAndRevocationUseBoundResource() = runTest {
        val requests = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Request>())
        var tokenCalls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val path = request.url.encodedPath
            var code = 200
            val body = when (path) {
                "/mcp" -> { code = 401; "{}" }
                "/.well-known/oauth-protected-resource/mcp" ->
                    """{"resource":"https://mcp.example/mcp","authorization_servers":["https://auth.example/tenant"]}"""
                "/.well-known/oauth-authorization-server/tenant" -> { code = 404; "{}" }
                "/.well-known/openid-configuration/tenant" ->
                    """{"issuer":"https://auth.example/tenant","authorization_endpoint":"https://auth.example/authorize","token_endpoint":"https://auth.example/token","registration_endpoint":"https://auth.example/register","revocation_endpoint":"https://auth.example/revoke","code_challenge_methods_supported":["S256"],"authorization_response_iss_parameter_supported":true}"""
                "/register" -> """{"client_id":"registered","token_endpoint_auth_method":"none"}"""
                "/token" -> { tokenCalls++; if (tokenCalls == 1)
                    """{"token_type":"Bearer","access_token":"first","refresh_token":"refresh-first","expires_in":0}"""
                    else """{"token_type":"Bearer","access_token":"second","refresh_token":"refresh-second","expires_in":3600}""" }
                "/revoke" -> "{}"
                else -> { code = 404; "{}" }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .apply { if (path == "/mcp") header("WWW-Authenticate",
                    "Bearer resource_metadata=\"https://mcp.example/.well-known/oauth-protected-resource/mcp\", scope=\"read:items\"") }
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val protocol = LocalMcpOAuthProtocol(http)
        val info = protocol.discover("https://mcp.example/mcp")
        assertEquals("read:items", info.scope)
        val id = protocol.register(info, "http://127.0.0.1:1234/mcp-callback")
        val authorization = protocol.authorizationUrl(info, id, "http://127.0.0.1:1234/mcp-callback", "state", "verifier").toHttpUrl()
        assertEquals("https://mcp.example/mcp", authorization.queryParameter("resource"))
        assertEquals("S256", authorization.queryParameter("code_challenge_method"))
        val first = protocol.exchange(info, id, "http://127.0.0.1:1234/mcp-callback", "verifier", "code")
        val refreshed = protocol.refresh(first)
        assertEquals("second", refreshed.accessToken)
        assertEquals("refresh-second", refreshed.refreshToken)
        assertTrue(protocol.revoke(refreshed))
        val tokenForms = requests.filter { it.url.encodedPath == "/token" }.map { it.body as FormBody }
        tokenForms.forEach { form ->
            assertTrue((0 until form.size).any { form.name(it) == "resource" && form.value(it) == "https://mcp.example/mcp" })
        }
        assertEquals(2, tokenCalls)
        assertTrue(requests.all { it.header("Authorization") == null })
        assertTrue(requests.none { it.url.query?.contains("access_token") == true })
    }

}
