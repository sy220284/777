package com.labteto.dshmobile.interop.github

import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubConnectorPluginTest {
    @Test
    fun connectorInjectsCredentialWithoutReturningItToTheModel() = runBlocking {
        val token = "github_pat_test_secret_1234567890"
        var authorization: String? = null
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                authorization = chain.request().header("Authorization")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("X-RateLimit-Remaining", "59")
                    .body("""{"login":"connector-user"}""".toResponseBody(JSON_MEDIA))
                    .build()
            }
            .build()
        val registry = PluginRegistry()
        registry.install(
            GitHubConnectorPlugin(
                http = http,
                json = Json,
                credentialProvider = { token },
                apiBaseUrl = "https://api.github.test",
            ),
        )

        val result = registry.context.tools.execute("github_status", buildJsonObject { })

        assertFalse(result.isError)
        assertEquals("Bearer $token", authorization)
        assertTrue(result.content.contains("connector-user"))
        assertTrue(result.content.contains("59"))
        assertFalse(result.content.contains(token))
    }

    @Test
    fun mutationRequiresApprovalAndNeverLeaksCredentialIntoArguments() = runBlocking {
        val token = "github_pat_test_secret_1234567890"
        var method: String? = null
        var path: String? = null
        var authorization: String? = null
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                method = chain.request().method
                path = chain.request().url.encodedPath
                authorization = chain.request().header("Authorization")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body("""{"number":271}""".toResponseBody(JSON_MEDIA))
                    .build()
            }
            .build()
        val registry = PluginRegistry()
        registry.install(
            GitHubConnectorPlugin(
                http = http,
                json = Json,
                credentialProvider = { token },
                apiBaseUrl = "https://api.github.test",
            ),
        )
        val args = buildJsonObject {
            put("method", "POST")
            put("path", "/repos/example/project/pulls")
            put("body", buildJsonObject {
                put("title", "test")
                put("head", "feature")
                put("base", "main")
            })
        }

        val blocked = registry.context.tools.execute("github_api_request", args)
        assertTrue(blocked.isError)
        assertTrue(blocked.content.contains("审批"))

        val result = registry.context.tools.execute(
            "github_api_request",
            args,
            context = ToolContext(approval = { true }),
        )
        assertFalse(result.isError)
        assertEquals("POST", method)
        assertEquals("/repos/example/project/pulls", path)
        assertEquals("Bearer $token", authorization)
        assertFalse(args.toString().contains(token))
        assertFalse(result.content.contains(token))
    }

    @Test
    fun readRetriesTransientGatewayFailureButMutationDoesNot() = runBlocking {
        val token = "github_pat_test_secret_1234567890"
        var readCalls = 0
        val readHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                readCalls += 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (readCalls == 1) 503 else 200)
                    .message(if (readCalls == 1) "Service Unavailable" else "OK")
                    .body(
                        (if (readCalls == 1) """{"message":"retry"}""" else """{"ok":true}""")
                            .toResponseBody(JSON_MEDIA),
                    )
                    .build()
            }
            .build()
        val readRegistry = PluginRegistry()
        readRegistry.install(
            GitHubConnectorPlugin(
                http = readHttp,
                json = Json,
                credentialProvider = { token },
                apiBaseUrl = "https://api.github.test",
            ),
        )

        val read = readRegistry.context.tools.execute(
            "github_api_get",
            buildJsonObject { put("path", "/repos/example/project") },
        )
        assertFalse(read.isError)
        assertEquals(2, readCalls)

        var writeCalls = 0
        val writeHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                writeCalls += 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body("""{"message":"unknown delivery"}""".toResponseBody(JSON_MEDIA))
                    .build()
            }
            .build()
        val writeRegistry = PluginRegistry()
        writeRegistry.install(
            GitHubConnectorPlugin(
                http = writeHttp,
                json = Json,
                credentialProvider = { token },
                apiBaseUrl = "https://api.github.test",
            ),
        )
        val write = writeRegistry.context.tools.execute(
            "github_api_request",
            buildJsonObject {
                put("method", "POST")
                put("path", "/repos/example/project/pulls")
                put("body", buildJsonObject {
                    put("title", "test")
                    put("head", "feature")
                    put("base", "main")
                })
            },
            context = ToolContext(approval = { true }),
        )

        assertTrue(write.isError)
        assertEquals(1, writeCalls)
    }

    @Test
    fun connectorRejectsSensitiveRepositoryAdministrationRoutes() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls += 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(204)
                    .message("No Content")
                    .body("".toResponseBody(JSON_MEDIA))
                    .build()
            }
            .build()
        val registry = PluginRegistry()
        registry.install(
            GitHubConnectorPlugin(
                http = http,
                json = Json,
                credentialProvider = { "github_pat_test_secret_1234567890" },
                apiBaseUrl = "https://api.github.test",
            ),
        )
        val result = registry.context.tools.execute(
            "github_api_request",
            buildJsonObject {
                put("method", "PUT")
                put("path", "/repos/example/project/actions/secrets/SECRET")
                put("body", buildJsonObject { put("encrypted_value", "hidden") })
            },
            context = ToolContext(approval = { true }),
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("未向 Agent 开放"))

        val deleteRepository = registry.context.tools.execute(
            "github_api_request",
            buildJsonObject {
                put("method", "DELETE")
                put("path", "/repos/example/project")
            },
            context = ToolContext(approval = { true }),
        )
        assertTrue(deleteRepository.isError)
        assertTrue(deleteRepository.content.contains("仓库本体"))
        assertEquals(0, calls)
    }

    @Test
    fun missingCredentialIsReportedWithoutNetworkAccess() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls += 1
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("unexpected")
                    .body("{}".toResponseBody(JSON_MEDIA))
                    .build()
            }
            .build()
        val registry = PluginRegistry()
        registry.install(
            GitHubConnectorPlugin(
                http = http,
                json = Json,
                credentialProvider = { null },
                apiBaseUrl = "https://api.github.test",
            ),
        )

        val status = registry.context.tools.execute("github_status", buildJsonObject { })
        val read = registry.context.tools.execute(
            "github_api_get",
            buildJsonObject { put("path", "/repos/example/project") },
        )

        assertFalse(status.isError)
        assertTrue(status.content.contains("\"configured\":false"))
        assertTrue(read.isError)
        assertTrue(read.content.contains("GITHUB_NOT_CONFIGURED"))
        assertEquals(0, calls)
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
