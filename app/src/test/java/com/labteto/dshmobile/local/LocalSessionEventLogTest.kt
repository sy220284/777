package com.labteto.dshmobile.local

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionEventLogTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun rotatesAtWholeJsonLinesWithoutDiscardingHistory() {
        val directory = Files.createTempDirectory("local-event-log").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = LocalSessionEventLog(file, json, maxBytes = 700)
            repeat(20) { index ->
                log.append("test/event", buildJsonObject { put("value", "event-$index-" + "x".repeat(40)) })
            }

            assertTrue(file.length() <= 700)
            assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("session.events.jsonl.part-") })
            val retained = log.snapshot()
            assertEquals(20, retained.size)
            assertEquals(0L, retained.first().sequence)
            assertEquals(19L, retained.last().sequence)

            val restarted = LocalSessionEventLog(file, json, maxBytes = 700)
            restarted.append("test/restarted", buildJsonObject { put("value", "latest") })
            assertEquals(20L, requireNotNull(restarted.latest("test/restarted")).sequence)
            assertEquals(21, restarted.snapshot().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun appendReturnsTheExactDurableSequence() {
        val directory = Files.createTempDirectory("local-event-append-sequence").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = LocalSessionEventLog(file, json, maxBytes = 4_096)
            val baseline = log.append("session/projection-baseline", buildJsonObject { put("source", "legacy") })
            log.append("plan/state", buildJsonObject { put("value", 1) })

            assertEquals(0L, baseline.sequence)
            assertEquals(1L, log.latestSequence())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun exposesLatestTypedEventForRecovery() {
        val directory = Files.createTempDirectory("local-event-latest").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = LocalSessionEventLog(file, json, maxBytes = 4_096)
            log.append("local/model-history-checkpoint", buildJsonObject { put("version", 1) })
            log.append("other", buildJsonObject { put("value", 2) })

            val latest = requireNotNull(log.latest("local/model-history-checkpoint"))
            assertEquals("local/model-history-checkpoint", latest.type)
            assertEquals(1, latest.data["version"]?.toString()?.toInt())
        } finally {
            directory.deleteRecursively()
        }
    }
}
