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

    @Test
    fun projectionTailAndTraceStayCorrectAcrossRotatedSegments() {
        val directory = Files.createTempDirectory("session-projection-rotated").toFile()
        try {
            val log = SessionEventLog(
                file = directory.resolve("events.jsonl"),
                json = json,
                maxBytes = 700,
                clock = { 1L },
            )
            repeat(30) { index ->
                log.append(
                    if (index == 27) "checkpoint/type" else "test/event",
                    buildJsonObject { put("value", "row-$index-" + "x".repeat(48)) },
                )
            }

            assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("events.jsonl.part-") })
            assertEquals(listOf(28L, 29L), log.snapshotAfter(27L).map { it.sequence })
            assertEquals(27L, requireNotNull(log.latest("checkpoint/type")).sequence)
            val tail = log.tail(3).lineSequence().toList()
            assertEquals(3, tail.size)
            assertTrue(tail.last().contains("\"sequence\":29"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun reverseLookupCanSkipARejectedNewerCheckpoint() {
        val directory = Files.createTempDirectory("session-reverse-checkpoint").toFile()
        try {
            val log = SessionEventLog(
                file = directory.resolve("events.jsonl"),
                json = json,
                maxBytes = 700,
                clock = { 1L },
            )
            log.append("history/checkpoint", buildJsonObject { put("version", 1) })
            repeat(8) { index ->
                log.append("test/event", buildJsonObject { put("value", "row-$index-" + "x".repeat(48)) })
            }
            val newer = log.append("history/checkpoint", buildJsonObject { put("version", 999) })

            assertEquals(newer.sequence, requireNotNull(log.latest("history/checkpoint")).sequence)
            assertEquals(
                0L,
                requireNotNull(
                    log.latest(
                        "history/checkpoint",
                        beforeSequenceExclusive = newer.sequence,
                    ),
                ).sequence,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

}
