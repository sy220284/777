package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.session.LocalSessionEventLog
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
    fun streamedEventsMatchSnapshotAcrossRotatedSegmentsAndSequenceAdvances() {
        val directory = Files.createTempDirectory("local-event-stream").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = LocalSessionEventLog(file, json, maxBytes = 700)
            repeat(20) { index ->
                log.append(
                    "test/event",
                    buildJsonObject { put("value", "row-$index-" + "x".repeat(48)) },
                )
            }
            val firstSequence = log.latestSequence()
            assertEquals(
                log.snapshot().map { it.sequence to it.type },
                log.withEvents { events -> events.map { it.sequence to it.type }.toList() },
            )

            log.append("test/next", buildJsonObject { put("value", "next") })

            assertTrue(log.latestSequence() > firstSequence)
            assertEquals("test/next", log.withEvents { it.last().type })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun streamedEventsDoNotReadRowsAppendedAfterIterationSnapshotOpens() {
        val directory = Files.createTempDirectory("local-event-stream-snapshot").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = LocalSessionEventLog(file, json, maxBytes = 4_096)
            log.append("test/first", buildJsonObject { put("value", "first") })

            log.withEvents { events ->
                val iterator = events.iterator()
                assertTrue(iterator.hasNext())
                val first = iterator.next()
                log.append("test/late", buildJsonObject { put("value", "late") })

                assertEquals("test/first", first.type)
                assertTrue(!iterator.hasNext())
            }
            assertEquals("test/late", log.withEvents { it.last().type })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun scopedStreamSurvivesSegmentCompressionBetweenRows() {
        val directory = Files.createTempDirectory("local-event-archive-snapshot").toFile()
        val file = directory.resolve("session.events.jsonl")
        val writer = com.labteto.dshmobile.harness.session.SessionEventLog(file, json, maxBytes = 700)
        repeat(20) { index ->
            writer.append("test/event", buildJsonObject { put("value", "$index-" + "x".repeat(60)) })
        }
        writer.close()
        val log = LocalSessionEventLog(file, json, maxBytes = 700)
        val archiver = com.labteto.dshmobile.harness.session.SessionEventLog(file, json, maxBytes = 700)
        try {
            val observed = log.withEvents { events ->
                val iterator = events.iterator()
                val first = iterator.next()
                assertTrue(archiver.archiveLegacySegments(limit = 16) > 0)
                listOf(first.sequence) + iterator.asSequence().map { it.sequence }.toList()
            }
            assertEquals((0L until 20L).toList(), observed)
        } finally {
            archiver.close()
            log.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun scopedStreamClosesSnapshotsAfterEarlyReturnAndConsumerFailure() {
        val directory = Files.createTempDirectory("local-event-scoped").toFile()
        val log = LocalSessionEventLog(directory.resolve("session.events.jsonl"), json, maxBytes = 1_048_576)
        try {
            repeat(20) { index ->
                log.append("test/event", buildJsonObject { put("value", "$index-" + "x".repeat(60)) })
            }
            // Fixed raw and compressed segments exercise unread snapshots without racing the
            // asynchronous rotation archiver. Their contents are valid durable event rows.
            val bytes = directory.resolve("session.events.jsonl").readBytes()
            directory.resolve("session.events.jsonl.part-0").writeBytes(bytes)
            java.util.zip.GZIPOutputStream(
                directory.resolve("session.events.jsonl.part-1.gz").outputStream(),
            ).use { it.write(bytes) }
            val descriptors = java.io.File("/proc/self/fd")
            org.junit.Assume.assumeTrue(descriptors.isDirectory)
            fun openSnapshots() = descriptors.listFiles().orEmpty().count { descriptor ->
                runCatching { Files.readSymbolicLink(descriptor.toPath()).toString() }
                    .getOrNull()?.startsWith(directory.absolutePath) == true
            }
            val before = openSnapshots()
            repeat(20) {
                assertEquals("test/event", log.withEvents { it.first().type })
                val failure = runCatching {
                    log.withEvents { events ->
                        events.first()
                        error("consumer failed")
                    }
                }.exceptionOrNull()
                assertEquals("consumer failed", failure?.message)
            }
            assertEquals(before, openSnapshots())
        } finally {
            log.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun exposesBoundedReversePagesForInfiniteSessionHistory() {
        val directory = Files.createTempDirectory("local-event-page-before").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = LocalSessionEventLog(file, json, maxBytes = 700)
            repeat(24) { index ->
                log.append(
                    "test/event",
                    buildJsonObject { put("value", "row-$index-" + "x".repeat(48)) },
                )
            }

            assertEquals(
                listOf(20L, 21L, 22L, 23L),
                log.pageBeforeChronological(limit = 4)
                    .map(LocalSessionEventLog.Event::sequence),
            )
            assertEquals(
                listOf(23L, 22L, 21L, 20L),
                log.pageBeforeNewestFirst(limit = 4)
                    .map(LocalSessionEventLog.Event::sequence),
            )
            assertEquals(
                listOf(7L, 8L, 9L),
                log.pageBeforeChronological(sequenceExclusive = 10L, limit = 3)
                    .map(LocalSessionEventLog.Event::sequence),
            )
            assertEquals(
                listOf(9L, 8L, 7L),
                log.pageBeforeNewestFirst(sequenceExclusive = 10L, limit = 3)
                    .map(LocalSessionEventLog.Event::sequence),
            )
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
