package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalQueuedUserMessageEditorTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun withQueue(block: (LocalSessionEventLog, AgentInputQueue, LocalQueuedUserMessageEditor) -> Unit) {
        val directory = Files.createTempDirectory("queue-editor-contract-").toFile()
        val log = LocalSessionEventLog(directory.resolve("session.events.jsonl"), json)
        val queue = AgentInputQueue()
        try {
            val first = QueuedAgentInput(
                content = "初始问题",
                memoryInput = "初始问题",
                modelMessage = buildJsonObject {
                    put("role", "user")
                    put("content", "初始问题")
                },
                id = "user-1",
            )
            val second = QueuedAgentInput(
                content = "第二问题",
                memoryInput = "第二问题",
                modelMessage = buildJsonObject {
                    put("role", "user")
                    put("content", "第二问题")
                },
                id = "user-2",
            )
            queue.offer(first)
            queue.offer(second)
            log.append(LOCAL_AGENT_INBOX_EVENT_TYPE, encodeLocalAgentInboxEvent(
                action = "queued",
                pending = queue.snapshot(),
                affected = listOf(first, second),
                transcript = listOf(
                    LocalHarnessMessage("user-1", "user", "初始问题", createdAt = 100),
                    LocalHarnessMessage("user-2", "user", "第二问题", createdAt = 101),
                ),
            ))
            block(log, queue, LocalQueuedUserMessageEditor(log, queue))
        } finally {
            log.close()
            directory.deleteRecursively()
        }
    }

    @Test fun editPreservesIdentityAndModelPayloadAcrossCrashReplay() = withQueue { log, queue, editor ->
        val before = log.latestSequence()
        val edited = requireNotNull(editor.edit("user-1", "修订后的问题"))
        assertEquals(before + 1L, edited.eventSequence)
        assertEquals(2, edited.pendingCount)
        assertEquals(listOf("user-1", "user-2"), edited.messages.map { it.id })
        assertEquals("修订后的问题", edited.messages.first().content)
        assertEquals("修订后的问题", queue.snapshot().first().memoryInput)
        assertEquals("修订后的问题", queue.snapshot().first().modelMessage?.get("content")?.toString()?.trim('"'))
        val persisted = requireNotNull(log.latest(LOCAL_AGENT_INBOX_EVENT_TYPE))
        assertEquals(LOCAL_QUEUED_USER_TIMELINE_ACTION,
            persisted.data["action"]?.toString()?.trim('"'))
        assertEquals(queue.snapshot(), decodeLocalAgentInboxPending(persisted.data))
        assertEquals(edited.messages, LocalSessionTranscriptPager(log).all())
    }

    @Test fun deleteKeepsOriginalOtherUserAndSurvivesRestoringEventLog() =
        withQueue { log, queue, editor ->
            val update = requireNotNull(editor.remove("user-1"))
            assertEquals(1, update.pendingCount)
            assertEquals(listOf("user-2"), update.messages.map { it.id })
            assertEquals(listOf("user-2"), queue.snapshot().map { it.id })
            assertEquals(listOf("user-2"), LocalSessionTranscriptPager(log).all().map { it.id })
            assertEquals(queue.snapshot(),
                decodeLocalAgentInboxPending(requireNotNull(log.latest(LOCAL_AGENT_INBOX_EVENT_TYPE)).data))
            assertEquals(null, editor.remove("user-1"))
        }

    @Test fun reorderCommitsTheExactOrderAndDoesNotRewriteText() =
        withQueue { log, queue, editor ->
            val before = LocalSessionTranscriptPager(log).all()
            assertTrue(editor.reorder(listOf("user-2", "user-1")))
            assertEquals(listOf("user-2", "user-1"), queue.snapshot().map { it.id })
            assertEquals(queue.snapshot(),
                decodeLocalAgentInboxPending(requireNotNull(log.latest(LOCAL_AGENT_INBOX_EVENT_TYPE)).data))
            assertEquals(before, LocalSessionTranscriptPager(log).all())
            assertFalse(editor.reorder(listOf("user-1")))
            assertEquals(listOf("user-2", "user-1"), queue.snapshot().map { it.id })
        }

    @Test fun missingQueuedIdDoesNotMutateEventLogOrQueue() =
        withQueue { log, queue, editor ->
            val before = log.latestSequence()
            val inputs = queue.snapshot()
            assertEquals(null, editor.edit("missing", "new"))
            assertEquals(null, editor.remove("missing"))
            assertEquals(before, log.latestSequence())
            assertEquals(inputs, queue.snapshot())
        }

    @Test fun failedTranscriptAdmissionPreservesQueuedInputs() {
        val directory = Files.createTempDirectory("queue-missing-transcript-").toFile()
        val log = LocalSessionEventLog(directory.resolve("session.events.jsonl"), json)
        val queue = AgentInputQueue()
        try {
            queue.offer(QueuedAgentInput(content = "no durable user row", id = "missing"))
            val before = queue.snapshot()
            val editor = LocalQueuedUserMessageEditor(log, queue)
            assertTrue(runCatching { editor.remove("missing") }.isFailure)
            assertTrue(runCatching { editor.edit("missing", "modified") }.isFailure)
            assertEquals(before, queue.snapshot())
            assertEquals(-1L, log.latestSequence())
        } finally {
            log.close()
            directory.deleteRecursively()
        }
    }
}
