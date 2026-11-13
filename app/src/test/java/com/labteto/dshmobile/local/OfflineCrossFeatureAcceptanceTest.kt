package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline regression: long transcript replay across rotated event segments. */
class OfflineCrossFeatureAcceptanceTest {
    @Test
    fun coldRestartRetainsAllMessagesAcrossRotatedSegmentsAndToolNoise() {
        val root = kotlin.io.path.createTempDirectory("cross-history-scale-").toFile()
        val file = File(root, "session.events.jsonl")
        val json = Json { ignoreUnknownKeys = true }
        try {
            val writer = LocalSessionEventLog(file, json, maxBytes = 8_192)
            repeat(32) { batch ->
                val messages = (0 until 32).map { offset ->
                    val id = batch * 32 + offset
                    LocalHarnessMessage(
                        id = "m$id",
                        role = if (id % 2 == 0) "user" else "assistant",
                        content = "历史消息 $id",
                        createdAt = id.toLong(),
                    )
                }
                writer.append("transcript/batch",
                    buildJsonObject { put("transcript", encodeTranscriptMessages(messages)) })
                writer.append("tool/result", buildJsonObject { put("batch", batch) })
            }
            writer.close()
            val reopened = LocalSessionEventLog(file, json, maxBytes = 8_192)
            try {
                val pager = LocalSessionTranscriptPager(reopened, eventPageSize = 3)
                val history = pager.all(pageSize = 37)
                assertEquals(1_024, history.size)
                assertEquals((0 until 1_024).map { "m$it" }, history.map { it.id })
                assertEquals(1_024, history.map { it.id }.toSet().size)
                val newest = pager.page(limit = 5)
                assertEquals((1019..1023).map { "m$it" }, newest.messages.map { it.id })
                assertTrue(newest.nextCursor != null)
                assertEquals(63L, reopened.latestSequence())
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
