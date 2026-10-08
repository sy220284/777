package com.labteto.dshmobile.local.io

import java.io.BufferedReader
import java.io.StringReader
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedNetworkInputTest {
    @Test
    fun lineReaderHandlesCrLfAndEof() {
        val reader = BufferedReader(StringReader("data: ok\r\nlast"))
        assertEquals("data: ok", readBoundedLine(reader, 10))
        assertEquals("last", readBoundedLine(reader, 10))
        assertNull(readBoundedLine(reader, 10))
    }

    @Test
    fun singleOverlongLineFailsBeforeReadingTheWholePeerPayload() {
        val reader = BufferedReader(StringReader("x".repeat(100_000)))
        val error = runCatching { readBoundedLine(reader, 32) }.exceptionOrNull()
        assertTrue(error is NetworkInputTooLargeException)
    }

    @Test
    fun responseBodyReadsExactLimitAndRejectsOversize() {
        assertEquals("1234", readBoundedBody("1234".toResponseBody(), 4))
        val error = runCatching { readBoundedBody("12345".toResponseBody(), 4) }.exceptionOrNull()
        assertTrue(error is NetworkInputTooLargeException)
    }
}
