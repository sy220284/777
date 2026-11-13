package com.labteto.dshmobile.interop.mcp

import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.source
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpLegacySseLifecycleTest {
    @Test
    fun deprecatedSseReceivesMultilineResponseWithoutWaitingForEof() = runBlocking {
        withServer(closeOnTool = false) { transport ->
            val response = withTimeout(3_000) { transport.request("tools/list") }
            assertEquals("1", response["result"]!!.jsonObject["count"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun crossOriginEndpointIsRejectedBeforeJsonRpcPost() = runBlocking {
        var posts = 0
        withServer(
            closeOnTool = false,
            endpointData = "http://attacker.example/post",
            onPost = { posts++ },
        ) { transport ->
            val failure = runCatching {
                withTimeout(3_000) { transport.request("tools/list") }
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("同源"))
            assertEquals(0, posts)
        }
    }

    @Test
    fun persistentStreamEofFailsPendingRequestImmediately() = runBlocking {
        withServer(closeOnTool = true) { transport ->
            val failure = runCatching { withTimeout(3_000) { transport.request("tools/list") } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("连接已结束"))
        }
    }

    private suspend fun withServer(
        closeOnTool: Boolean,
        endpointData: String = "/post",
        onPost: () -> Unit = {},
        test: suspend (McpTransport) -> Unit,
    ) {
        val input = PipedInputStream(16_384)
        val output = PipedOutputStream(input)
        val source = input.source().buffer()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") {
                output.write("event: endpoint\ndata: $endpointData\n\n".toByteArray())
                output.flush()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                    .header("Content-Type", "text/event-stream").body(object : ResponseBody() {
                        override fun contentType(): MediaType? = null
                        override fun contentLength() = -1L
                        override fun source() = source
                    }).build()
            } else {
                onPost()
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                val payload = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                val method = payload["method"]!!.jsonPrimitive.content
                val id = payload["id"]?.jsonPrimitive?.content
                when (method) {
                    "initialize" -> output.write(("data: {\"id\":$id,\"result\":{\"protocolVersion\":\"2024-11-05\"}}\n\n").toByteArray())
                    "tools/list" -> if (closeOnTool) output.close() else output.write(
                        ("data: {\"id\":$id,\ndata: \"result\":{\"count\":1}}\n\n").toByteArray(),
                    )
                }
                if (!closeOnTool || method != "tools/list") output.flush()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(202).message("accepted")
                    .body("".toResponseBody()).build()
            }
        }.build()
        val transport = McpLegacyHttpSseTransport("http://example.com/sse", http, Json)
        try { test(transport) } finally {
            transport.close()
            input.close()
            output.close()
            http.dispatcher.executorService.shutdownNow()
        }
    }
}
