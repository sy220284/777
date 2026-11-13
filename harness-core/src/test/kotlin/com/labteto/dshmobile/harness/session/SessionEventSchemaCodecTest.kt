package com.labteto.dshmobile.harness.session

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventSchemaCodecTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val v0 = """{"schema_version":0,"seq":0,"event_type":"user/message","timestamp":100,"payload":{"text":"prior"}}"""
    private val v1 = """{"sequence":1,"type":"user/message","createdAt":101,"data":{"text":"old"}}"""
    private val v3 = """{"sequence":3,"type":"user/message","createdAt":103,"data":{},"schema_version":3}"""

    @Test fun legacyV0AndUnversionedV1UpgradeToCanonicalEnvelopeWithoutLoss() {
        val first = SessionEventSchemaCodec.decode(json, v0)
        val next = SessionEventSchemaCodec.decode(json, v1)
        assertEquals(0L, first.sequence)
        assertEquals("prior", first.data["text"]?.toString()?.trim('"'))
        assertEquals(1L, next.sequence)
        assertEquals("old", next.data["text"]?.toString()?.trim('"'))
        assertEquals(2, first.schemaVersion)
        assertEquals(2, next.schemaVersion)
    }

    @Test fun newEventsWriteExplicitV2AndPersistThroughColdRestartWithOldMessages() {
        val dir = Files.createTempDirectory("session-versioned-").toFile()
        val file = dir.resolve("session.jsonl")
        try {
            file.writeText(v0 + "\n" + v1 + "\n")
            SessionEventLog(file, json, maxBytes = 800).use { log ->
                val current = log.append("user/message", buildJsonObject { put("text", "new") })
                assertEquals(2L, current.sequence)
                assertEquals(2, current.schemaVersion)
                assertTrue(file.readText().contains("\"schema_version\":2"))
            }
            SessionEventLog(file, json, maxBytes = 800).use { reopened ->
                val restored = reopened.pageBeforeChronological(limit = 3)
                assertEquals(listOf("prior", "old", "new"),
                    restored.map { it.data["text"]?.toString()?.trim('"') })
                assertEquals(listOf(0L, 1L, 2L), restored.map { it.sequence })
                assertEquals(listOf(0L, 1L, 2L),
                    reopened.pageAfter(-1L, 3).map { it.sequence })
            }
            // The migration runs at read-time. Neither old durable row is rewritten.
            assertTrue(file.readText().startsWith(v0 + "\n" + v1 + "\n"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun futureSchemaMustBlockReadsInsteadOfPretendingMessageNeverExisted() {
        assertThrows(UnsupportedSessionEventSchemaVersion::class.java) {
            SessionEventSchemaCodec.decode(json, v3)
        }
        val dir = Files.createTempDirectory("session-future-schema-").toFile()
        val file = dir.resolve("events.jsonl")
        try {
            file.writeText(v1 + "\n" + v3 + "\n")
            // The cold reader must fail BEFORE it exposes partial history or
            // allocates a new sequence that could overwrite future commands.
            assertThrows(UnsupportedSessionEventSchemaVersion::class.java) {
                SessionEventLog(file, json, maxBytes = 800)
            }
            assertTrue(file.readText().contains(v3))
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidLegacyPayloadIsNotReinterpretedAsAValidEmptyMessage() {
        assertThrows(Exception::class.java) {
            SessionEventSchemaCodec.decode(json,
                """{"schema_version":0,"seq":1,"event_type":"user/message","timestamp":1,"payload":"missing"}""")
        }
    }
    @Test fun completeCrashTailWithoutNewlineKeepsCommittedMessageAndMonotonicSequence() {
        val dir = Files.createTempDirectory("event-complete-tail-").toFile()
        val file = dir.resolve("events.jsonl")
        try {
            file.writeText(v1)
            SessionEventLog(file, json, maxBytes = 800).use { log ->
                val next = log.append("user/message", buildJsonObject { put("text", "after-restart") })
                assertEquals(2L, next.sequence)
                assertEquals(listOf(1L, 2L), log.snapshot().map { it.sequence })
            }
            assertTrue(file.readText().startsWith(v1 + "\n"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun completeFutureVersionWithoutNewlineIsNeverTruncatedOrMadeWritable() {
        val dir = Files.createTempDirectory("event-future-tail-").toFile()
        val file = dir.resolve("events.jsonl")
        try {
            file.writeText(v3)
            assertThrows(UnsupportedSessionEventSchemaVersion::class.java) {
                SessionEventLog(file, json, maxBytes = 800)
            }
            assertEquals(v3, file.readText())
        } finally { dir.deleteRecursively() }
    }

}
