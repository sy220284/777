package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalModelHistoryBufferTest {
    @Test
    fun metricsStayInSyncAcrossAppendPrependReplaceAndReset() {
        val system = message("system", "base")
        val user = message("user", "hello")
        val assistant = message("assistant", "world")
        val replacementSystem = message("system", "updated")

        val buffer = LocalModelHistoryBuffer()
        buffer.append(user)
        buffer.prepend(system)
        buffer.append(assistant)

        assertMetrics(buffer, listOf(system, user, assistant))

        buffer.replaceSystem(replacementSystem)
        assertMetrics(buffer, listOf(replacementSystem, user, assistant))

        buffer.reset(listOf(system, assistant))
        assertMetrics(buffer, listOf(system, assistant))
    }

    @Test
    fun replaceSystemRequiresSystemAtHead() {
        val buffer = LocalModelHistoryBuffer()
        buffer.append(message("user", "hello"))

        assertThrows(IllegalArgumentException::class.java) {
            buffer.replaceSystem(message("system", "base"))
        }
    }

    private fun assertMetrics(
        buffer: LocalModelHistoryBuffer,
        expected: List<kotlinx.serialization.json.JsonObject>,
    ) {
        assertEquals(expected, buffer.snapshot())
        assertEquals(expected.sumOf { it.toString().length }, buffer.encodedChars)
        assertEquals(expected.sumOf { estimateModelTokens(it.toString()) }, buffer.estimatedTokens)
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}
