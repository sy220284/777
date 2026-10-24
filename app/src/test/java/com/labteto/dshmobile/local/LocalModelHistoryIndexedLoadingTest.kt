package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelHistoryIndexedLoadingTest {
    @Test fun filterSkipsUnrelatedEventsButPreservesCheckpointAndToolHistory() {
        val dir = kotlin.io.path.createTempDirectory("history-indexed-").toFile()
        try {
            val log = LocalSessionEventLog(File(dir, "events.jsonl"), Json)
            log.append("user/message", buildJsonObject { put("content", "Before checkpoint") })
            val codec = ModelHistoryCheckpointCodec()
            log.append(ModelHistoryCheckpointCodec.EVENT_TYPE, codec.encode(
                messages = emptyList(), reason = "pre-turn", asOfSequence = -1L,
            ))
            repeat(120) { i -> log.append("step/end", buildJsonObject { put("iteration", i) }) }
            log.append("assistant/message", buildJsonObject {
                put("role", "assistant")
                put("content", "After checkpoint")
            })

            val indexed = loadModelHistoryReplayEvents(log, codec, emptyList())
            assertEquals(
                listOf("user/message", ModelHistoryCheckpointCodec.EVENT_TYPE, "assistant/message"),
                indexed.map { it.type },
            )
            val reconstructed = restoreLocalModelHistory(indexed, emptyList(), codec)
            assertEquals(listOf("user", "assistant"), reconstructed.messages.map {
                it["role"].toString().trim('"')
            })
            assertFalse(indexed.any { it.type == "step/end" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun invalidCheckpointWatermarkFallsBackToEarlierValidCheckpoint() {
        val dir = kotlin.io.path.createTempDirectory("history-watermark-").toFile()
        try {
            val log = LocalSessionEventLog(File(dir, "events.jsonl"), Json)
            val codec = ModelHistoryCheckpointCodec()
            val first = log.append(ModelHistoryCheckpointCodec.EVENT_TYPE, codec.encode(
                messages = emptyList(), reason = "valid", asOfSequence = -1L,
            ))
            log.append("user/message", buildJsonObject { put("content", "Retained") })
            // This checkpoint falsely claims it has materialized a later event.
            log.append(ModelHistoryCheckpointCodec.EVENT_TYPE, codec.encode(
                messages = emptyList(), reason = "future", asOfSequence = 100L,
            ))
            val recovered = loadModelHistoryReplayEvents(log, codec, emptyList())
            assertEquals(first.sequence, recovered.first().sequence)
            assertTrue(recovered.any { it.type == "user/message" })
        } finally {
            dir.deleteRecursively()
        }
    }
}
