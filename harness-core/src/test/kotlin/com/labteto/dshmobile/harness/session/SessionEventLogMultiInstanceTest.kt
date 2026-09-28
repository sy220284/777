package com.labteto.dshmobile.harness.session

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionEventLogMultiInstanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun liveInstancesOnSamePathNeverReuseCommittedSequence() {
        val root = createTempDir(prefix = "session-log-multi-instance-")
        try {
            val file = File(root, "session.events.jsonl")
            val first = SessionEventLog(file, json, maxBytes = 4_096, clock = { 1L })
            val second = SessionEventLog(file, json, maxBytes = 4_096, clock = { 2L })

            assertEquals(0L, first.append("test/first", buildJsonObject { put("value", 1) }).sequence)
            assertEquals(1L, second.append("test/second", buildJsonObject { put("value", 2) }).sequence)
            assertEquals(2L, first.append("test/third", buildJsonObject { put("value", 3) }).sequence)

            assertEquals(listOf(0L, 1L, 2L), first.snapshot().map(SessionEvent::sequence))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearRebasesOtherLiveInstanceBeforeItsNextAppend() {
        val root = createTempDir(prefix = "session-log-clear-instance-")
        try {
            val file = File(root, "session.events.jsonl")
            val first = SessionEventLog(file, json, maxBytes = 4_096, clock = { 1L })
            first.append("test/old", buildJsonObject { put("value", 1) })
            val stale = SessionEventLog(file, json, maxBytes = 4_096, clock = { 2L })
            first.append("test/old-2", buildJsonObject { put("value", 2) })

            first.clear()
            val fresh = stale.append("test/fresh", buildJsonObject { put("value", 3) })

            assertEquals(0L, fresh.sequence)
            assertEquals(listOf(0L), stale.snapshot().map(SessionEvent::sequence))
        } finally {
            root.deleteRecursively()
        }
    }
}
