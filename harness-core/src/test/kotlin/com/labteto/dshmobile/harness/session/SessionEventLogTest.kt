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
    fun rotatesOnlyAtCompleteRowsAndContinuesSequenceAfterRestart() {
        val directory = Files.createTempDirectory("harness-event-log").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 700, clock = { 123L })
            repeat(20) { index ->
                log.append("test/event", buildJsonObject { put("value", "event-$index-" + "x".repeat(40)) })
            }

            assertTrue(file.length() <= 700)
            val retained = file.readLines().map { json.decodeFromString(SessionEvent.serializer(), it) }
            assertEquals(19L, retained.last().sequence)
            assertTrue(retained.first().sequence > 0L)

            SessionEventLog(file, json, maxBytes = 700, clock = { 456L })
                .append("test/restarted", buildJsonObject { put("value", "latest") })
            val latest = json.decodeFromString(SessionEvent.serializer(), file.readLines().last())
            assertEquals(20L, latest.sequence)
            assertEquals(456L, latest.createdAt)
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
