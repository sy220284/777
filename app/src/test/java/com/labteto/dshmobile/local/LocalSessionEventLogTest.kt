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
    fun rotatesAtWholeJsonLinesAndKeepsSequenceMonotonicAcrossRestart() {
        val directory = Files.createTempDirectory("local-event-log").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = LocalSessionEventLog(file, json, maxBytes = 700)
            repeat(20) { index ->
                log.append("test/event", buildJsonObject { put("value", "event-$index-" + "x".repeat(40)) })
            }

            assertTrue(file.length() <= 700)
            val retained = file.readLines().map {
                json.decodeFromString(LocalSessionEventLog.Event.serializer(), it)
            }
            assertTrue(retained.isNotEmpty())
            assertEquals(19L, retained.last().sequence)
            assertTrue(retained.first().sequence > 0L)

            LocalSessionEventLog(file, json, maxBytes = 700)
                .append("test/restarted", buildJsonObject { put("value", "latest") })
            val latest = json.decodeFromString(
                LocalSessionEventLog.Event.serializer(),
                file.readLines().last(),
            )
            assertEquals(20L, latest.sequence)
        } finally {
            directory.deleteRecursively()
        }
    }
}
