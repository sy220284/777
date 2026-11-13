package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionEventSerializationTest {
    @Test
    fun eventFamiliesRetainTheirWireDiscriminatorsAcrossRoundTrips() {
        val payloads = mapOf(
            "turn/end" to """{"turn":2,"reason":{"kind":"aborted","reason":{"kind":"hook","reason":"stop"}}}""",
            "request/header" to """{"reason":"resume","header":{"config":{"provider":"p","model":"m"},"system":"s","tools":[{"name":"t","description":"d","parameters":{"type":"object"}}]}}""",
            "todo/write" to """{"todos":[{"content":"one","status":"completed"}]}""",
            "goal/change" to """{"operation":"clear"}""",
            "tool-workflow/agent-start" to """{"runId":"r","seq":1,"label":"child","childId":"c"}""",
            "subagent/descriptor" to """{"mode":"continuable","version":1,"provider":"p","label":"child","toolFilter":{"allow":["read"]}}""",
            "schedule/change" to """{"operation":"create","version":1,"schedule":{"kind":"every","id":"r","prompt":"p","everySeconds":10,"scheduledAt":"2026-10-03T00:00:00Z"}}""",
            "compaction/summary" to """{"compactionId":"c","summary":[{"type":"text","text":"kept"}],"shadowedRange":{"start":1,"end":4},"shadowedTokenCount":10,"provider":"p","model":"m"}""",
            "approval/asked" to """{"id":"a","toolName":"write","callId":"call"}""",
        )
        payloads.forEach { (type, data) ->
            val raw = Json.parseToJsonElement("""{"type":"$type","seq":7,"time":10,"data":$data}""")
            val event = decodeFromJsonElement(SessionEvent.serializer(), raw)
            assertFalse(type, event is UnknownSessionEvent)
            val encoded = encodeToJsonElement(SessionEvent.serializer(), event)
            assertEquals(JsonPrimitive(type), encoded.jsonObject["type"])
            raw.jsonObject.getValue("data").jsonObject.forEach { (key, value) ->
                assertEquals("$type/$key", value, encoded.jsonObject.getValue("data").jsonObject[key])
            }
            assertEquals(encoded, encodeToJsonElement(SessionEvent.serializer(), decodeFromJsonElement(SessionEvent.serializer(), encoded)))
        }
    }

    @Test
    fun unknownEnvelopeAndNestedContentSurviveLosslessly() {
        val raw = Json.parseToJsonElement("""{"type":"future/notice","seq":2,"time":3,"data":{"custom":[1,null]},"sourceEventSeqs":[1],"surfaceOp":{"kind":"append"},"ignorable":true,"future":"kept"}""")
        val event = decodeFromJsonElement(SessionEvent.serializer(), raw) as UnknownSessionEvent
        assertEquals(listOf(1), event.sourceEventSeqs)
        assertEquals(raw, encodeToJsonElement(SessionEvent.serializer(), event))

        val content = Json.parseToJsonElement("""{"type":"tool-result","toolCallId":"c","content":[{"type":"reasoning","text":"r"},{"type":"future-block","opaque":{"x":1}}]}""")
        val block = decodeFromJsonElement(ContentBlock.serializer(), content)
        assertEquals(content, encodeToJsonElement(ContentBlock.serializer(), block))
        val stream = Json.parseToJsonElement("""{"type":"block-end","index":0,"block":$content}""")
        assertEquals(stream, encodeToJsonElement(StreamChunk.serializer(), decodeFromJsonElement(StreamChunk.serializer(), stream)))
    }
}
