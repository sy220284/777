package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.session.LocalTranscriptPageCursor
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import com.labteto.dshmobile.local.session.projectSessionTranscriptTail
import com.labteto.dshmobile.local.session.projectLocalTranscriptRuntimeIndexTail
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex
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
        assertTrue("编辑仅记录单条消息变更", "transcript_delta" in persisted.data)
        assertFalse("不能重写整段会话", "transcript" in persisted.data)
        assertTrue("持久索引用于冷启动恢复", "transcript_index" in persisted.data)
        assertEquals(edited.messages, LocalSessionTranscriptPager(log).all())
        val replayed = projectSessionTranscriptTail(
            emptyList(), log.snapshot(), sequenceExclusive = -1L,
        )
        assertEquals(edited.messages, replayed.messages)
        val indexed = projectLocalTranscriptRuntimeIndexTail(
            LocalTranscriptRuntimeIndex(), log.snapshot(), sequenceExclusive = -1L,
        )
        assertEquals(edited.transcriptIndex, indexed)
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

    @Test fun repeatedEditsAndRemovalKeepOriginalOrderingWithoutHistoryCopies() =
        withQueue { log, queue, editor ->
            val first = requireNotNull(editor.edit("user-1", "第一次修改"))
            val second = requireNotNull(editor.edit("user-1", "第二次修改"))
            assertEquals(first.messages.map { it.id }, second.messages.map { it.id })
            assertEquals("第二次修改", LocalSessionTranscriptPager(log).all().first().content)
            assertEquals(second.messages, projectSessionTranscriptTail(
                emptyList(), log.snapshot(), -1L,
            ).messages)
            val removal = requireNotNull(editor.remove("user-1"))
            assertEquals(listOf("user-2"), removal.messages.map { it.id })
            assertEquals(removal.messages, LocalSessionTranscriptPager(log).all())
            assertEquals(removal.messages, projectSessionTranscriptTail(
                emptyList(), log.snapshot(), -1L,
            ).messages)
            assertEquals(1, queue.size())
            val rewritten = log.snapshot().filter {
                it.type == LOCAL_AGENT_INBOX_EVENT_TYPE &&
                    it.data["action"]?.toString()?.contains(LOCAL_QUEUED_USER_TIMELINE_ACTION) == true
            }
            assertEquals(3, rewritten.size)
            assertTrue(rewritten.all { "transcript_delta" in it.data && "transcript" !in it.data })
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

    @Test fun longTranscriptKeepsOriginalOrderAcrossPagesAndPersistsOnlyOneDelta() {
        val directory = Files.createTempDirectory("queue-long-transcript-").toFile()
        val log = LocalSessionEventLog(directory.resolve("long.events.jsonl"), json)
        val queue = AgentInputQueue()
        try {
            val original = (0 until 360).map { id ->
                LocalHarnessMessage(
                    id = "history-$id", role = "user", content = "历史内容-$id",
                    createdAt = id.toLong(),
                )
            }
            queue.offer(QueuedAgentInput(
                id = "history-12", content = "历史内容-12",
                memoryInput = "历史内容-12",
                modelMessage = buildJsonObject {
                    put("role", "user")
                    put("content", "历史内容-12")
                },
            ))
            log.append("chat/active-transcript", buildJsonObject {
                put("transcript", encodeTranscriptMessages(original))
            })
            val editor = LocalQueuedUserMessageEditor(log, queue)
            val updated = requireNotNull(editor.edit("history-12", "修订后仍在原位置"))
            val delta = requireNotNull(log.latest(LOCAL_AGENT_INBOX_EVENT_TYPE))
            assertTrue("消息变更不应再复制整段历史", delta.data.toString().length < 2_000)
            assertFalse("transcript" in delta.data)

            fun pagedMessages(): List<LocalHarnessMessage> {
                val pages = mutableListOf<List<LocalHarnessMessage>>()
                var cursor: LocalTranscriptPageCursor? = null
                do {
                    val page = LocalSessionTranscriptPager(log).page(cursor = cursor, limit = 23)
                    pages += page.messages
                    cursor = page.nextCursor
                } while (cursor != null)
                return pages.asReversed().flatten()
            }
            assertEquals(updated.messages, pagedMessages())
            assertEquals(updated.messages, projectSessionTranscriptTail(
                emptyList(), log.snapshot(), sequenceExclusive = -1L,
            ).messages)
            assertEquals(updated.transcriptIndex, projectLocalTranscriptRuntimeIndexTail(
                LocalTranscriptRuntimeIndex(), log.snapshot(), sequenceExclusive = -1L,
            ))
            val removed = requireNotNull(editor.remove("history-12"))
            assertEquals(359, removed.messages.size)
            assertEquals(removed.messages, pagedMessages())
            assertEquals(removed.messages, projectSessionTranscriptTail(
                emptyList(), log.snapshot(), sequenceExclusive = -1L,
            ).messages)
        } finally {
            log.close()
            directory.deleteRecursively()
        }
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
