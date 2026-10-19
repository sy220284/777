package com.labteto.dshmobile.local.model

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalApiModelCatalogTest {
    @Test fun compatibleEndpointsUseTheConfiguredHostAndPath() {
        assertEquals("https://proxy.example/v1/models",
            apiModelCatalogEndpoint("https://proxy.example/v1").toString())
        assertEquals("https://api.deepseek.com/models",
            apiModelCatalogEndpoint("https://api.deepseek.com").toString())
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/models",
            apiModelCatalogEndpoint("https://generativelanguage.googleapis.com/v1beta/openai/").toString())
        assertEquals("https://api.anthropic.com/v1/models",
            apiModelCatalogEndpoint("https://api.anthropic.com/v1/models").toString())
        assertEquals("http://localhost:11434/v1/models",
            apiModelCatalogEndpoint("http://localhost:11434/v1").toString())
    }

    @Test fun rejectsUrlCredentialsAndInsecureRemoteHosts() {
        assertThrows(IllegalArgumentException::class.java) {
            apiModelCatalogEndpoint("https://alice:password@proxy.example/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            apiModelCatalogEndpoint("http://proxy.example/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            apiModelCatalogEndpoint("https://proxy.example/v1?redirect=https://evil.invalid")
        }
    }

    @Test fun parsesReturnedIdsWithoutInventingCapabilities() {
        val (ids, next) = parseApiModelCatalogPage(
            """{"object":"list","data":[{"id":"new-model"},{"id":"another-model"},{"id":""},{"foo":"bar"}]}""",
        )
        assertEquals(listOf("new-model", "another-model"), ids)
        assertNull(next)
    }

    @Test fun nativeAnthropicPaginationProvidesCursor() {
        val (ids, next) = parseApiModelCatalogPage(
            """{"data":[{"id":"claude-sonnet-5"}],"has_more":true,"last_id":"claude-sonnet-5"}""",
        )
        assertEquals(listOf("claude-sonnet-5"), ids)
        assertEquals("claude-sonnet-5", next)
        assertThrows(IllegalStateException::class.java) {
            parseApiModelCatalogPage("""{"data":[],"has_more":true}""")
        }
    }
    @Test fun userConfiguredCompatibleProxyUsesOnlyItsEnteredOriginAndBearerKey() = runBlocking {
        val requested = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val call = chain.request()
            requested += "${call.url}|${call.header("Authorization")}"
            Response.Builder().request(call).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK")
                .body("""{"data":[{"id":"new-model"},{"id":"new-model"},{"id":"new-vision"}]}""".toResponseBody())
                .build()
        }.build()
        val models = LocalApiModelCatalog(http).query(
            "test-key", "https://proxy.example/custom/v1", LocalModelProtocol.CHAT_COMPLETIONS,
        )
        assertEquals(listOf("new-model", "new-vision"), models)
        assertEquals(listOf("https://proxy.example/custom/v1/models|Bearer test-key"), requested)
    }

    @Test fun anthropicUsesItsOwnApiHeadersAndReadsAllPages() = runBlocking {
        val requested = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val call = chain.request()
            assertEquals("anthropic-key", call.header("x-api-key"))
            assertEquals("2023-06-01", call.header("anthropic-version"))
            assertNull(call.header("Authorization"))
            requested += call.url.toString()
            val body = if (requested.size == 1)
                """{"data":[{"id":"claude-1"}],"has_more":true,"last_id":"claude-1"}"""
            else """{"data":[{"id":"claude-2"}],"has_more":false}"""
            Response.Builder().request(call).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody()).build()
        }.build()
        val models = LocalApiModelCatalog(http).query(
            "anthropic-key", "https://api.anthropic.com/v1", LocalModelProtocol.ANTHROPIC_MESSAGES,
        )
        assertEquals(listOf("claude-1", "claude-2"), models)
        assertEquals(listOf(
            "https://api.anthropic.com/v1/models",
            "https://api.anthropic.com/v1/models?after_id=claude-1",
        ), requested)
    }

    @Test fun miniMaxCatalog401KeepsBearerHeaderAndExplainsRegionWithoutEchoingKey() = runBlocking {
        val observed = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            observed += chain.request().url.toString() + "|" + chain.request().header("Authorization")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401)
                .message("Unauthorized").body("""{"error":{"message":"login fail: Please carry the API secret key (1004)"}}""".toResponseBody()).build()
        }.build()
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                LocalApiModelCatalog(http).query("sample-only-key", "https://api.minimax.io/v1", LocalModelProtocol.CHAT_COMPLETIONS)
            }
        }
        assertEquals(listOf("https://api.minimax.io/v1/models|Bearer sample-only-key"), observed)
        assertTrue(error.message!!.contains("国际区"))
        assertFalse(error.message!!.contains("sample-only-key"))
    }

}
