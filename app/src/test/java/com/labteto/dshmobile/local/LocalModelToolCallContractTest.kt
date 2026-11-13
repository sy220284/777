package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalCanonicalModelCodec
import com.labteto.dshmobile.local.model.LocalModelReply
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LocalModelToolCallContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun call(id: JsonElement = JsonPrimitive("call-1"), name: JsonElement = JsonPrimitive("read"), args: JsonElement? = JsonPrimitive("{}")) = buildJsonObject {
        put("id", id)
        put("type", "function")
        put("function", buildJsonObject { put("name", name); args?.let { put("arguments", it) } })
    }
    private fun message(calls: List<JsonObject>) = buildJsonObject {
        put("role", "assistant"); put("content", JsonNull); put("tool_calls", JsonArray(calls))
    }
    private fun response(calls: List<JsonObject>) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject { put("message", message(calls)); put("finish_reason", "tool_calls") }) })
    }.toString()
    private fun client(body: String) = DeepSeekClient(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("text/event-stream".toMediaType())).build()
    }.build(), json)
    private suspend fun stream(frames: List<JsonObject>): LocalModelReply {
        val body = frames.joinToString("\n") { "data: $it" } +
            "\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\ndata: [DONE]\n"
        return client(body).completeStreaming("test-key", "https://example.com", "custom",
            listOf(buildJsonObject { put("role", "user"); put("content", "test") }), tools = JsonArray(emptyList()))
    }
    private fun frame(vararg calls: JsonObject) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject { put("delta", buildJsonObject { put("tool_calls", JsonArray(calls.toList())) }) }) })
    }
    private fun indexed(call: JsonObject, index: Int) = JsonObject(call + ("index" to JsonPrimitive(index)))

    @Test fun nonStreamingAndHistoryRejectAbsentOrMalformedArgumentsAndIdentity() {
        val invalid = listOf(call(args = null), call(args = JsonNull), call(args = JsonPrimitive(1)),
            call(args = JsonPrimitive("")), call(args = JsonPrimitive("[]")), call(args = JsonPrimitive("{broken")),
            call(id = JsonPrimitive("")), call(id = JsonPrimitive(1)), call(name = JsonPrimitive(" ")), call(name = JsonPrimitive(false)))
        for (bad in invalid) {
            val error = runCatching { client("").parse(response(listOf(bad))) }.exceptionOrNull()
            assertEquals(bad.toString(), "MODEL_RESPONSE_PROTOCOL", (error as? LocalModelException)?.code)
            val historyError = runCatching { LocalCanonicalModelCodec.message(message(listOf(bad))) }.exceptionOrNull()
            assertEquals(bad.toString(), "MODEL_HISTORY_INVALID", (historyError as? LocalModelException)?.code)
        }
    }

    @Test fun streamingRejectsMissingEmptyAndNonObjectArguments() = runBlocking {
        for (args in listOf(null, JsonNull, JsonPrimitive(""), JsonPrimitive("[]"), JsonPrimitive("{broken"), JsonPrimitive(1))) {
            val error = runCatching { stream(listOf(frame(indexed(call(args = args), 0)))) }.exceptionOrNull()
            assertEquals("MODEL_STREAM_PROTOCOL", (error as? LocalModelException)?.code)
        }
    }

    @Test fun explicitEmptyObjectRemainsValidInAllThreePaths() = runBlocking {
        val calls = listOf(call())
        assertEquals("{}", client("").parse(response(calls)).toolCalls.single().rawArguments)
        assertEquals("{}", stream(listOf(frame(indexed(call(), 0)))).toolCalls.single().rawArguments)
        assertEquals("{}", LocalCanonicalModelCodec.canonicalToolCalls(message(calls)).single().rawArguments)
    }

    @Test fun duplicateIdsAreRejectedBeforeAnyToolCanExecute() = runBlocking {
        val duplicate = listOf(call(), call(name = JsonPrimitive("write")))
        assertEquals("MODEL_RESPONSE_PROTOCOL", (runCatching { client("").parse(response(duplicate)) }.exceptionOrNull() as? LocalModelException)?.code)
        assertEquals("MODEL_HISTORY_INVALID", (runCatching { LocalCanonicalModelCodec.message(message(duplicate)) }.exceptionOrNull() as? LocalModelException)?.code)
        val error = runCatching { stream(listOf(frame(indexed(duplicate[0], 0), indexed(duplicate[1], 1)))) }.exceptionOrNull()
        assertEquals("MODEL_STREAM_PROTOCOL", (error as? LocalModelException)?.code)
    }

    @Test fun fragmentsCannotChangeIdentityOrMergeThroughAnInvalidIndex() = runBlocking {
        val invalid = listOf(call(), indexed(call(), -1), indexed(call(), 0).let { JsonObject(it + ("index" to JsonPrimitive("0"))) })
        for (bad in invalid) {
            assertEquals("MODEL_STREAM_PROTOCOL", (runCatching { stream(listOf(frame(bad))) }.exceptionOrNull() as? LocalModelException)?.code)
        }
        for (changed in listOf(call(id = JsonPrimitive("other")), call(name = JsonPrimitive("write")))) {
            val error = runCatching { stream(listOf(frame(indexed(call(args = JsonPrimitive("{")), 0)), frame(indexed(changed, 0)))) }.exceptionOrNull()
            assertEquals("MODEL_STREAM_PROTOCOL", (error as? LocalModelException)?.code)
        }
    }

    @Test fun unorderedAndNullMetadataFragmentsKeepExplicitArgumentsAndCallOrder() = runBlocking {
        val first = indexed(call(id = JsonPrimitive("a"), args = JsonPrimitive("{")), 0)
        val second = indexed(call(id = JsonPrimitive("b")), 1)
        val continuation = buildJsonObject { put("index", 0); put("id", JsonNull); put("function", buildJsonObject { put("name", JsonNull); put("arguments", "}") }) }
        val result = stream(listOf(frame(second, first), frame(continuation)))
        assertEquals(listOf("a", "b"), result.toolCalls.map { it.id })
        assertEquals(listOf("{}", "{}"), result.toolCalls.map { it.rawArguments })
    }
    @Test fun malformedCallContainersAreRejectedInsteadOfDiscarded() = runBlocking {
        val badMessage = JsonObject(message(emptyList()) + ("tool_calls" to buildJsonObject { put("id", "call-1") }))
        val response = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject { put("message", badMessage) }) }) }.toString()
        assertEquals("MODEL_RESPONSE_PROTOCOL", (runCatching { client("").parse(response) }.exceptionOrNull() as? LocalModelException)?.code)
        assertEquals("MODEL_HISTORY_INVALID", (runCatching { LocalCanonicalModelCodec.message(badMessage) }.exceptionOrNull() as? LocalModelException)?.code)
        val malformed = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject { put("delta", buildJsonObject { put("tool_calls", buildJsonObject {}) }) }) }) }
        assertEquals("MODEL_STREAM_PROTOCOL", (runCatching { stream(listOf(malformed)) }.exceptionOrNull() as? LocalModelException)?.code)
    }

}
