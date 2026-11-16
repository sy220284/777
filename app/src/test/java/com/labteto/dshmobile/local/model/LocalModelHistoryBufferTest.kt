package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.context.buildTrustedWorkCheckpointModelMessage
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
        val old = buildTrustedWorkCheckpointModelMessage("old")
        val newest = buildTrustedWorkCheckpointModelMessage("newest")
        buffer.append(old)
        buffer.append(newest)
        repeat(300) { buffer.append(message("tool", "tool-$it")) }
        val window = buffer.recentWithLatestWorkCheckpoint(80)
        assertEquals(81, window.size)
        assertEquals(newest, window.first())
        assertEquals(buffer.snapshot().takeLast(80), window.drop(1))
        buffer.append(buildTrustedWorkCheckpointModelMessage("active"))
        assertEquals(buffer.snapshot().takeLast(80), buffer.recentWithLatestWorkCheckpoint(80))
        buffer.reset(listOf(message("user", "new session")))
        assertEquals(buffer.snapshot(), buffer.recentWithLatestWorkCheckpoint(80))
        assertThrows(IllegalArgumentException::class.java) {
            buffer.recentWithLatestWorkCheckpoint(-1)
        }
        assertTrue(buffer.recentWithLatestWorkCheckpoint(0).isEmpty())
    }

    @Test
    fun checkpointIndexFollowsPrependInsertReplaceAndReset() {
        val buffer = LocalModelHistoryBuffer()
        val first = buildTrustedWorkCheckpointModelMessage("first")
        val latest = buildTrustedWorkCheckpointModelMessage("latest")
        buffer.append(first)
        repeat(90) { buffer.append(message("assistant", "old-$it")) }
        buffer.append(latest)
        repeat(90) { buffer.append(message("assistant", "recent-$it")) }
        assertEquals(latest, buffer.recentWithLatestWorkCheckpoint(80).first())
        buffer.prepend(message("system", "base"))
        buffer.insert(1, buildTrustedWorkCheckpointModelMessage("earlier"))
        assertEquals(latest, buffer.recentWithLatestWorkCheckpoint(80).first())
        buffer.replaceSystem(message("system", "changed"))
        assertEquals(latest, buffer.recentWithLatestWorkCheckpoint(80).first())
        buffer.insert(buffer.size, buildTrustedWorkCheckpointModelMessage("after"))
        assertEquals(buffer.snapshot().takeLast(80), buffer.recentWithLatestWorkCheckpoint(80))
        buffer.reset(listOf(message("system", "reset"), first) +
            List(90) { message("assistant", "reset-$it") })
        assertEquals(first, buffer.recentWithLatestWorkCheckpoint(80).first())
        buffer.reset()
        assertTrue(buffer.recentWithLatestWorkCheckpoint(80).isEmpty())
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
