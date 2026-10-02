package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalSessionEventLog
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class PendingChatTurnStoreTest {
    @Test fun prolongedConsolidationFailureBoundsContextAndPreservesEveryFullTurn() {
        val directory = createTempDirectory().toFile()
        try {
            val log = LocalSessionEventLog(File(directory, "events.jsonl"), Json, maxBytes = 32_768)
            var context = ChatContextState()
            repeat(150) { index ->
                val event = log.append("assistant/message", buildJsonObject {})
                context = context.enqueuePendingDurably(ChatPendingTurn(
                    sequence = event.sequence, assistantMessageId = "a$index",
                    userMessage = "user-$index-" + "u".repeat(6000),
                    assistantMessage = "reply-$index-" + "a".repeat(6000),
                ), log)
            }
            assertEquals(MAX_PENDING_CONTEXT_TURNS, context.pendingTurns.size)
            assertTrue(context.pendingTurns.all { it.userMessage.length <= 4000 && it.assistantMessage.length <= 4000 })
            val seen = mutableListOf<String>()
            while (true) {
                val batch = context.loadPendingBatch(log, 8)
                if (batch.isEmpty()) break
                assertTrue(batch.all { it.assistantMessage.length > 6000 })
                seen += batch.map { it.assistantMessageId }
                context = context.commitProcessed(context.scene, context.continuity, batch.last().sequence)
            }
            assertEquals((0 until 150).map { "a$it" }, seen)
            assertTrue(context.pendingTurns.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun archiveFiltersBranchAndScopeAndOrdersDelayedWritesByTurnSequence() {
        val directory = createTempDirectory().toFile()
        try {
            val log = LocalSessionEventLog(File(directory, "events.jsonl"), Json)
            repeat(30) { log.append("assistant/message", buildJsonObject {}) }
            var context = ChatContextState()
            for (sequence in listOf(25L, 8L, 20L, 3L)) {
                context = context.enqueuePendingDurably(ChatPendingTurn(sequence, assistantMessageId = "a$sequence", branchHeadId = "a$sequence"), log)
            }
            context.enqueuePendingDurably(ChatPendingTurn(7L, assistantMessageId = "group"), log, "group")
            val restored = context.rebaseGeneration()
            val batch = restored.loadPendingBatch(log, 2, activeBranchMessageIds = setOf("a3", "a8", "a25"))
            assertEquals(listOf(3L, 8L), batch.map { it.sequence })
            assertTrue(batch.all { it.generation == restored.generation })
        } finally { directory.deleteRecursively() }
    }
    @Test fun legacyQueueIsArchivedBeforeBoundingOnRestore() {
        val directory = createTempDirectory().toFile()
        try {
            val log = LocalSessionEventLog(File(directory, "events.jsonl"), Json)
            repeat(200) { log.append("assistant/message", buildJsonObject {}) }
            val legacy = ChatContextState(pendingTurns = (0L until 200L).map { sequence ->
                ChatPendingTurn(sequence, assistantMessageId = "a$sequence", assistantMessage = "x".repeat(6000))
            })
            val restored = legacy.boundDurablePending(log)
            assertTrue(restored.pendingArchiveReady)
            assertEquals(64, restored.pendingTurns.size)
            assertEquals(199L, restored.pendingThroughSequence)
            val batch = restored.loadPendingBatch(log, 8)
            assertEquals((0L until 8L).toList(), batch.map { it.sequence })
            assertTrue(batch.all { it.assistantMessage.length == 6000 })
            val before = log.latestSequence()
            restored.boundDurablePending(log)
            assertEquals(before, log.latestSequence())
        } finally { directory.deleteRecursively() }
    }

    @Test fun malformedScopeTypeDoesNotAbortPendingTurnRecovery() {
        val directory = createTempDirectory().toFile()
        try {
            val log = LocalSessionEventLog(File(directory, "events.jsonl"), Json)
            log.append("chat/pending-turn", buildJsonObject {
                put("scope", buildJsonObject { put("unexpected", true) })
            })
            val assistant = log.append("assistant/message", buildJsonObject {})
            val context = ChatContextState().enqueuePendingDurably(
                ChatPendingTurn(assistant.sequence, assistantMessageId = "valid"),
                log,
            )

            assertEquals(listOf("valid"), context.loadPendingBatch(log, 8).map { it.assistantMessageId })
        } finally { directory.deleteRecursively() }
    }

    @Test fun continuationImportsEvictedFactsAndResetsSourceCursorToTargetLog() {
        val directory = createTempDirectory().toFile()
        try {
            val source = LocalSessionEventLog(File(directory, "source.events.jsonl"), Json)
            var context = ChatContextState()
            repeat(100) { index ->
                val event = source.append("assistant/message", buildJsonObject {})
                context = context.enqueuePendingDurably(ChatPendingTurn(
                    event.sequence, assistantMessageId = "a$index", branchHeadId = "a$index",
                    assistantMessage = "fact-$index",
                ), source)
            }
            context = context.commitProcessed(context.scene, context.continuity, 19L)
            var continued = context.continuePendingInSession(directory, "source", "target", "direct")
            val target = LocalSessionEventLog(File(directory, "target.events.jsonl"), Json)
            assertEquals(-1L, continued.processedThroughSequence)
            val found = mutableListOf<String>()
            while (true) {
                val batch = continued.loadPendingBatch(target, 8, activeBranchMessageIds = setOf("new-branch"))
                if (batch.isEmpty()) break
                found += batch.map { it.assistantMessageId }
                continued = continued.commitProcessed(continued.scene, continued.continuity, batch.last().sequence)
            }
            assertEquals((10 until 100).map { "a$it" }, found)
            val newEvent = target.append("assistant/message", buildJsonObject {})
            continued = continued.enqueuePendingDurably(ChatPendingTurn(newEvent.sequence, assistantMessageId = "new"), target)
            assertEquals("new", continued.loadPendingBatch(target, 8).single().assistantMessageId)
        } finally { directory.deleteRecursively() }
    }

}
