package com.labteto.dshmobile.local.tools

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Complete client protocol against a deterministic intercepted issuer, including real loopback
 * callback and Android Keystore persistence. No external provider/TLS socket is implied.
 */
class LocalMcpOAuthProtocolEndToEndAndroidTest {
    @Test fun providerSimulationPkceCallbackExchangeRefreshRevokeAndPersist() = runBlocking {
        val requests = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Request>())
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val path = request.url.encodedPath
            var status = 200
            val response = when (path) {
                "/mcp" -> { status = 401; "{}" }
                "/.well-known/oauth-protected-resource/mcp" ->
                    """{"resource":"https://mcp.perf.invalid/mcp","authorization_servers":["https://auth.perf.invalid"]}"""
                "/.well-known/oauth-authorization-server" ->
                    """{"issuer":"https://auth.perf.invalid","authorization_endpoint":"https://auth.perf.invalid/authorize","token_endpoint":"https://auth.perf.invalid/token","registration_endpoint":"https://auth.perf.invalid/register","revocation_endpoint":"https://auth.perf.invalid/revoke","code_challenge_methods_supported":["S256"],"authorization_response_iss_parameter_supported":true}"""
                "/register" ->
                    """{"client_id":"client-perf","token_endpoint_auth_method":"none"}"""
                "/token" -> {
                    calls++
                    val body = request.body as FormBody
                    val values = (0 until body.size).associate { body.name(it) to body.value(it) }
                    assertEquals("https://mcp.perf.invalid/mcp", values["resource"])
                    if (calls % 2 == 1) {
                        assertEquals("authorization_code", values["grant_type"])
                        assertEquals("oauth-code", values["code"])
                    } else {
                        assertEquals("refresh_token", values["grant_type"])
                        assertEquals("refresh-one", values["refresh_token"])
                    }
                    """{"token_type":"Bearer","access_token":"access-$calls","refresh_token":"refresh-${if (calls % 2 == 1) "one" else "two"}","expires_in":3600}"""
                }
                "/revoke" -> "{}"
                else -> { status = 404; "{}" }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("test issuer")
                .apply { if (path == "/mcp") header("WWW-Authenticate",
                    """Bearer resource_metadata="https://mcp.perf.invalid/.well-known/oauth-protected-resource/mcp" """.trim()) }
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val protocol = LocalMcpOAuthProtocol(http)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uuid = UUID.randomUUID().toString().replace("-", "")
        val id = "oauth" + uuid.take(16)
        val preferencesFile = File(context.cacheDir, "oauth-e2e-$uuid.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = scope, produceFile = { preferencesFile })
        val credentials = LocalMcpCredentialStore(prefs)
        val elapsed = mutableListOf<Double>()
        try {
            repeat(12) { iteration ->
                val begin = SystemClock.elapsedRealtimeNanos()
                val info = protocol.discover("https://mcp.perf.invalid/mcp")
                LocalMcpOAuthCoordinator.CallbackListener().use { listener ->
                    val clientId = protocol.register(info, listener.redirectUri)
                    val state = LocalMcpOAuthProtocol.nonce()
                    val verifier = LocalMcpOAuthProtocol.nonce()
                    val auth = protocol.authorizationUrl(info, clientId, listener.redirectUri, state, verifier)
                        .toHttpUrl()
                    assertEquals(LocalMcpOAuthProtocol.challenge(verifier), auth.queryParameter("code_challenge"))
                    assertEquals("S256", auth.queryParameter("code_challenge_method"))
                    assertEquals(info.resource, auth.queryParameter("resource"))
                    val received = async(Dispatchers.IO) {
                        withTimeout(10_000L) { listener.await(state) }
                    }
                    val uri = URI(listener.redirectUri)
                    withContext(Dispatchers.IO) {
                        Socket("127.0.0.1", uri.port).use { socket ->
                            socket.soTimeout = 3_000
                            val target = "/mcp-callback?state=$state&iss=https%3A%2F%2Fauth.perf.invalid&code=oauth-code"
                            socket.getOutputStream().write(
                                "GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII))
                            assertTrue(socket.getInputStream().bufferedReader().readLine().contains("200"))
                        }
                    }
                    val code = LocalMcpOAuthProtocol.validateCallback(
                        received.await(), state, info.issuer, info.issuerInResponseRequired)
                    val first = protocol.exchange(info, clientId, listener.redirectUri, verifier, code)
                    credentials.saveOAuth(id, info.resource, first)
                    assertEquals(first.accessToken, credentials.forEndpoint(id, info.resource))
                    val next = protocol.refresh(first)
                    assertEquals("refresh-two", next.refreshToken)
                    credentials.saveOAuth(id, info.resource, next)
                    assertEquals(next.accessToken, credentials.forEndpoint(id, info.resource))
                    assertTrue(protocol.revoke(next))
                    credentials.clear(id)
                    assertEquals(null, credentials.forEndpoint(id, info.resource))
                }
                elapsed += (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000.0
            }
            assertEquals(24, calls)
            assertTrue(requests.none { it.header("Authorization") != null })
            assertTrue(requests.none { it.url.query?.contains("access-") == true })
            val s = elapsed.sorted()
            fun p(q: Double) = s[(kotlin.math.ceil(q * s.size).toInt() - 1).coerceIn(0, s.lastIndex)]
            val line = String.format(Locale.US,
                "ANDROID_PERF api=%d scenario=oauth_intercepted_provider_full_protocol " +
                    "samples=%d p50_ms=%.3f p95_ms=%.3f",
                Build.VERSION.SDK_INT, s.size, p(0.5), p(0.95))
            InstrumentationRegistry.getInstrumentation().sendStatus(2,
                Bundle().apply { putString("stream", line) })
            val evidence = File(context.getExternalFilesDir(null), "work-timeline-review")
            check(evidence.isDirectory || evidence.mkdirs())
            File(evidence, "oauth-e2e-api${Build.VERSION.SDK_INT}.txt").writeText(line + "\n")
        } finally {
            credentials.clear(id)
            scope.cancel()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("dsh_mcp_bearer_$id")
            preferencesFile.delete()
        }
    }
}
