package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.session.LocalSessionCoordinator
import com.labteto.dshmobile.local.session.LocalSessionRepository

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatReplySuggestion
import com.labteto.dshmobile.local.chat.ChatSceneState
import com.labteto.dshmobile.local.chat.LocalChatBranchNode
import com.labteto.dshmobile.local.chat.LocalChatBranchState
import com.labteto.dshmobile.local.chat.LocalChatMode
import com.labteto.dshmobile.local.chat.LocalChatSessionDomainCodec
import com.labteto.dshmobile.local.chat.LocalChatState
import com.labteto.dshmobile.local.chat.LocalGroupChatMember
import com.labteto.dshmobile.local.chat.LocalGroupChatState
import com.labteto.dshmobile.local.chat.chatBranches
import com.labteto.dshmobile.local.chat.chatContext
import com.labteto.dshmobile.local.chat.chatState
import com.labteto.dshmobile.local.chat.groupChat
import com.labteto.dshmobile.local.chat.withChatSessionDomain
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex
import com.labteto.dshmobile.local.session.LocalSessionSnapshotBoundary
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalTodoItem
import com.labteto.dshmobile.local.work.LocalWorkState
import com.labteto.dshmobile.local.work.LocalWorkSessionDomainCodec
import com.labteto.dshmobile.local.work.goal
import com.labteto.dshmobile.local.work.todos
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
            domainCodecs = listOf(LocalChatSessionDomainCodec),
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
            domainCodecs = listOf(LocalChatSessionDomainCodec),
        )
        repository.writeNow(
            LocalHarnessSession(id = "legacy-chat").withChatSessionDomain(
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
            domainCodecs = listOf(LocalChatSessionDomainCodec),
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

        val runtime = com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore()
        runtime.initialize(state)
        val snapshot = requireNotNull(
            LocalCurrentSessionSnapshotComposition(runtime).snapshot(
                expectedSessionId = "s1",
                boundary = LocalSessionSnapshotBoundary(
                    controlProjectedThroughSequence = 8L,
                    transcriptProjectedThroughSequence = 7L,
                ),
                runtimeWindowMessages = 2,
            ),
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
            repository,
            { id -> LocalSessionEventLog(File(sessions, "$id.events.jsonl"), json) },
            2,
            listOf(LocalChatSessionDomainCodec),
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
        val runtime = com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore()
        runtime.initialize(LocalHarnessState(sessionId = "round-trip", chat = chat, work = work))
        val snapshot = requireNotNull(
            LocalCurrentSessionSnapshotComposition(runtime).snapshot(
                expectedSessionId = "round-trip",
                boundary = LocalSessionSnapshotBoundary(
                    controlProjectedThroughSequence = 11L,
                    transcriptProjectedThroughSequence = 10L,
                ),
                runtimeWindowMessages = 2,
            ),
        )
        coordinator.writeNow(snapshot)

        // A fresh repository must decode disk, rather than returning the writer's cached snapshot.
        val coldRepository = LocalSessionRepository(sessions, json, backgroundScope, {}, {})
        val coldCoordinator = LocalSessionCoordinator(
            coldRepository,
            { id -> LocalSessionEventLog(File(sessions, "$id.events.jsonl"), json) },
            2,
            listOf(LocalChatSessionDomainCodec, LocalWorkSessionDomainCodec),
        )
        val restored = requireNotNull(coldCoordinator.read("round-trip"))
        assertEquals(snapshot, restored)
        assertEquals(chat.chatBranches, restored.chatBranches)
        assertEquals(chat.groupChat, restored.groupChat)
        assertEquals(chat.gallerySaveSuppressedThrough, restored.gallerySaveSuppressedThrough)
        // Durable storage keeps the stable Session schema while runtime state is domain-owned.
        val encoded = json.encodeToString(LocalHarnessSession.serializer(), restored)
        assertTrue(encoded.contains("\"personaId\""))
        assertTrue(!encoded.contains("\"chat\":") && !encoded.contains("\"work\":"))
    }


    private fun message(id: String, role: String, content: String) = LocalHarnessMessage(
        id = id,
        role = role,
        content = content,
        createdAt = id.removePrefix("m").toLongOrNull() ?: 1L,
    )

}
