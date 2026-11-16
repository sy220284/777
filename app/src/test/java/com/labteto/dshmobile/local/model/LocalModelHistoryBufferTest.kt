package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun recentReferenceWindowKeepsOnlyTailAndNewestOlderCheckpoint() {
        val buffer = LocalModelHistoryBuffer()
        buffer.append(message("assistant", "old checkpoint"))
        buffer.append(message("assistant", "newest checkpoint"))
        repeat(300) { buffer.append(message("tool", "tool-$it")) }
        val window = buffer.recentWithLatestMatching(80) {
            it["content"]?.toString()?.contains("checkpoint") == true
        }
        assertEquals(81, window.size)
        assertEquals("newest checkpoint", window.first()["content"]?.toString()?.trim('"'))
        assertEquals(buffer.snapshot().takeLast(80), window.drop(1))
        buffer.append(message("assistant", "active checkpoint"))
        val active = buffer.recentWithLatestMatching(80) {
            it["content"]?.toString()?.contains("checkpoint") == true
        }
        assertEquals(80, active.size)
        assertEquals(buffer.snapshot().takeLast(80), active)
        buffer.reset(listOf(message("user", "new session")))
        assertEquals(buffer.snapshot(), buffer.recentWithLatestMatching(80) {
            it["content"]?.toString()?.contains("checkpoint") == true
        })
        assertThrows(IllegalArgumentException::class.java) {
            buffer.recentWithLatestMatching(-1) { false }
        }
        assertTrue(buffer.recentWithLatestMatching(0) { false }.isEmpty())
    }

    private fun assertMetrics(
        buffer: LocalModelHistoryBuffer,
        expected: List<kotlinx.serialization.json.JsonObject>,
    ) {
        assertEquals(expected, buffer.snapshot())
        assertEquals(expected.size, buffer.size)
        assertEquals(expected.sumOf { it.toString().length }, buffer.encodedChars)
        assertEquals(expected.sumOf { estimateModelTokens(it.toString()) }, buffer.estimatedTokens)
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}
