package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import com.labteto.dshmobile.local.LocalModelException
import org.junit.Test

class OpenAiResponsesStreamSnapshotTest {
    @Test fun nonCompletedProviderStatesCannotAuthorizeAnOtherwiseValidToolCall() {
        val client = OpenAiResponsesClient(okhttp3.OkHttpClient(), Json)
        for (status in listOf("failed", "cancelled", "canceled", "queued", "in_progress")) {
            val response = event("""{"status":"$status","output":[{"type":"function_call","call_id":"call-a","name":"write","arguments":"{}"}]}""")
            val reply = client.parseCompleted(response, com.labteto.dshmobile.local.TokenPromptBreakdown())
            assertEquals(LocalModelCompletionReason.INTERRUPTED, reply.completionReason)
            assertEquals(emptyList<LocalToolCall>(), reply.toolCalls)
        }
        assertThrows(LocalModelException::class.java) {
            client.parseCompleted(event("""{"status":"unknown-new-state","output":[]}"""),
                com.labteto.dshmobile.local.TokenPromptBreakdown())
        }
    }

    @Test fun completedSnapshotCannotResurrectOmittedToolsOrLongerPreviewText() {
        val stream = OpenAiResponsesStreamSnapshot()
        stream.record(event("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"preview tail"}"""))
        stream.record(event("""{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","id":"fc-old","call_id":"call-old","name":"write","arguments":"{}"}}"""))
        val terminal = event("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"preview"}]}]}""")
        assertEquals(terminal, stream.settledResponse(terminal))
    }

    @Test fun malformedTerminalOutputCannotTurnAStoredToolItemIntoAValidResponse() {
        val stream = OpenAiResponsesStreamSnapshot()
        stream.record(event("""{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"call-old","name":"write","arguments":"{}"}}"""))
        assertThrows(LocalModelException::class.java) {
            stream.settledResponse(event("""{"status":"completed","output":"corrupt"}"""))
        }
    }

    @Test fun explicitlyEmptyFinalTextDoesNotFallBackToPreviewContent() {
        val terminal = event("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":""}]}]}""")
        val failure = assertThrows(LocalModelException::class.java) {
            OpenAiResponsesClient(okhttp3.OkHttpClient(), Json).parseCompleted(terminal,
                com.labteto.dshmobile.local.TokenPromptBreakdown(), streamedContent = "preview", streamedReasoning = "preview thought")
        }
        assertEquals("MODEL_EMPTY_RESPONSE", failure.code)
    }

    @Test
    fun missingDeltaIsRecoveredFromDoneWithoutRepeatingAlreadyReceivedText() {
        val stream = OpenAiResponsesStreamSnapshot()
        stream.record(event("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"hel"}"""))
        stream.record(event("""{"type":"response.output_text.done","output_index":0,"content_index":0,"text":"hello"}"""))
        stream.record(event("""{"type":"response.output_item.done","output_index":0,"item":{"type":"message","content":[{"type":"output_text","text":"hello"}]}}"""))
        assertEquals("hello", stream.content)
        assertEquals(1, stream.settledResponse(event("""{"output":[]}"""))["output"]!!.jsonArray.size)
    }

    @Test
    fun unorderedDifferentPartsSettleInOutputAndContentOrder() {
        val stream = OpenAiResponsesStreamSnapshot()
        listOf(
            """{"type":"response.output_text.done","output_index":1,"content_index":0,"text":"C"}""",
            """{"type":"response.output_text.done","output_index":0,"content_index":1,"text":"B"}""",
            """{"type":"response.content_part.done","output_index":0,"content_index":0,"part":{"type":"output_text","text":"A"}}""",
        ).forEach { stream.record(event(it)) }
        assertEquals("ABC", stream.content)
    }

    @Test
    fun refusalAndReasoningDoneRecoverMissingDeltas() {
        val stream = OpenAiResponsesStreamSnapshot()
        stream.record(event("""{"type":"response.refusal.done","refusal":"cannot comply"}"""))
        stream.record(event("""{"type":"response.reasoning_summary_text.done","text":"thought"}"""))
        assertEquals("cannot comply", stream.content)
        assertEquals("thought", stream.reasoningText)
    }

    @Test
    fun itemDoneRetainsToolIdentityArgumentsAndNamespaceForEmptyCompletedOutput() {
        val stream = OpenAiResponsesStreamSnapshot()
        val item = event("""{"type":"function_call","id":"fc-a","call_id":"call-a","name":"read","namespace":"local","arguments":"{}"}""")
        stream.record(JsonObject(event("""{"type":"response.output_item.done","output_index":0}""") + ("item" to item)))
        assertEquals(item, stream.settledResponse(event("""{"output":[]}"""))["output"]!!.jsonArray.single())
        val authoritative = event("""{"output":[{"type":"message","content":[{"type":"output_text","text":"final"}]}]}""")
        assertEquals(authoritative, stream.settledResponse(authoritative))
    }

    @Test
    fun malformedSnapshotCannotOverwriteARealTextPartOrHideAnIndexCollision() {
        listOf(
            """{"type":"response.output_text.done","text":123}""",
            """{"type":"response.output_text.done","output_index":-1,"text":"bad"}""",
            """{"type":"response.output_text.done","content_index":"0","text":"bad"}""",
        ).forEach { raw ->
            assertThrows(LocalModelException::class.java) { OpenAiResponsesStreamSnapshot().record(event(raw)) }
        }
    }

    private fun event(value: String) = Json.parseToJsonElement(value) as JsonObject
    @Test
    fun incompleteSnapshotKeepsMissingPartsAndDoesNotDuplicatePrefix() {
        val stream = OpenAiResponsesStreamSnapshot()
        stream.record(event("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"第一段完整正文"}"""))
        stream.record(event("""{"type":"response.output_text.delta","output_index":0,"content_index":1,"delta":"第二段"}"""))
        val settled = stream.settledResponse(event("""{"status":"incomplete","output":[{"type":"message","content":[{"type":"output_text","text":"第一段"}]}]}"""))
        val reply = OpenAiResponsesClient(okhttp3.OkHttpClient(), Json).parseCompleted(settled,
            com.labteto.dshmobile.local.TokenPromptBreakdown())
        assertEquals("第一段完整正文第二段", reply.content)
        assertEquals(LocalModelCompletionReason.INTERRUPTED, reply.completionReason)
    }

    @Test
    fun everyDoneSnapshotPreservesLongerAcceptedPrefixBeforeTerminalMerge() {
        listOf(
            """{"type":"response.output_text.done","output_index":0,"content_index":0,"text":"abc"}""",
            """{"type":"response.content_part.done","output_index":0,"content_index":0,"part":{"type":"output_text","text":"abc"}}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","content":[{"type":"output_text","text":"abc"}]}}""",
        ).forEach { done ->
            val stream = OpenAiResponsesStreamSnapshot()
            stream.record(event("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"abcdef"}"""))
            stream.record(event(done))
            assertEquals("abcdef", stream.content)
            val settled = stream.settledResponse(event("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}"""))
            val reply = OpenAiResponsesClient(okhttp3.OkHttpClient(), Json).parseCompleted(settled,
                com.labteto.dshmobile.local.TokenPromptBreakdown())
            assertEquals("abcdef", reply.content)
            assertEquals(LocalModelCompletionReason.OUTPUT_LIMIT, reply.completionReason)
        }
    }

}
