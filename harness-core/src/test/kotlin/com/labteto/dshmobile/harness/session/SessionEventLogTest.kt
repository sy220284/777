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
    fun restartRecoversSequenceFromNewestValidTailWithoutReplayingHistory() {
        val directory = Files.createTempDirectory("harness-event-tail-recovery").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 700, clock = { 1L })
            repeat(18) { index ->
                log.append("test/event", buildJsonObject { put("value", "row-$index-" + "x".repeat(48)) })
            }
            val expectedNext = log.snapshot().last().sequence + 1L

            // Simulate a torn final write. Startup must skip it and recover from the newest
            // complete row without requiring a full historical replay.
            file.appendText("{\"sequence\":999")
            val restarted = SessionEventLog(file, json, maxBytes = 700, clock = { 2L })
            val appended = restarted.append("test/restarted", buildJsonObject { put("value", "ok") })

            assertEquals(expectedNext, appended.sequence)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun restartFallsBackToPreviousSegmentWhenActiveFileHasNoValidRow() {
        val directory = Files.createTempDirectory("harness-event-tail-fallback").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 700, clock = { 1L })
            repeat(12) { index ->
                log.append("test/event", buildJsonObject { put("value", "row-$index-" + "x".repeat(52)) })
            }
            val newestSegment = directory.listFiles().orEmpty()
                .filter { it.name.startsWith("session.events.jsonl.part-") }
                .maxByOrNull { it.name }
                ?: error("expected at least one rotated segment")
            val previousSequence = newestSegment.readLines()
                .mapNotNull { line ->
                    runCatching { json.decodeFromString(SessionEvent.serializer(), line).sequence }.getOrNull()
                }
                .maxOrNull()
                ?: error("expected a valid event in rotated segment")

            file.writeText("{broken tail only")

            val restarted = SessionEventLog(file, json, maxBytes = 700, clock = { 2L })
            val appended = restarted.append("test/restarted", buildJsonObject { put("value", "ok") })
            assertEquals(previousSequence + 1L, appended.sequence)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectedOversizedEventDoesNotConsumeSequence() {
        val directory = Files.createTempDirectory("harness-event-sequence").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 512, clock = { 1L })
            runCatching {
                log.append("too-large", buildJsonObject { put("value", "x".repeat(2_000)) })
            }
            val accepted = log.append("accepted", buildJsonObject { put("value", "ok") })
            assertEquals(0L, accepted.sequence)
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

    @Test
    fun tailAndPointReadRemainCorrectAcrossRotatedSegments() {
        val directory = Files.createTempDirectory("harness-event-window").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 700, clock = { 1L })
            repeat(30) { index ->
                log.append("test/event", buildJsonObject { put("value", "row-$index-" + "x".repeat(44)) })
            }

            val tail = log.tail(3).lineSequence()
                .map { json.decodeFromString(SessionEvent.serializer(), it).sequence }
                .toList()
            assertEquals(listOf(27L, 28L, 29L), tail)

            val window = log.read(sequence = 15L, before = 2, after = 2).lineSequence()
                .map { json.decodeFromString(SessionEvent.serializer(), it).sequence }
                .toList()
            assertEquals(listOf(13L, 14L, 15L, 16L, 17L), window)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun pageBeforeReadsOnlyTheRequestedOlderWindowAcrossSegments() {
        val directory = Files.createTempDirectory("harness-event-page-before").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 700, clock = { 1L })
            repeat(30) { index ->
                log.append(
                    "test/event",
                    buildJsonObject { put("value", "row-$index-" + "x".repeat(44)) },
                )
            }
            file.appendText("{broken tail\n")

            assertEquals(
                listOf(27L, 28L, 29L),
                log.pageBefore(limit = 3).map(SessionEvent::sequence),
            )
            assertEquals(
                listOf(12L, 13L, 14L),
                log.pageBefore(sequenceExclusive = 15L, limit = 3).map(SessionEvent::sequence),
            )
            assertEquals(
                listOf(0L, 1L),
                log.pageBefore(sequenceExclusive = 2L, limit = 20).map(SessionEvent::sequence),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun latestOfReturnsNewestMatchingRelevantType() {
        val directory = Files.createTempDirectory("harness-event-latest-of").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 700, clock = { 1L })
            repeat(16) { index ->
                val type = when (index) {
                    3 -> "tool/call"
                    11 -> "user/message"
                    else -> "checkpoint"
                }
                log.append(type, buildJsonObject { put("value", index) })
            }

            val latest = requireNotNull(log.latestOf(setOf("user/message", "tool/call", "tool/result")))
            assertEquals(11L, latest.sequence)
            assertEquals("user/message", latest.type)
        } finally {
            directory.deleteRecursively()
        }
    }

}
