package com.labteto.dshmobile.interop.lsp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class LspFramingTest {
    @Test
    fun frameRoundTripsUtf8Payload() {
        val sink = ByteArrayOutputStream()
        BufferedOutputStream(sink).use { output ->
            LspFraming.write(output, """{"jsonrpc":"2.0","text":"中文"}""")
        }
        val source = BufferedInputStream(ByteArrayInputStream(sink.toByteArray()))
        assertEquals("""{"jsonrpc":"2.0","text":"中文"}""", LspFraming.read(source))
    }
}
