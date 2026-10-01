package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.*
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
        val state = InterceptedFlow(MutableStateFlow(LocalHarnessState(sessionId = "s", usageMode = LocalUsageMode.CHAT, chatContext = context)))
        val turns = LocalChatTurnCoordinator(
            ChatTurnRunner(ChatPersonaStore(File(temporary.root, "personas.json"), json), ChatRelationshipEngine(), CharacterLoreEngine()),
            ChatInteractionPlanner(json),
        )
        var persisted = 0
        val profile = LocalModelProfile("profile-a", "model-a", "https://example.test/v1")
        val observedProfiles = mutableListOf<String>()
        val coordinator = LocalChatContextRefreshCoordinator(
            state, scope, turns,
            requestPlanner = { _, _, _, selectedProfile ->
                observedProfiles += selectedProfile.id
                LocalModelReply(
                buildJsonObject {}, """{"state":{},"suggestions":[],"turnSignificance":"NONE"}""", null, emptyList(),
            ) },
            recordUsage = { _, _ -> }, persistBranchState = {}, persist = { persisted++ },
        )
        return Fixture(log, state, coordinator, profile, observedProfiles) { persisted }
    }
    @Test fun failedCompareFollowedBySessionSwitchCannotReportSuccessfulConsolidation() = runTest {
        val fixture = fixture(backgroundScope)
        val before = fixture.state.value
        fixture.state.beforeCompare = { fixture.state.delegate.value = before.copy(sessionId = "other") }
        fixture.coordinator.refresh(PersonaProfile(), "s", before.chatState, before.chatContext.generation, fixture.log, fixture.profile)
        assertEquals("other", fixture.state.value.sessionId)
        assertEquals(before.chatContext.processedThroughSequence, fixture.state.value.chatContext.processedThroughSequence)
        assertEquals("stale-discarded", fixture.log.latest("chat/post-turn")?.data?.get("status")?.jsonPrimitive?.content)
        assertEquals(0, fixture.persisted())
    }
    @Test fun compareRetryKeepsTurnAndSceneAddedWhileConsolidationWasCommitting() = runTest {
        val fixture = fixture(backgroundScope)
        val before = fixture.state.value
        fixture.state.beforeCompare = {
            val event = fixture.log.append("assistant/message", buildJsonObject {})
            val context = before.chatContext.enqueuePendingDurably(
                ChatPendingTurn(event.sequence, assistantMessageId = "new", assistantMessage = "new fact"), fixture.log,
            ).copy(scene = ChatSceneState(location = "new"))
            fixture.state.delegate.value = before.copy(chatContext = context)
        }
        fixture.coordinator.refresh(PersonaProfile(), "s", before.chatState, before.chatContext.generation, fixture.log, fixture.profile)
        assertEquals("new", fixture.state.value.chatContext.scene.location)
        assertEquals(listOf("new"), fixture.state.value.chatContext.pendingTurns.map { it.assistantMessageId })
        assertEquals(0L, fixture.state.value.chatContext.processedThroughSequence)
        assertEquals(1, fixture.persisted())
    }
    @Test fun refreshPassesTheFrozenProfileToThePlanner() = runTest {
        val fixture = fixture(backgroundScope)
        val before = fixture.state.value
        fixture.coordinator.refresh(
            PersonaProfile(), "s", before.chatState, before.chatContext.generation,
            fixture.log, fixture.profile,
        )
        assertEquals(listOf("profile-a"), fixture.observedProfiles)
    }

    @Test fun remainingPendingContinuationCannotResetTheRetryBudgetForever() = runTest {
        val fixture = fixture(backgroundScope)
        val before = fixture.state.value
        var context = before.chatContext
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
        fixture.state.delegate.value = before.copy(chatContext = context)

        fixture.coordinator.refresh(
            PersonaProfile(), "s", before.chatState, context.generation, fixture.log, fixture.profile,
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
