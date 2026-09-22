package com.labteto.dshmobile.harness.session

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventLogTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun rotatesWithoutDiscardingRowsAndContinuesSequenceAfterRestart() {
        val directory = Files.createTempDirectory("harness-event-log").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 700, clock = { 123L })
            repeat(20) { index ->
                log.append("test/event", buildJsonObject { put("value", "event-$index-" + "x".repeat(40)) })
            }

            assertTrue(file.length() <= 700)
            assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("session.events.jsonl.part-") })
            val retained = log.snapshot()
            assertEquals(20, retained.size)
            assertEquals(0L, retained.first().sequence)
            assertEquals(19L, retained.last().sequence)

            val restarted = SessionEventLog(file, json, maxBytes = 700, clock = { 456L })
            restarted.append("test/restarted", buildJsonObject { put("value", "latest") })
            val latest = requireNotNull(restarted.latest("test/restarted"))
            assertEquals(20L, latest.sequence)
            assertEquals(456L, latest.createdAt)
            assertEquals(21, restarted.snapshot().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun latestTypedEventSkipsMalformedRowsAndKeepsNewestCompleteMatch() {
        val directory = Files.createTempDirectory("harness-event-latest").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 4_096, clock = { 1L })
            log.append("checkpoint", buildJsonObject { put("value", "old") })
            log.append("other", buildJsonObject { put("value", "noise") })
            log.append("checkpoint", buildJsonObject { put("value", "new") })
            file.appendText("{broken\n")

            val latest = requireNotNull(log.latest("checkpoint"))
            assertEquals(2L, latest.sequence)
            assertEquals("new", latest.data["value"]?.toString()?.trim('"'))
        } finally {
            directory.deleteRecursively()
        }
    }
}
