package com.labteto.dshmobile.local.model

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStreamLineReaderTest {
    private fun stream(text: String, lineLimit: Int, byteLimit: Int) =
        ModelStreamLineReader(BufferedReader(StringReader(text)), lineLimit, byteLimit)

    @Test fun sseReaderReassemblesMultilineJsonIgnoresHeadersAndRetainsFinalFrame() {
        val input = "event: message_start\r\n: ping\r\ndata: {\"type\":\"message_start\",\r\n" +
            "data: \"message\":{\"id\":\"msg\"}}\r\n\r\n" +
            "event: message_stop\n" +
            "data: {\"type\":\"message_stop\"}"
        val events = ModelSseDataReader(stream(input, 200, 500))
        assertEquals("{\"type\":\"message_start\",\n\"message\":{\"id\":\"msg\"}}", events.readData())
        assertEquals("{\"type\":\"message_stop\"}", events.readData())
        assertNull(events.readData())
    }

    @Test fun adjacentJsonEventsWithoutBlankSeparatorsRemainSeparate() {
        val source = "data: {\"type\":\"response.created\"}\n" +
            "data: {\"type\":\"response.completed\"}\n" +
            "data: [DONE]\n"
        val frames = ModelSseDataReader(stream(source, 300, 1_000))
        assertEquals("""{"type":"response.created"}""", frames.readData())
        assertEquals("""{"type":"response.completed"}""", frames.readData())
        assertEquals("[DONE]", frames.readData())
        assertNull(frames.readData())
    }

    @Test fun nonSseJsonBodyIsAvailableToChatCompletionsFallback() {
        val original = """{"choices":[{"message":{"content":"OK"}}]}"""
        val forwarded = StringBuilder()
        val frames = ModelSseDataReader(
            stream(original, 300, 500),
            onNonEventLine = { forwarded.append(it) },
        )
        assertNull(frames.readData())
        assertEquals(original, forwarded.toString())
    }

    @Test fun crLfAndEofPreserveProviderFraming() {
        val reader = stream("data: one\r\ndata: two", 100, 100)
        assertEquals("data: one", reader.readLine())
        assertEquals("data: two", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test fun countsUtf8BytesRatherThanCharacters() {
        val reader = stream("中\na\n", 4, 5)
        assertEquals("中", reader.readLine())
        val error = runCatching { reader.readLine() }.exceptionOrNull()
        assertTrue(error is ModelStreamLimitException)
        assertEquals(ModelStreamLimitKind.STREAM, (error as ModelStreamLimitException).kind)
    }

    @Test fun distinguishesLineLimitFromTotalSizeLimit() {
        val line = runCatching { stream("abcdef\n", 5, 100).readLine() }.exceptionOrNull()
        assertEquals(ModelStreamLimitKind.LINE, (line as ModelStreamLimitException).kind)
        val reader = stream("a\nb\n", 20, 3)
        assertEquals("a", reader.readLine())
        val total = runCatching { reader.readLine() }.exceptionOrNull()
        assertEquals(ModelStreamLimitKind.STREAM, (total as ModelStreamLimitException).kind)
    }

    @Test fun acceptsExactSizeAndEmptyEof() {
        val reader = stream("a\nb", 2, 4)
        assertEquals("a", reader.readLine())
        assertEquals("b", reader.readLine())
        assertNull(reader.readLine())
        assertNull(stream("", 1, 1).readLine())
    }
}
