package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatDiaryDelta
import com.labteto.dshmobile.local.chat.ChatDiarySourceMode
import com.labteto.dshmobile.local.chat.ChatDiaryStore
import com.labteto.dshmobile.local.chat.ChatDiaryWriteRequest
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.LocalChatBranchState
import com.labteto.dshmobile.local.chat.LocalGroupChatState
import com.labteto.dshmobile.local.chat.LocalTimelineRewriteProjectionInput
import com.labteto.dshmobile.local.chat.LocalTimelineRewriteState
import com.labteto.dshmobile.local.chat.appendTimelineRewriteCommit
import com.labteto.dshmobile.local.chat.recoverPendingTimelineRewriteProjection
import com.labteto.dshmobile.local.chat.sourceEventSequenceForMessage
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalTodoItem
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTimelineRewriteTransactionTest {
    @Test
    fun oneRewriteEventRestoresTranscriptModelHistoryAndControlState() {
        withStores { log, _, _, _, _ ->
            val edited = LocalHarnessMessage(
                id = "edited",
                role = "user",
                content = "新问题",
                createdAt = 10L,
            )
            val modelHistory = listOf(
                buildJsonObject { put("role", "system"); put("content", "system") },
                buildJsonObject { put("role", "user"); put("content", "新问题") },
            )
            val rewriteState = LocalTimelineRewriteState(
                plan = listOf("新计划"),
                todos = listOf(LocalTodoItem("继续修复", "in_progress")),
                goal = LocalGoal("完成重写"),
                planMode = true,
                chatState = ChatCharacterState(mood = "稳定"),
                chatContext = ChatContextState(generation = 7L),
                chatBranches = LocalChatBranchState(),
                groupChat = LocalGroupChatState(),
            )
            val event = appendTimelineRewriteCommit(
                eventLog = log,
                reason = "test",
                activeTranscript = listOf(edited),
                modelHistory = modelHistory,
                state = rewriteState,
                projection = LocalTimelineRewriteProjectionInput(
                    sourceSessionId = "session",
                    createdAtInclusive = 1L,
                    discardedMessageIds = listOf("old"),
                ),
                editedMessageId = edited.id,
                editedModelMessage = modelHistory.last(),
            )

            assertEquals(listOf("edited"), LocalSessionTranscriptPager(log).all().map { it.id })
            assertEquals(
                modelHistory,
                restoreLocalModelHistory(
                    events = listOf(event),
                    legacyFallback = emptyList(),
                    codec = ModelHistoryCheckpointCodec(),
                ).messages,
            )
            val projected = projectSessionControlTail(
                snapshot = LocalHarnessSession(id = "session"),
                events = listOf(event),
                sequenceExclusive = -1L,
            )
            assertEquals(listOf("新计划"), projected.plan)
            assertEquals("完成重写", projected.goal?.description)
            assertEquals("稳定", projected.chatState.mood)
            assertEquals(7L, projected.chatContext.generation)
        }
    }

    @Test
    fun pendingExternalProjectionIsIdempotentlyCompletedAfterRewriteCommit() {
        withStores { log, memory, gallery, diary, _ ->
            memory.remember(
                content = "future-memory",
                scope = MemoryScope.GLOBAL,
                sourceSessionId = "session",
                sourceMessageId = "discarded",
            )
            diary.record(
                ChatDiaryWriteRequest(
                    subjectKey = "gallery:a",
                    personaName = "阿青",
                    delta = ChatDiaryDelta(
                        event = "用户答应周末一起去海边",
                        feeling = "我很期待",
                        innerThought = "这件事终于定下来了",
                        importance = 4,
                    ),
                    turnSignificance = "MAJOR",
                    sourceMode = ChatDiarySourceMode.DIRECT,
                    sourceSessionId = "session",
                    sourceUserMessageIds = listOf("discarded"),
                    sourceAssistantMessageIds = listOf("assistant-discarded"),
                    evidenceText = "用户答应周末一起去海边，角色说好",
                    generation = 1L,
                ),
            )
            val edited = LocalHarnessMessage("edited", "user", "新问题", createdAt = 10L)
            val modelMessage = buildJsonObject { put("role", "user"); put("content", "新问题") }
            appendTimelineRewriteCommit(
                eventLog = log,
                reason = "test",
                activeTranscript = listOf(edited),
                modelHistory = listOf(modelMessage),
                state = LocalTimelineRewriteState(
                    plan = emptyList(),
                    todos = emptyList(),
                    goal = null,
                    planMode = false,
                    chatState = ChatCharacterState(),
                    chatContext = ChatContextState(),
                    chatBranches = LocalChatBranchState(),
                    groupChat = LocalGroupChatState(),
                ),
                projection = LocalTimelineRewriteProjectionInput(
                    sourceSessionId = "session",
                    createdAtInclusive = 1L,
                    discardedMessageIds = listOf("discarded"),
                ),
                editedMessageId = edited.id,
                editedModelMessage = modelMessage,
            )

            assertTrue(recoverPendingTimelineRewriteProjection(log, memory, gallery, diary))
            assertTrue(memory.listActive(setOf(MemoryScope.GLOBAL), null, null).isEmpty())
            assertTrue(diary.listActive("gallery:a").isEmpty())
            assertFalse(recoverPendingTimelineRewriteProjection(log, memory, gallery, diary))
        }
    }

    @Test
    fun editedMessageCanBeLocatedFromRewriteCommitForAnotherHistoricalEdit() {
        withStores { log, _, _, _, _ ->
            val edited = LocalHarnessMessage("edited", "user", "新问题", createdAt = 10L)
            val modelMessage = buildJsonObject {
                put("role", "user")
                put("content", "新问题")
                put("marker", "structured")
            }
            val event = appendTimelineRewriteCommit(
                eventLog = log,
                reason = "test",
                activeTranscript = listOf(edited),
                modelHistory = listOf(modelMessage),
                state = LocalTimelineRewriteState(
                    emptyList(), emptyList(), null, false,
                    ChatCharacterState(), ChatContextState(), LocalChatBranchState(), LocalGroupChatState(),
                ),
                projection = LocalTimelineRewriteProjectionInput("session", 1L, listOf("old")),
                editedMessageId = edited.id,
                editedModelMessage = modelMessage,
            )

            assertEquals(event.sequence, sourceEventSequenceForMessage(log, edited.id))
            assertEquals(
                "structured",
                editedChatUserModelMessage(log, edited.id, "再次修改")["marker"]?.toString()?.trim('"'),
            )
        }
    }

    private fun withStores(
        block: (LocalSessionEventLog, MemoryStore, ChatPersonaGalleryStore, ChatDiaryStore, Json) -> Unit,
    ) {
        val root = createTempDir(prefix = "timeline-rewrite-")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        try {
            block(
                LocalSessionEventLog(File(root, "session.events.jsonl"), json),
                MemoryStore(File(root, "memory"), json),
                ChatPersonaGalleryStore(File(root, "gallery.json"), json),
                ChatDiaryStore(File(root, "diary"), json),
                json,
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
