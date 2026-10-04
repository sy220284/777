package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.work.LocalWorkState
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatSceneState
import com.labteto.dshmobile.local.chat.LocalChatState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatReplySuggestion
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSessionCoordinatorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun legacyFullSnapshotRestoresOnlyBoundedRuntimeWindow() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val sessions = temporary.newFolder("sessions")
        val repository = LocalSessionRepository(sessions, json, backgroundScope, {}, {})
        val coordinator = LocalSessionCoordinator(
            repository = repository,
            eventLogFor = { id -> LocalSessionEventLog(File(sessions, "$id.events.jsonl"), json) },
            runtimeWindowMessages = 2,
        )
        val legacy = LocalHarnessSession(
            id = "legacy",
            messages = (0 until 5).map { index ->
                message("m$index", if (index % 2 == 0) "user" else "assistant", "消息$index")
            },
            transcriptIndex = LocalTranscriptRuntimeIndex(),
        )

        val restored = coordinator.restoreTranscript(legacy, persistedSnapshotExists = true)

        assertEquals(listOf("m3", "m4"), restored.messages.map { it.id })
        assertEquals(5L, restored.index.totalMessageCount)
        assertTrue(restored.needsPersist)
    }

    @Test
    fun readCanonicalizesLegacyChatContextAtPersistenceBoundary() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val sessions = temporary.newFolder("legacy-chat-context")
        val repository = LocalSessionRepository(sessions, json, backgroundScope, {}, {})
        val coordinator = LocalSessionCoordinator(
            repository = repository,
            eventLogFor = { id -> LocalSessionEventLog(File(sessions, "$id.events.jsonl"), json) },
            runtimeWindowMessages = 2,
        )
        repository.writeNow(
            LocalHarnessSession(
                id = "legacy-chat",
                chatState = ChatCharacterState(scene = ChatSceneState(location = "庭院")),
            ),
        )

        val restored = requireNotNull(coordinator.read("legacy-chat"))

        assertEquals("庭院", restored.chatContext.scene.location)
        assertTrue(restored.chatState.scene.location.isBlank())
    }

    @Test
    fun snapshotNeverPersistsCompleteRuntimeMessages() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val sessions = temporary.newFolder("snapshot-sessions")
        val repository = LocalSessionRepository(sessions, json, backgroundScope, {}, {})
        val coordinator = LocalSessionCoordinator(
            repository = repository,
            eventLogFor = { id -> LocalSessionEventLog(File(sessions, "$id.events.jsonl"), json) },
            runtimeWindowMessages = 2,
        )
        val state = LocalHarnessState(
            loading = false,
            sessionId = "s1",
            messages = listOf(
                message("m1", "user", "一"),
                message("m2", "assistant", "二"),
                message("m3", "user", "三"),
            ),
            transcriptIndex = LocalTranscriptRuntimeIndex(
                latestCreatedAt = 123_456L,
                totalMessageCount = 3L,
            ),
            chat = LocalChatState(
                personaId = "persona-test",
                galleryId = "gallery-test",
                chatState = ChatCharacterState(mood = "专注"),
            ),
            work = LocalWorkState(
                plan = listOf("检查边界"),
                todos = listOf(LocalTodoItem("补回归", "in_progress")),
                goal = LocalGoal("完成架构迁移"),
                planMode = true,
            ),
        )

        val snapshot = coordinator.snapshot(
            sessionId = "s1",
            state = state,
            controlProjectedThroughSequence = 8L,
            transcriptProjectedThroughSequence = 7L,
        )

        assertTrue(snapshot.messages.isEmpty())
        assertEquals(listOf("m2", "m3"), snapshot.transcriptWindow.map { it.id })
        assertEquals(3L, snapshot.transcriptIndex.totalMessageCount)
        assertEquals(123_456L, snapshot.updatedAt)
        assertEquals("persona-test", snapshot.personaId)
        assertEquals("gallery-test", snapshot.galleryId)
        assertEquals("专注", snapshot.chatState.mood)
        assertEquals(listOf("检查边界"), snapshot.plan)
        assertEquals(listOf(LocalTodoItem("补回归", "in_progress")), snapshot.todos)
        assertEquals(LocalGoal("完成架构迁移"), snapshot.goal)
        assertTrue(snapshot.planMode)
        assertEquals(8L, snapshot.controlProjectedThroughSequence)
        assertEquals(7L, snapshot.transcriptProjectedThroughSequence)
    }

    @Test
    fun nestedDomainStateSurvivesDurableSessionRoundTrip() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val sessions = temporary.newFolder("domain-round-trip")
        val repository = LocalSessionRepository(sessions, json, backgroundScope, {}, {})
        val coordinator = LocalSessionCoordinator(
            repository, { id -> LocalSessionEventLog(File(sessions, "$id.events.jsonl"), json) }, 2,
        )
        val context = ChatContextState(scene = ChatSceneState(location = "庭院"), generation = 7L)
        val suggestions = listOf(ChatReplySuggestion("继续", "然后呢？"))
        val chat = LocalChatState(
            personaId = "persona", galleryId = "gallery", galleryStoryId = "story",
            gallerySaveSuppressedThrough = 42L,
            chatState = ChatCharacterState(mood = "专注"), chatContext = context,
            replySuggestions = suggestions,
            chatBranches = LocalChatBranchState(nodes = listOf(LocalChatBranchNode(
                message = message("reply", "assistant", "回复"),
                chatStateAfter = ChatCharacterState(mood = "专注"),
                chatContextAfter = context, replySuggestionsAfter = suggestions,
            ))),
            groupChat = LocalGroupChatState(
                mode = LocalChatMode.GROUP,
                members = listOf(LocalGroupChatMember("gallery", "persona", "成员")),
                context = context, announcement = "共同探索", failedReplyMemberIds = listOf("gallery"),
            ),
        )
        val work = LocalWorkState(
            plan = listOf("检查"), todos = listOf(LocalTodoItem("恢复", "in_progress")),
            goal = LocalGoal("完整恢复"), planMode = true,
        )
        val snapshot = coordinator.snapshot(
            "round-trip", LocalHarnessState(chat = chat, work = work), 11L, 10L,
        )
        coordinator.writeNow(snapshot)

        val restored = requireNotNull(coordinator.read("round-trip"))
        assertEquals(snapshot, restored)
        assertEquals(chat.chatBranches, restored.chatBranches)
        assertEquals(chat.groupChat, restored.groupChat)
        assertEquals(chat.gallerySaveSuppressedThrough, restored.gallerySaveSuppressedThrough)
        // Durable storage keeps the stable Session schema while runtime state is domain-owned.
        val encoded = json.encodeToString(LocalHarnessSession.serializer(), restored)
        assertTrue(encoded.contains("\"personaId\""))
        assertTrue(!encoded.contains("\"chat\":") && !encoded.contains("\"work\":"))
    }

    @Test
    fun persistenceProjectionCapturesCursorBeforeReadingForegroundState() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val sessions = temporary.newFolder("cursor-order")
        val log = LocalSessionEventLog(File(sessions, "s1.events.jsonl"), json)
        val repository = LocalSessionRepository(sessions, json, backgroundScope, {}, {})
        val coordinator = LocalSessionCoordinator(repository, { log }, 2)
        val capturedSequence = log.latestSequence()
        var stateReads = 0

        val snapshot = localSessionPersistenceSnapshot(
            sessionCoordinator = coordinator,
            currentSessionId = "s1",
            currentState = {
                stateReads++
                // Simulate a control update landing at the state-read boundary. Its event must
                // remain replayable, rather than being skipped by a cursor newer than the state.
                log.append("plan/state", kotlinx.serialization.json.buildJsonObject {})
                LocalHarnessState(work = LocalWorkState(plan = listOf("新计划")))
            },
            eventLog = log,
            transcriptProjectionCursor = 0L,
        )

        assertEquals(1, stateReads)
        assertEquals(capturedSequence, snapshot.controlProjectedThroughSequence)
        assertTrue(log.latestSequence() > snapshot.controlProjectedThroughSequence!!)
        assertEquals(listOf("新计划"), snapshot.plan)
    }

    private fun message(id: String, role: String, content: String) = LocalHarnessMessage(
        id = id,
        role = role,
        content = content,
        createdAt = id.hashCode().toLong(),
    )
}
