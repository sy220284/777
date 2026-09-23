package com.labteto.dshmobile.harness.session

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventProjectionCursorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun exposesLatestSequenceAndTailAfterCheckpoint() {
        val directory = Files.createTempDirectory("session-projection-cursor").toFile()
        try {
            val log = SessionEventLog(
                file = directory.resolve("events.jsonl"),
                json = json,
                maxBytes = 4_096,
                clock = { 1L },
            )

            assertEquals(-1L, log.latestSequence())
            log.append("plan/state", buildJsonObject { put("version", 1) })
            val checkpoint = log.latestSequence()
            log.append("todo/state", buildJsonObject { put("version", 2) })
            log.append("goal/state", buildJsonObject { put("version", 3) })

            assertEquals(2L, log.latestSequence())
            assertEquals(
                listOf("todo/state", "goal/state"),
                log.snapshotAfter(checkpoint).map { it.type },
            )
            assertTrue(log.snapshotAfter(log.latestSequence()).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
