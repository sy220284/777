package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalRequestEvidenceSurfaceMemo
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRequestMessageSurfaceTest {
    private fun message(role: String, text: String) = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    @Test fun longConversationReusesHistoryAndReplaysEveryCommittedLink() {
        val records = linkedMapOf<Long, LocalSessionEventLog.Event>()
        var previous: LocalRequestMessageSurfaceState? = null
        val history = mutableListOf(message("system", "系统上下文".repeat(300)))
        var actualBytes = 0L
        var fullBytes = 0L
        var checkpoints = 0
        repeat(120) { round ->
            history += message("tool", "第$round 次工具输出 ".repeat(70))
            val digest = stableJsonSha256(JsonArray(history))
            val encoded = encodeLocalRequestMessageSurface(history, digest, false, previous)
            val sequence = round.toLong()
            val record = LocalSessionEventLog.Event(sequence, "request/message-surface", 1L, encoded.payload)
            records[sequence] = record
            actualBytes += encoded.payload.toString().toByteArray().size
            fullBytes += history.toString().toByteArray().size
            if (encoded.depth == 0) checkpoints++
            previous = LocalRequestMessageSurfaceState(sequence, history.toList(), digest, encoded.depth, false)
            if (round % 11 == 0 || round == 119) {
                val issues = mutableListOf<String>()
                val restored = replayLocalRequestMessageSurface(record, records::get, issues)
                assertEquals(history, restored)
                assertTrue(issues.toString(), issues.isEmpty())
            }
        }
        assertTrue("actual=$actualBytes full=$fullBytes", actualBytes * 4 < fullBytes)
        assertTrue("checkpoint count=$checkpoints", checkpoints in 3..7)
    }

    @Test fun processRestartReusesDurableDeltaChainAndPreservesRedactionIdentity() {
        val folder = Files.createTempDirectory("message-delta-restart").toFile()
        val logFile = folder.resolve("events.jsonl")
        try {
            val first = LocalSessionEventLog(logFile, kotlinx.serialization.json.Json, maxBytes = 32L * 1024 * 1024)
            val initial = listOf(message("system", "规则".repeat(300)), message("user", "开始"))
            val memo1 = LocalRequestEvidenceSurfaceMemo()
            val a = memo1.ensureMessages(first, "session-route", stableJsonSha256(JsonArray(initial)), initial, false)
            val updated = initial + message("assistant", "完成第一步")
            val b = memo1.ensureMessages(first, "session-route", stableJsonSha256(JsonArray(updated)), updated, false)
            assertTrue(b > a)
            first.close()

            val reopened = LocalSessionEventLog(logFile, kotlinx.serialization.json.Json, maxBytes = 32L * 1024 * 1024)
            val memo2 = LocalRequestEvidenceSurfaceMemo()
            val unchanged = memo2.ensureMessages(reopened, "session-route", stableJsonSha256(JsonArray(updated)), updated, false)
            assertEquals(b, unchanged)
            val later = updated + message("user", "继续")
            val c = memo2.ensureMessages(reopened, "session-route", stableJsonSha256(JsonArray(later)), later, false)
            assertTrue(c > b)
            assertTrue(reopened.latest("request/message-surface")!!.data.containsKey("base_surface_seq"))
            val redactedSequence = memo2.ensureMessages(
                reopened, "session-route", stableJsonSha256(JsonArray(later)), later, true,
            )
            assertTrue(redactedSequence > c)
            val sameRedacted = memo2.ensureMessages(
                reopened, "session-route", stableJsonSha256(JsonArray(later)), later, true,
            )
            assertEquals(redactedSequence, sameRedacted)
            reopened.close()
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun corruptedParentDigestAndFutureLinksAreRejected() {
        val firstMessages = listOf(message("system", "基线".repeat(100)))
        val base = encodeLocalRequestMessageSurface(
            firstMessages, stableJsonSha256(JsonArray(firstMessages)), false,
        )
        val baseEvent = LocalSessionEventLog.Event(7, "request/message-surface", 1L, base.payload)
        val nextMessages = firstMessages + message("user", "下一轮")
        val delta = encodeLocalRequestMessageSurface(
            nextMessages, stableJsonSha256(JsonArray(nextMessages)), false,
            LocalRequestMessageSurfaceState(7, firstMessages, stableJsonSha256(JsonArray(firstMessages)), 0, false),
        )
        assertTrue(delta.payload.containsKey("base_surface_seq"))
        val wrong = JsonObject(delta.payload.toMutableMap().apply {
            put("base_surface_seq", JsonPrimitive(100L))
        })
        val forward = LocalSessionEventLog.Event(8, "request/message-surface", 1L, wrong)
        val invalid = mutableListOf<String>()
        assertEquals(null, replayLocalRequestMessageSurface(forward, { baseEvent }, invalid))
        assertFalse(invalid.isEmpty())

        val corruptedBase = LocalSessionEventLog.Event(
            7, "request/message-surface", 1L,
            JsonObject(base.payload.toMutableMap().apply {
                put("digest", JsonPrimitive("bad-digest"))
            }),
        )
        val child = LocalSessionEventLog.Event(8, "request/message-surface", 1L, delta.payload)
        val issues = mutableListOf<String>()
        assertEquals(null, replayLocalRequestMessageSurface(child, { corruptedBase }, issues))
        assertTrue(issues.any { it.contains("摘要校验失败") })
    }

    @Test fun legacyFullSurfaceReplaysWithoutMigration() {
        val values = listOf(message("user", "旧数据"))
        val data = buildJsonObject {
            put("version", 3)
            put("digest", stableJsonSha256(JsonArray(values)))
            put("messages", JsonArray(values))
            put("redacted", false)
        }
        val event = LocalSessionEventLog.Event(0L, "request/message-surface", 1L, data)
        val issues = mutableListOf<String>()
        assertEquals(values, replayLocalRequestMessageSurface(event, { null }, issues))
        assertTrue(issues.isEmpty())
    }
}
