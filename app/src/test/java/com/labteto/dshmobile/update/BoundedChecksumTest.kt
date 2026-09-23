package com.labteto.dshmobile.update

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedChecksumTest {
    @Test fun acceptsExactBoundary() {
        val bytes = "checksum".toByteArray()
        assertArrayEquals(bytes, readChecksumBytes(ByteArrayInputStream(bytes), bytes.size.toLong()))
    }
    @Test fun stopsAtOneByteBeyondLimitWithoutReadingRemainder() {
        val input = ByteArrayInputStream(ByteArray(100))
        assertTrue(runCatching { readChecksumBytes(input, 8) }.isFailure)
        assertEquals(91, input.available())
    }
}
