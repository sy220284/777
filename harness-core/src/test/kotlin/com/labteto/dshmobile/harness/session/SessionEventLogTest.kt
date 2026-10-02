package com.labteto.dshmobile.harness.session

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            val diagnostics = mutableListOf<String>()
            val restarted = SessionEventLog(
                file,
                json,
                maxBytes = 700,
                clock = { 2L },
                diagnosticSink = { kind, _ -> diagnostics += kind },
            )
            val appended = restarted.append("test/restarted", buildJsonObject { put("value", "ok") })

            assertEquals(expectedNext, appended.sequence)
            assertEquals(expectedNext, restarted.latest("test/restarted")?.sequence)
            file.readLines().filter(String::isNotBlank).forEach { line ->
                json.decodeFromString(SessionEvent.serializer(), line)
            }
            assertTrue("torn tail should be repaired before append", "torn-tail-truncated" in diagnostics)
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
            val previousSequence = (if (newestSegment.name.endsWith(".gz"))
                GZIPInputStream(newestSegment.inputStream()).bufferedReader().readLines()
            else newestSegment.readLines())
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
    fun archivedSegmentsPreserveHistoryAndMigrateOldRawSegments() {
        val directory = Files.createTempDirectory("harness-event-archive").toFile()
        try {
            val file = directory.resolve("events.jsonl")
            val log = SessionEventLog(file, json, maxBytes = 700)
            repeat(28) { index ->
                log.append("test", buildJsonObject { put("value", "repeat-me-".repeat(6) + index) })
            }
            val archive = directory.listFiles().orEmpty().first { it.name.endsWith(".gz") }
            val raw = File(archive.path.removeSuffix(".gz"))
            GZIPInputStream(archive.inputStream()).use { input -> raw.writeBytes(input.readBytes()) }
            assertTrue(archive.delete()) // Simulate a pre-migration app version.

            val restarted = SessionEventLog(file, json, maxBytes = 700)
            assertEquals(28, restarted.snapshot().size)
            restarted.append("test", buildJsonObject { put("value", "new") })
            assertTrue(!raw.exists())
            assertTrue(File(raw.path + ".gz").isFile)
            assertEquals((0L..28L).toList(), restarted.snapshot().map(SessionEvent::sequence))
            assertEquals(listOf(26L, 27L, 28L), restarted.pageBefore(limit = 3).map(SessionEvent::sequence))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun dormantSessionArchivesLegacySegmentsWithoutAppendingAndKeepsHistory() {
        val directory = Files.createTempDirectory("harness-idle-archive").toFile()
        try {
            val file = directory.resolve("events.jsonl")
            val log = SessionEventLog(file, json, maxBytes = 700)
            repeat(36) { index ->
                log.append("test", buildJsonObject { put("value", "repeat-me-".repeat(5) + index) })
            }
            val archives = directory.listFiles().orEmpty().filter { it.name.endsWith(".gz") }
            assertTrue(archives.size >= 2)
            archives.take(2).forEach { archive ->
                val raw = File(archive.path.removeSuffix(".gz"))
                GZIPInputStream(archive.inputStream()).use { input -> raw.writeBytes(input.readBytes()) }
                assertTrue(archive.delete())
            }

            val idleLog = SessionEventLog(file, json, maxBytes = 700)
            assertEquals(1, idleLog.archiveLegacySegments())
            assertEquals(1, idleLog.archiveLegacySegments())
            assertEquals(0, idleLog.archiveLegacySegments())
            assertEquals((0L..35L).toList(), idleLog.snapshot().map(SessionEvent::sequence))
            assertEquals(35L, idleLog.latestSequence())
            assertTrue(directory.listFiles().orEmpty().none {
                it.name.matches(Regex("events\\.jsonl\\.part-[0-9]+"))
            })
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
    fun malformedRowsAndUnreadableSegmentsAreCountedAndReported() {
        val directory = Files.createTempDirectory("harness-event-diagnostics").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val reports = mutableListOf<String>()
            val log = SessionEventLog(
                file = file,
                json = json,
                maxBytes = 700,
                diagnosticSink = { kind, _ -> reports += kind },
            )
            repeat(12) { index ->
                log.append("test/event", buildJsonObject {
                    put("value", "row-$index-" + "x".repeat(48))
                })
            }
            file.appendText("{broken-row\n")
            log.snapshot()
            assertTrue(log.diagnostics().malformedRows > 0)
            assertTrue(reports.any { it == "malformed-row" })

            val archive = directory.listFiles().orEmpty().firstOrNull { it.name.endsWith(".gz") }
                ?: error("expected rotated archive")
            archive.writeText("not-a-gzip")
            log.snapshot()
            assertTrue(log.diagnostics().segmentReadFailures > 0)
            assertTrue(reports.any { it.startsWith("segment-read-failed:") })
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
    fun searchIsBoundedAndPaginatesBySequence() {
        val directory = Files.createTempDirectory("harness-event-search-page").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 4_096, clock = { 1L })
            repeat(5) { index ->
                log.append("test/event", buildJsonObject { put("value", "needle-$index") })
            }

            val first = log.search("needle", limit = 2)
            assertTrue(first.contains("0: "))
            assertTrue(first.contains("1: "))
            assertTrue(!first.contains("2: "))
            assertTrue(first.contains("after_sequence=1"))

            val second = log.search("needle", limit = 2, afterSequence = 1L)
            assertTrue(second.contains("2: "))
            assertTrue(second.contains("3: "))
            assertTrue(!second.contains("0: "))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun searchHardCapsOversizedMatchingEvents() {
        val directory = Files.createTempDirectory("harness-event-search-bound").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 100_000, clock = { 1L })
            log.append(
                "test/event",
                buildJsonObject { put("value", "needle-" + "x".repeat(20_000)) },
            )

            val result = log.search("needle", maxChars = 4_096)

            assertTrue(result.length <= 4_096)
            assertTrue(result.contains("session_event_read"))
            assertTrue(result.contains("after_sequence=0"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun largeEventReadPaginatesWithoutLosingContentAndTraceStaysBounded() {
        val directory = Files.createTempDirectory("harness-event-bounded-read").toFile()
        try {
            val log = SessionEventLog(directory.resolve("session.events.jsonl"), json)
            val event = log.append("test/large", buildJsonObject { put("value", "x".repeat(120_000)) })
            val expected = json.encodeToString(SessionEvent.serializer(), event)
            val restored = StringBuilder()
            var offset = 0
            do {
                val page = log.read(event.sequence, offsetChars = offset, maxChars = 4_096)
                assertTrue(page.length <= 4_096)
                val body = page.substringBefore("\n[结果已分页")
                restored.append(body)
                val next = Regex("offset_chars=([0-9]+)").find(page)?.groupValues?.get(1)?.toIntOrNull()
                if (next != null) assertEquals(offset + body.length, next)
                offset = next ?: -1
            } while (offset >= 0)
            assertEquals(expected, restored.toString())
            assertTrue(log.tail(1).length <= 49_000)
            assertTrue(log.tail(1).contains("session_event_read"))
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


    @Test
    fun latestMatchingReturnsNewestPredicateMatchAcrossSegments() {
        val directory = Files.createTempDirectory("harness-event-latest-matching").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 700, clock = { 1L })
            repeat(24) { index ->
                log.append(
                    if (index % 3 == 0) "agent/run-checkpoint" else "noise",
                    buildJsonObject {
                        put("call_id", if (index == 6 || index == 18) "target" else "call-$index")
                        put("value", index)
                    },
                )
            }

            val latest = requireNotNull(
                log.latestMatching(setOf("agent/run-checkpoint")) { data ->
                    data["call_id"]?.jsonPrimitive?.content == "target"
                },
            )

            assertEquals(18L, latest.sequence)
            assertEquals(18, latest.data["value"]?.jsonPrimitive?.content?.toInt())
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test
    fun pointReadCrossesCompressedSegmentBoundary() {
        val directory = Files.createTempDirectory("harness-event-compressed-window").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = SessionEventLog(file, json, maxBytes = 700, clock = { 1L })
            repeat(40) { index ->
                log.append(
                    "test/event",
                    buildJsonObject { put("value", "row-$index-" + "x".repeat(44)) },
                )
            }
            val archived = directory.listFiles().orEmpty()
                .filter { it.name.startsWith("session.events.jsonl.part-") && it.name.endsWith(".gz") }
                .minByOrNull(File::getName)
                ?: error("expected compressed event segment")
            val archivedSequences = GZIPInputStream(archived.inputStream()).bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    runCatching { json.decodeFromString(SessionEvent.serializer(), line).sequence }.getOrNull()
                }.toList()
            }
            val target = archivedSequences.last()
            val window = log.read(sequence = target, before = 2, after = 2).lineSequence()
                .map { json.decodeFromString(SessionEvent.serializer(), it).sequence }
                .toList()

            assertEquals((target - 2L..target + 2L).toList(), window)
        } finally {
            directory.deleteRecursively()
        }
    }

}
