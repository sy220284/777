package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.LocalChatTurnCoordinator

import com.labteto.dshmobile.local.chat.CharacterLoreEngine
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatDiaryStore
import com.labteto.dshmobile.local.chat.ChatInteractionPlanner
import com.labteto.dshmobile.local.chat.ChatPendingTurn
import com.labteto.dshmobile.local.chat.ChatPersonaStore
import com.labteto.dshmobile.local.chat.ChatRelationshipEngine
import com.labteto.dshmobile.local.chat.ChatSceneState
import com.labteto.dshmobile.local.chat.ChatTurnRunner
import com.labteto.dshmobile.local.chat.LocalChatState
import com.labteto.dshmobile.local.chat.LocalChatStatePort
import com.labteto.dshmobile.local.chat.LocalChatContextRefreshCoordinator
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.enqueuePendingDurably
import com.labteto.dshmobile.local.chat.shouldRetryChatPostTurnRequest
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalChatContextRefreshRaceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private class InterceptedFlow(val delegate: MutableStateFlow<LocalHarnessState>) :
        MutableStateFlow<LocalHarnessState> by delegate {
        var beforeCompare: (() -> Unit)? = null
        override fun compareAndSet(expect: LocalHarnessState, update: LocalHarnessState): Boolean {
            beforeCompare?.also { beforeCompare = null; it() }
            return delegate.compareAndSet(expect, update)
        }
    }
    private class Fixture(
        val log: LocalSessionEventLog, val state: InterceptedFlow,
        val coordinator: LocalChatContextRefreshCoordinator,
        val profile: LocalModelProfile,
        val observedProfiles: List<String>,
        val persisted: () -> Int,
    )
    private fun fixture(scope: CoroutineScope): Fixture {
        val log = LocalSessionEventLog(File(temporary.root, "events.jsonl"), json)
        val message = LocalHarnessMessage("a", "assistant", "reply", createdAt = 1L)
        val event = log.append("assistant/message", buildJsonObject { put("transcript", encodeTranscriptMessages(listOf(message))) })
        val context = ChatContextState(scene = ChatSceneState(location = "old")).enqueuePendingDurably(
            ChatPendingTurn(event.sequence, assistantMessageId = "a", branchHeadId = "a", assistantMessage = "reply"), log,
        )
        val state = InterceptedFlow(MutableStateFlow(LocalHarnessState(sessionId = "s", usageMode = LocalUsageMode.CHAT, chat = LocalChatState(chatContext = context))))
        val turns = LocalChatTurnCoordinator(
            ChatTurnRunner(ChatPersonaStore(File(temporary.root, "personas.json"), json), ChatRelationshipEngine(), CharacterLoreEngine()),
            ChatInteractionPlanner(json),
            object : com.labteto.dshmobile.local.project.ProjectContextPort {
                override fun activeProjectId() = "local-workspace"
                override fun instructionsFor(projectId: String?) = ""
            },
        )
        var persisted = 0
        val profile = LocalModelProfile("profile-a", "model-a", "https://example.test/v1")
        val observedProfiles = mutableListOf<String>()
        val coordinator = LocalChatContextRefreshCoordinator(
            readState = { state.value },
            chatState = localAggregateChatStatePort(state),
            chatTurnCoordinator = turns,
            diaryStore = ChatDiaryStore(File(temporary.root, "diary"), json),
            requestPlanner = { _, _, _, selectedProfile ->
                observedProfiles += selectedProfile.id
                LocalModelReply(
                    buildJsonObject {},
                    """{"state":{},"suggestions":[],"turnSignificance":"NONE"}""",
                    null,
                    emptyList(),
                )
            },
            recordUsage = { _, _ -> },
            persistBranchState = { _, _ -> },
            persistSnapshot = { persisted++ },
            scope = scope,
        )
        return Fixture(log, state, coordinator, profile, observedProfiles) { persisted }
    }
    @Test fun failedCompareFollowedBySessionSwitchCannotReportSuccessfulConsolidation() = runTest {
        val fixture = fixture(backgroundScope)
        val before = fixture.state.value
        fixture.state.beforeCompare = { fixture.state.delegate.value = before.copy(sessionId = "other") }
        fixture.coordinator.refresh(PersonaProfile(), "s", before.chat.chatState, before.chat.chatContext.generation, fixture.log, fixture.profile)
        assertEquals("other", fixture.state.value.sessionId)
        assertEquals(before.chat.chatContext.processedThroughSequence, fixture.state.value.chat.chatContext.processedThroughSequence)
        assertEquals("stale-discarded", fixture.log.latest("chat/post-turn")?.data?.get("status")?.jsonPrimitive?.content)
        assertEquals(0, fixture.persisted())
    }
    @Test fun compareRetryKeepsTurnAndSceneAddedWhileConsolidationWasCommitting() = runTest {
        val fixture = fixture(backgroundScope)
        val before = fixture.state.value
        fixture.state.beforeCompare = {
            val event = fixture.log.append("assistant/message", buildJsonObject {})
            val context = before.chat.chatContext.enqueuePendingDurably(
                ChatPendingTurn(event.sequence, assistantMessageId = "new", assistantMessage = "new fact"), fixture.log,
            ).copy(scene = ChatSceneState(location = "new"))
            fixture.state.delegate.value = before.copy(chat = before.chat.copy(chatContext = context))
        }
        fixture.coordinator.refresh(PersonaProfile(), "s", before.chat.chatState, before.chat.chatContext.generation, fixture.log, fixture.profile)
        assertEquals("new", fixture.state.value.chat.chatContext.scene.location)
        assertEquals(listOf("new"), fixture.state.value.chat.chatContext.pendingTurns.map { it.assistantMessageId })
        assertEquals(0L, fixture.state.value.chat.chatContext.processedThroughSequence)
        assertEquals(1, fixture.persisted())
    }
    @Test fun refreshPassesTheFrozenProfileToThePlanner() = runTest {
        val fixture = fixture(backgroundScope)
        val before = fixture.state.value
        fixture.coordinator.refresh(
            PersonaProfile(), "s", before.chat.chatState, before.chat.chatContext.generation,
            fixture.log, fixture.profile,
        )
        assertEquals(listOf("profile-a"), fixture.observedProfiles)
    }

    @Test fun remainingPendingContinuationCannotResetTheRetryBudgetForever() = runTest {
        val fixture = fixture(backgroundScope)
        val before = fixture.state.value
        var context = before.chat.chatContext
        repeat(39) { index ->
            val id = "extra-$index"
            val message = LocalHarnessMessage(id, "assistant", "reply-$index", createdAt = index + 2L)
            val event = fixture.log.append("assistant/message", buildJsonObject {
                put("transcript", encodeTranscriptMessages(listOf(message)))
            })
            context = context.enqueuePendingDurably(
                ChatPendingTurn(
                    event.sequence,
                    assistantMessageId = id,
                    branchHeadId = id,
                    assistantMessage = "reply-$index",
                ),
                fixture.log,
            )
        }
        fixture.state.delegate.value = before.copy(chat = before.chat.copy(chatContext = context))

        fixture.coordinator.refresh(
            PersonaProfile(), "s", before.chat.chatState, context.generation, fixture.log, fixture.profile,
        )
        advanceUntilIdle()

        val continuationRetries = fixture.log.snapshot().count { event ->
            event.type == "chat/post-turn" &&
                event.data["status"]?.jsonPrimitive?.content == "retrying" &&
                event.data["reason"]?.jsonPrimitive?.content == "remaining-pending"
        }
        assertTrue("连续重排必须受同一预算约束", continuationRetries <= 3)
    }

    @Test fun terminalProviderFailureDoesNotEnterDetachedPostTurnRetryLoop() {
        assertFalse(shouldRetryChatPostTurnRequest(
            LocalModelException("CHATGPT_PLAN_LIMIT_REACHED", "limit", false),
        ))
        assertTrue(shouldRetryChatPostTurnRequest(
            LocalModelException("MODEL_NETWORK", "network", true),
        ))
        assertTrue(shouldRetryChatPostTurnRequest(IOException("socket reset")))
        assertFalse(shouldRetryChatPostTurnRequest(IllegalStateException("bad state")))
    }

    @Test fun enqueueRetryAfterSessionSwitchReturnsNoGenerationAndDoesNotPersistNewSession() = runTest {
        val fixture = fixture(backgroundScope)
        val before = fixture.state.value
        fixture.state.beforeCompare = { fixture.state.delegate.value = before.copy(sessionId = "other") }
        val generation = fixture.coordinator.enqueue("question", "reply", "s", "a", fixture.log, "u")
        assertNull(generation)
        assertEquals(0, fixture.persisted())
    }
}
