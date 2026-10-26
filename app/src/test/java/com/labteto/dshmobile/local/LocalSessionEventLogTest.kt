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
    fun durableAppendAndResetEmitRevisionsWithoutInventingVerification() {
        val directory = Files.createTempDirectory("local-event-revisions").toFile()
        val file = directory.resolve("session.events.jsonl")
        try {
            val log = LocalSessionEventLog(file, json, maxBytes = 4_096)
            assertEquals(0L, log.changes.value)
            val first = log.append("work/requirement-evidence", buildJsonObject {
                put("assertion", "user_selected_reference_only")
            })
            assertEquals(0L, first.sequence)
            assertEquals(1L, log.changes.value)
            log.append("work/tool-result", buildJsonObject { put("status", "failed") })
            assertEquals(2L, log.changes.value)
            log.clear()
            assertEquals(3L, log.changes.value)
            assertTrue(log.snapshot().isEmpty())
            log.close()
            assertEquals(4L, log.changes.value)
        } finally { directory.deleteRecursively() }
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
    @Test
    fun coldSnapshotStreamAndPagesApplyOneVersionMigrationAcrossCompressedSegments() {
        val dir = Files.createTempDirectory("local-event-schema-upgrade").toFile()
        val file = dir.resolve("session.events.jsonl")
        val oldV0 = """{"schema_version":0,"seq":0,"event_type":"user/message","timestamp":100,"payload":{"text":"archived"}}"""
        val oldV1 = """{"sequence":1,"type":"user/message","createdAt":101,"data":{"text":"legacy"}}"""
        try {
            java.util.zip.GZIPOutputStream(
                dir.resolve("session.events.jsonl.part-0.gz").outputStream()
            ).use { it.write((oldV0 + "\n" + oldV1 + "\n").toByteArray()) }
            val log = LocalSessionEventLog(file, json, maxBytes = 700)
            try {
                val written = log.append("user/message", buildJsonObject { put("text", "current") })
                assertEquals(2L, written.sequence)
                val fromStream = log.withEvents { events ->
                    events.map { it.sequence to it.data["text"]?.toString()?.trim('"') }.toList()
                }
                val fromPages = log.pageBeforeChronological(limit = 3)
                    .map { it.sequence to it.data["text"]?.toString()?.trim('"') }
                assertEquals(
                    listOf(0L to "archived", 1L to "legacy", 2L to "current"),
                    fromStream,
                )
                assertEquals(fromStream, fromPages)
            } finally { log.close() }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun legacyFileBackendAlsoHonorsWorkProjectionWindowsBeyondTwoHundredEvents() {
        val dir = Files.createTempDirectory("legacy-work-projection-window").toFile()
        try {
            val log = LocalSessionEventLog(dir.resolve("work.events.jsonl"), json, maxBytes = 4_000_000)
            try {
                repeat(275) { number ->
                    log.append("tool/call", buildJsonObject {
                        put("id", "call-$"+"number")
                        put("name", "read")
                    })
                }
                assertEquals((0L..274L).toList(),
                    log.pageBeforeChronological(limit = 1_024).map { it.sequence })
                assertEquals((75L..274L).toList(),
                    log.pageBeforeChronological(limit = 200).map { it.sequence })
                assertEquals((0L..249L).toList(),
                    log.pageBeforeChronological(sequenceExclusive = 250L, limit = 4_096).map { it.sequence })
            } finally {
                log.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun newerEventSchemaBlocksColdSessionOwnerInsteadOfDeletingOrSkippingHistory() {
        val dir = Files.createTempDirectory("local-future-event").toFile()
        val file = dir.resolve("session.events.jsonl")
        val future = """{"sequence":0,"type":"user/message","createdAt":11,"data":{},"schema_version":14}"""
        try {
            file.writeText(future + "\n")
            org.junit.Assert.assertThrows(
                com.labteto.dshmobile.harness.session.UnsupportedSessionEventSchemaVersion::class.java
            ) {
                LocalSessionEventLog(file, json, maxBytes = 700)
            }
            assertEquals(future + "\n", file.readText())
        } finally { dir.deleteRecursively() }
    }

}
