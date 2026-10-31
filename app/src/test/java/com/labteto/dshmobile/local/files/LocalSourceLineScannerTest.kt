package com.labteto.dshmobile.local.files

import com.labteto.dshmobile.local.io.NetworkInputTooLargeException
import com.labteto.dshmobile.local.io.readBoundedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalSourceLineScannerTest {
    @Test fun bulkScannerMatchesOriginalReaderAcrossUnicodeAndBlockBoundaries() {
        val text = buildString {
            append("first\r\n\n")
            append("ascii\n")
            append("字😀".repeat(6_000)).append("\n")
            append("x".repeat(32_771)).append("\r\n")
            append("\t").append("\r")
        }
        val original = text.reader().buffered()
        val buffered = text.reader().buffered()
        val scanner = LocalSourceLineScanner(buffered, 100_000)
        while (true) {
            val expected = readBoundedLine(original, 100_000)
            assertEquals(expected, scanner.readLine())
            if (expected == null) break
        }
    }

    @Test fun exactBoundAndEmptyFinalLineFollowOriginalSemantics() {
        val max = 16_384
        val source = "x".repeat(max) + "\nlast\n"
        val scanner = LocalSourceLineScanner(source.reader().buffered(), max)
        assertEquals("x".repeat(max), scanner.readLine())
        assertEquals("last", scanner.readLine())
        assertEquals(null, scanner.readLine())
        val overflowing = LocalSourceLineScanner(("x".repeat(max + 1) + "\n").reader().buffered(), max)
        assertThrows(NetworkInputTooLargeException::class.java) { overflowing.readLine() }
    }

    @Test fun overlongMultiBufferLineThrowsBeforeUnboundedAllocation() {
        val scanner = LocalSourceLineScanner(("z".repeat(50_000)).reader().buffered(), 20_000)
        assertThrows(NetworkInputTooLargeException::class.java) { scanner.readLine() }
    }
}
