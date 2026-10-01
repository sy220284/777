package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import com.labteto.dshmobile.local.LocalModelException
import org.junit.Test

class OpenAiResponsesStreamSnapshotTest {
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
}
