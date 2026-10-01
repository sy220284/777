package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelDelta
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.LocalModelRuntimeCapabilities
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicMessagesClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = AnthropicMessagesClient(OkHttpClient(), json)

    private fun route(
        baseUrl: String = "https://api.anthropic.com/v1",
        model: String = "claude-sonnet-5-5",
    ) = LocalResolvedModelRoute(
        profileId = "claude",
        provider = "Claude",
        model = model,
        baseUrl = baseUrl,
        authKind = LocalModelAuthKind.API_KEY,
        protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
        bearerToken = "test-key",
        capabilities = LocalModelRuntimeCapabilities(imageInput = true),
    )

    @Test
    fun sameRouteReplaysNativeThinkingSignatureAndToolUseLosslessly() {
        val route = route()
        val native = buildJsonArray {
            add(buildJsonObject {
                put("type", "thinking")
                put("thinking", "分析")
                put("signature", "signed-thinking")
            })
            add(buildJsonObject {
                put("type", "text")
                put("text", "先读取")
            })
            add(buildJsonObject {
                put("type", "tool_use")
                put("id", "tool-1")
                put("name", "read")
                put("input", buildJsonObject { put("path", "a.txt") })
            })
        }
        val message = LocalCanonicalMessage(
            role = LocalCanonicalRole.ASSISTANT,
            content = listOf(
                LocalCanonicalContent.Reasoning("分析"),
                LocalCanonicalContent.Text("先读取"),
                LocalCanonicalContent.ToolCall(
                    id = "tool-1",
                    name = "read",
                    arguments = buildJsonObject { put("path", "a.txt") },
                    rawArguments = """{"path":"a.txt"}""",
                ),
            ),
            replay = LocalModelReplayEnvelope(
                adapterId = LocalModelAdapterIds.ANTHROPIC_MESSAGES,
                routeFingerprint = route.fingerprint,
                payload = buildJsonObject { put("content", native) },
            ),
        )

        val payload = client.buildPayload(route, listOf(message), emptyList(), null)
        val content = payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(native, content)

        val changedRoute = route(model = "claude-opus-5-5")
        val changed = client.buildPayload(changedRoute, listOf(message), emptyList(), null)
            .getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertFalse(changed.any {
            it.jsonObject["type"]?.jsonPrimitive?.content == "thinking"
        })
        assertTrue(changed.any {
            it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
        })
    }

    @Test
    fun nativeSseStreamsTextThinkingToolArgumentsAndUsage() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        val requestBody = AtomicReference<String>()
        server.createContext("/v1/messages") { exchange ->
            requestBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                val events = listOf(
                    """{"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":5,"cache_read_input_tokens":2,"cache_creation_input_tokens":3,"output_tokens":0}}}""",
                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"完成"}}""",
                    """{"type":"content_block_stop","index":0}""",
                    """{"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":""}}""",
                    """{"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":"分析"}}""",
                    """{"type":"content_block_delta","index":1,"delta":{"type":"signature_delta","signature":"sig-1"}}""",
                    """{"type":"content_block_stop","index":1}""",
                    """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool-1","name":"read","input":{}}}""",
                    """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.txt\"}"}}""",
                    """{"type":"content_block_stop","index":2}""",
                    """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}""",
                    """{"type":"message_stop"}""",
                )
                events.forEach { event ->
                    output.write("data: $event\n\n".toByteArray())
                    output.flush()
                }
            }
        }
        server.start()
        try {
            val deltas = mutableListOf<LocalModelDelta>()
            val reply = client.complete(
                route = route("http://127.0.0.1:${server.address.port}/v1"),
                messages = listOf(
                    LocalCanonicalMessage(
                        LocalCanonicalRole.USER,
                        listOf(LocalCanonicalContent.Text("读取文件")),
                    ),
                ),
                tools = listOf(
                    LocalCanonicalToolDefinition(
                        name = "read",
                        description = "读取文件",
                        parameters = buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("path", buildJsonObject { put("type", "string") })
                            })
                        },
                    ),
                ),
                temperature = null,
                streaming = true,
                onDelta = deltas::add,
            )

            assertTrue(requestBody.get().contains("\"stream\":true"))
            assertEquals("完成", reply.content)
            assertEquals("分析", reply.reasoning)
            assertEquals("read", reply.toolCalls.single().name)
            assertEquals("a.txt", reply.toolCalls.single().arguments["path"]!!.jsonPrimitive.content)
            assertEquals(10L, reply.usage.promptTokens)
            assertEquals(2L, reply.usage.cacheHitTokens)
            assertEquals(8L, reply.usage.cacheMissTokens)
            assertEquals(7L, reply.usage.completionTokens)
            assertTrue(deltas.any { it.content == "完成" })
            assertTrue(deltas.any { it.reasoning == "分析" })
            assertNotNull(reply.message[LOCAL_MODEL_REPLAY_KEY])
            val native = reply.canonicalMessage!!.replay!!.payload["content"]!!.jsonArray
            val thinking = native[1].jsonObject
            assertEquals("sig-1", thinking["signature"]!!.jsonPrimitive.content)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationClosesAnthropicStreamAfterHeaders() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        val hold = CountDownLatch(1)
        val arrived = CompletableDeferred<Unit>()
        server.createContext("/v1/messages") { exchange ->
            try {
                exchange.requestBody.readBytes()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    val frames = listOf(
                        """{"type":"message_start","message":{"id":"msg-2","usage":{"input_tokens":1,"output_tokens":0}}}""",
                        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"start"}}""",
                    )
                    frames.forEach { frame ->
                        output.write("data: $frame\n\n".toByteArray())
                        output.flush()
                    }
                    hold.await(5, TimeUnit.SECONDS)
                }
            } catch (_: java.io.IOException) {
                // Client cancellation is expected to close the socket.
            }
        }
        server.start()
        try {
            val job = launch {
                client.complete(
                    route = route("http://127.0.0.1:${server.address.port}/v1"),
                    messages = listOf(
                        LocalCanonicalMessage(
                            LocalCanonicalRole.USER,
                            listOf(LocalCanonicalContent.Text("test")),
                        ),
                    ),
                    tools = emptyList(),
                    temperature = null,
                    streaming = true,
                    onDelta = { delta ->
                        if (delta.content == "start") arrived.complete(Unit)
                    },
                )
            }
            withTimeout(3_000) { arrived.await() }
            withTimeout(1_500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1L, hold.count)
        } finally {
            hold.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun fixture(events: List<String>, failClose: Boolean = false): AnthropicMessagesClient {
        val text = events.joinToString("\n\n") { "data: $it" } + "\n\n"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val delegate = text.toResponseBody("text/event-stream".toMediaType())
            val body = if (!failClose) delegate else object : ResponseBody() {
                private val stream = object : ForwardingSource(delegate.source()) {
                    override fun close() { super.close(); throw java.io.IOException("late close reset") }
                }.buffer()
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun source() = stream
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        return AnthropicMessagesClient(http, json)
    }

    @Test
    fun terminalSuccessSurvivesCloseResetAndMissingTerminalNeverSucceeds() = runBlocking {
        val start = """{"type":"message_start","message":{"id":"msg","usage":{"input_tokens":2,"output_tokens":0}}}"""
        val delta = """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}"""
        val stop = """{"type":"message_stop"}"""
        val reply = fixture(listOf(start, delta, stop), failClose = true).complete(route(), emptyList(), emptyList(), null, true)
        assertEquals(3L, reply.usage.completionTokens)
        listOf(
            listOf(start, delta) to "ANTHROPIC_STREAM_INTERRUPTED_AFTER_ADMISSION",
            listOf(start, """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}""") to "ANTHROPIC_PROTOCOL_ERROR",
            listOf(start, """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"private"}}""", """{"type":"content_block_stop","index":0}""", delta, stop) to "ANTHROPIC_PROTOCOL_ERROR",
            listOf(start, stop) to "MODEL_FINISH_null",
            listOf(start, """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""") to "ANTHROPIC_STREAM_ERROR",
            listOf(start, """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""", stop) to "MODEL_OUTPUT_TRUNCATED",
            listOf(start, """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"read","input":[]}}""", """{"type":"content_block_stop","index":0}""", """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""", stop) to "ANTHROPIC_PROTOCOL_ERROR",
        ).forEach { (frames, code) ->
            val error = runCatching {
                fixture(frames).complete(route(), emptyList(), emptyList(), null, true)
            }.exceptionOrNull() as? com.labteto.dshmobile.local.LocalModelException
            assertEquals(code, error?.code)
            assertFalse("HTTP 成功接收后的失败不得触发整轮重放：$code", error?.retryable ?: true)
        }
    }

    @Test
    fun canonicalTextWinsWhileThinkingSignatureAndParallelResultsSurvive() {
        val currentRoute = route()
        val assistant = LocalCanonicalMessage(LocalCanonicalRole.ASSISTANT,
            listOf(LocalCanonicalContent.Text("filtered")),
            LocalModelReplayEnvelope(LocalModelAdapterIds.ANTHROPIC_MESSAGES, currentRoute.fingerprint,
                Json.parseToJsonElement("""{"content":[{"type":"thinking","thinking":"private","signature":"sig"},{"type":"text","text":"original"}]}""").jsonObject))
        val results = listOf("a", "b").map { LocalCanonicalMessage(LocalCanonicalRole.TOOL, listOf(LocalCanonicalContent.ToolResult(it, "done"))) }
        val turns = client.buildPayload(currentRoute, listOf(assistant) + results, emptyList(), null)["messages"]!!.jsonArray
        assertEquals(2, turns.size)
        assertEquals(2, turns[1].jsonObject["content"]!!.jsonArray.size)
        assertEquals("sig", turns[0].jsonObject["content"]!!.jsonArray[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertTrue(turns.toString().contains("filtered"))
        assertFalse(turns.toString().contains("original"))
        val audio = LocalCanonicalMessage(LocalCanonicalRole.USER, listOf(LocalCanonicalContent.Raw(Json.parseToJsonElement("""{"type":"input_audio"}""").jsonObject)))
        assertTrue(runCatching { client.buildPayload(currentRoute, listOf(audio), emptyList(), null) }.isFailure)
    }

    @Test
    fun outOfOrderParallelToolBlocksKeepIdentityAndRejectDuplicateIndexes() = runBlocking {
        val start = """{"type":"message_start","message":{"id":"msg","usage":{"input_tokens":1}}}"""
        val first = """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"b","name":"read","input":{}}}"""
        val second = """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"a","name":"read","input":{}}}"""
        val end = listOf("""{"type":"content_block_stop","index":0}""", """{"type":"content_block_stop","index":1}""", """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":2}}""", """{"type":"message_stop"}""")
        val reply = fixture(listOf(start, first, second) + end).complete(route(), emptyList(), emptyList(), null, true)
        assertEquals(listOf("a", "b"), reply.toolCalls.map { it.id })
        val duplicate = runCatching { fixture(listOf(start, first, first) + end).complete(route(), emptyList(), emptyList(), null, true) }.exceptionOrNull()
        assertEquals("ANTHROPIC_PROTOCOL_ERROR", (duplicate as? com.labteto.dshmobile.local.LocalModelException)?.code)
    }

    @Test
    fun officialImageAndWholeRequestLimitsFailBeforeNetwork() = runBlocking {
        val exact = "data:image/png;base64," + "A".repeat(10_000_000)
        val image = LocalCanonicalMessage(LocalCanonicalRole.USER, listOf(LocalCanonicalContent.Image(exact)))
        assertEquals(7_500_000L, com.labteto.dshmobile.local.LocalModelPresets.maxNativeImageBytesFor("claude-sonnet-5-5", "https://api.anthropic.com/v1"))
        assertTrue(client.buildPayload(route(), listOf(image), emptyList(), null).isNotEmpty())
        val tooLargeImage = image.copy(content = listOf(LocalCanonicalContent.Image(exact + "AAAA")))
        assertTrue(runCatching { client.buildPayload(route(), listOf(tooLargeImage), emptyList(), null) }.isFailure)
        val totalError = runCatching { fixture(emptyList()).complete(route(), listOf(image, image, image, image), emptyList(), null, true) }.exceptionOrNull()
        assertEquals("MODEL_REQUEST_TOO_LARGE", (totalError as? com.labteto.dshmobile.local.LocalModelException)?.code)
    }
}
