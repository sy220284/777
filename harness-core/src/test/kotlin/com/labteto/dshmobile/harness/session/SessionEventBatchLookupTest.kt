package com.labteto.dshmobile.harness.session

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventBatchLookupTest {
    @Test
    fun latestPerKeyFindsMostRecentEvidenceAcrossArchivedSegments() {
        val folder = Files.createTempDirectory("session-batch-events").toFile()
        try {
            val log = SessionEventLog(folder.resolve("events.jsonl"), Json, maxBytes = 700)
            val firstA = log.append("subagent/start", buildJsonObject {
                put("background_job_id", "A")
                put("max_steps", 12)
            })
            repeat(36) { n ->
                log.append("unrelated/event", buildJsonObject { put("text", "noise-$n-" + "x".repeat(70)) })
            }
            val oldA = log.append("subagent/checkpoint", buildJsonObject {
                put("background_job_id", "A")
                put("step", 3)
            })
            val latestB = log.append("subagent/checkpoint", buildJsonObject {
                put("background_job_id", "B")
                put("step", 4)
            })
            val latestA = log.append("subagent/checkpoint", buildJsonObject {
                put("background_job_id", "A")
                put("step", 9)
            })
            val keys = setOf("start:A", "checkpoint:A", "checkpoint:B", "checkpoint:missing")
            val found = log.latestMatchingByKey(
                setOf("subagent/start", "subagent/checkpoint"), keys,
            ) { event ->
                val id = event.data["background_job_id"]?.jsonPrimitive?.contentOrNull
                    ?: return@latestMatchingByKey null
                val kind = if (event.type == "subagent/start") "start" else "checkpoint"
                "$kind:$id"
            }
            assertEquals(firstA.sequence, found["start:A"]?.sequence)
            assertEquals(latestA.sequence, found["checkpoint:A"]?.sequence)
            assertEquals(latestB.sequence, found["checkpoint:B"]?.sequence)
            assertFalse("checkpoint:missing" in found)
            assertTrue(oldA.sequence < requireNotNull(found["checkpoint:A"]).sequence)
            log.close()
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun emptyRequestedKeysDoesNotScanOrInventEvents() {
        val folder = Files.createTempDirectory("session-batch-empty").toFile()
        try {
            val log = SessionEventLog(folder.resolve("events.jsonl"), Json)
            assertTrue(log.latestMatchingByKey(setOf("x"), emptySet<String>()) { null }.isEmpty())
            log.close()
        } finally {
            folder.deleteRecursively()
        }
    }
}
