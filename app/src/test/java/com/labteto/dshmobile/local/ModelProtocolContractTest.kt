package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.OpenAiResponsesClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ModelProtocolContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val emptyTools = JsonArray(emptyList())
    private val messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") })

    private fun chat(body: String) = DeepSeekClient(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("text/event-stream".toMediaType())).build()
    }.build(), json)

    @Test fun errorFrameAndTruncatedStreamNeverBecomeSuccessfulReplies() = runBlocking {
        listOf(
            "data: {\"error\":{\"message\":\"quota exhausted\"}}\n\ndata: [DONE]\n\n" to "MODEL_STREAM_ERROR",
            "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n" to "MODEL_OUTPUT_TRUNCATED",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\ndata: [DONE]\n\n" to "MODEL_FINISH_content_filter",
        ).forEach { (body, code) ->
            val error = runCatching { chat(body).completeStreaming("test", "https://test.example/v1", "test", messages, emptyTools) }.exceptionOrNull()
            assertEquals(code, (error as? LocalModelException)?.code)
        }
    }

    @Test fun nonStreamingTruncationAlsoFails() {
        val error = runCatching { chat("").parse("""{"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"partial"}}]}""") }.exceptionOrNull()
        assertEquals("MODEL_OUTPUT_TRUNCATED", (error as? LocalModelException)?.code)
    }

    @Test fun streamedToolMetadataSurvivesOutOfOrderParallelCallsAndContinuation() = runBlocking {
        val body = listOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call-b","function":{"name":"read","arguments":"{}"},"extra_content":{"google":{"thought_signature":"sig-b"}}},{"index":0,"id":"call-a","function":{"name":"read","arguments":"{"},"extra_content":{"google":{"thought_signature":"sig-a"}}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"},"extra_content":{"google":{"other":"kept"}}}]},"finish_reason":"tool_calls"}]}""",
            "data: [DONE]",
        ).joinToString("\n\n")
        val reply = chat(body).completeStreaming("test", "https://test.example/v1", "test", messages, emptyTools)
        val calls = reply.message["tool_calls"]!!.jsonArray
        assertEquals(listOf("call-a", "call-b"), reply.toolCalls.map { it.id })
        assertEquals("sig-a", calls[0].jsonObject["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content)
        assertEquals("sig-b", calls[1].jsonObject["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content)
        assertEquals("kept", calls[0].jsonObject["extra_content"]!!.jsonObject["google"]!!.jsonObject["other"]!!.jsonPrimitive.content)
    }

    @Test fun responsesRejectMalformedOrMissingToolIdentityAndArguments() {
        val client = OpenAiResponsesClient(OkHttpClient(), json)
        listOf(
            """{"type":"function_call","call_id":"c","name":"read","arguments":"{broken"}""",
            """{"type":"function_call","call_id":"c","name":"read","arguments":"[]"}""",
            """{"type":"function_call","name":"read","arguments":"{}"}""",
            """{"type":"function_call","call_id":"c","name":"","arguments":"{}"}""",
            """{"type":"function_call","call_id":"c","name":"read"}""",
        ).forEach { call ->
            val error = runCatching { client.parseCompleted(json.parseToJsonElement("{\"output\":[$call]}").jsonObject, TokenPromptBreakdown()) }.exceptionOrNull()
            assertEquals("RESPONSES_PROTOCOL_ERROR", (error as? LocalModelException)?.code)
        }
    }

    @Test fun responsesCanonicalReplyReplacesNativeTextButRetainsReasoningAndCallOrder() {
        val client = OpenAiResponsesClient(OkHttpClient(), json)
        val history = json.parseToJsonElement("""{
          "role":"assistant","content":"filtered text","_dsh_responses_output":[
            {"type":"reasoning","id":"r","encrypted_content":"encrypted"},
            {"type":"message","id":"m","role":"assistant","content":[{"type":"output_text","text":"original text"}]}
          ]
        }""").jsonObject
        val input = client.buildPayload("test", listOf(history), emptyTools, null)["input"]!!.jsonArray
        assertEquals("encrypted", input[0].jsonObject["encrypted_content"]!!.jsonPrimitive.content)
        assertEquals("m", input[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("filtered text", input[1].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(input.toString().contains("original text"))
    }

    @Test fun unsupportedResponsesMediaFailsBeforeAnyRequest() {
        val client = OpenAiResponsesClient(OkHttpClient(), json)
        val user = json.parseToJsonElement("""{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"YWJj","format":"wav"}}]}""").jsonObject
        val error = runCatching { client.buildPayload("test", listOf(user), emptyTools, null) }.exceptionOrNull()
        assertEquals("RESPONSES_PROTOCOL_ERROR", (error as? LocalModelException)?.code)
    }

    @Test fun cancellationClosesChatAndResponsesStreamsAfterHeaders() = runBlocking {
        listOf(false, true).forEach { responses ->
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val executor = Executors.newSingleThreadExecutor()
            server.executor = executor
            val hold = CountDownLatch(1)
            val arrived = CompletableDeferred<Unit>()
            server.createContext("/") { exchange ->
                try {
                    exchange.requestBody.readBytes()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        val frame = if (responses) """{"type":"response.output_text.delta","delta":"start"}"""
                            else """{"choices":[{"delta":{"content":"start"}}]}"""
                        output.write("data: $frame\n\n".toByteArray()); output.flush()
                        hold.await(5, TimeUnit.SECONDS)
                    }
                } catch (_: java.io.IOException) {
                    // Cancellation is expected to close the client socket before the server ends.
                }
            }
            server.start()
            try {
                val base = "http://127.0.0.1:${server.address.port}/v1"
                val job = launch {
                    if (responses) OpenAiResponsesClient(OkHttpClient(), json).completeStreaming(
                        "test", base, "test", messages, emptyTools, planSharing = false, onDelta = { arrived.complete(Unit) },
                    ) else DeepSeekClient(OkHttpClient(), json).completeStreaming(
                        "test", base, "test", messages, emptyTools, onDelta = { arrived.complete(Unit) },
                    )
                }
                withTimeout(3_000) { arrived.await() }
                withTimeout(1_500) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(1L, hold.count)
            } finally {
                hold.countDown(); server.stop(0); executor.shutdownNow()
            }
        }
    }
}
