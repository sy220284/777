package com.labteto.dshmobile.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionStoreUploadBoundTest {
    @Test
    fun boundedFallbackReadAcceptsInputWithinLimit() {
        val expected = "hello".toByteArray()
        val actual = ByteArrayInputStream(expected).use { it.readBytesAtMost(expected.size) }

        assertArrayEquals(expected, actual)
    }

    @Test
    fun boundedFallbackReadRejectsActualInputBeyondDeclaredGuard() {
        val input = ByteArrayInputStream(ByteArray(9) { it.toByte() })

        assertThrows(IllegalStateException::class.java) {
            input.use { it.readBytesAtMost(8) }
        }
    }
}
