package com.labteto.dshmobile.local.session

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSessionSnapshotBoundaryTest {
    @Test
    fun boundaryCapturesLatestDurableControlSequenceAndTranscriptCursor() {
        val directory = Files.createTempDirectory("session-snapshot-boundary").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("control/one", buildJsonObject { put("value", 1) })
            log.append("control/two", buildJsonObject { put("value", 2) })

            val boundary = localSessionSnapshotBoundary(log, transcriptProjectionCursor = 9L)

            assertEquals(1L, boundary.controlProjectedThroughSequence)
            assertEquals(9L, boundary.transcriptProjectedThroughSequence)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun boundaryPreservesMissingTranscriptCursor() {
        val directory = Files.createTempDirectory("session-snapshot-boundary-null").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            log.append("control/one", buildJsonObject { put("value", 1) })

            val boundary = localSessionSnapshotBoundary(log, transcriptProjectionCursor = null)

            assertEquals(0L, boundary.controlProjectedThroughSequence)
            assertNull(boundary.transcriptProjectedThroughSequence)
        } finally {
            directory.deleteRecursively()
        }
    }
}
