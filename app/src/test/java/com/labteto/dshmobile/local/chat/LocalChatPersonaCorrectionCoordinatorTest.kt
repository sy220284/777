package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalChatPersonaCorrectionCoordinatorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun undoPersistsPersonaAndAuditsAgainstOriginatingSession() = runTest {
        val personaStore = ChatPersonaStore(
            file = File(temporary.root, "personas.json"),
            json = json,
        )
        val correction = "我不会喝咖啡"
        val persona = personaStore.upsert(
            PersonaProfile(
                id = "persona-1",
                name = "测试角色",
                corrections = listOf(correction),
            ),
        )
        val notice = ChatPersonaCorrectionNotice(
            id = 7L,
            personaId = persona.id,
            correction = correction,
        )
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(
            LocalHarnessState(
                sessionId = "session-a",
                usageMode = LocalUsageMode.CHAT,
                chat = LocalChatState(
                    personaId = persona.id,
                    chatPersona = persona,
                    personaCorrectionNotice = notice,
                ),
            ),
        )
        val logs = LocalSessionEventLogRegistry(
            sessionsRoot = File(temporary.root, "sessions").apply { mkdirs() },
            json = json,
        )
        val coordinator = LocalChatPersonaCorrectionCoordinator(LocalChatStatePort(runtime), personaStore, logs)

        coordinator.undo(notice.id, notice.personaId, notice.correction)

        assertTrue(personaStore.get(persona.id).corrections.isEmpty())
        assertTrue(runtime.state.value.chat.chatPersona.corrections.isEmpty())
        assertNull(runtime.state.value.chat.personaCorrectionNotice)
        val event = requireNotNull(logs.get("session-a").latest("chat/persona-correction"))
        assertEquals("undo", event.data["action"]?.jsonPrimitive?.contentOrNull)
        assertEquals(persona.id, event.data["persona_id"]?.jsonPrimitive?.contentOrNull)

        logs.clearAndEvict(setOf("session-a"))
    }

    @Test
    fun mismatchedNoticeDoesNotMutatePersonaOrWriteAuditEvent() = runTest {
        val personaStore = ChatPersonaStore(
            file = File(temporary.root, "personas-mismatch.json"),
            json = json,
        )
        val correction = "我不会喝咖啡"
        val persona = personaStore.upsert(
            PersonaProfile(
                id = "persona-2",
                name = "测试角色二",
                corrections = listOf(correction),
            ),
        )
        val runtime = LocalRuntimeStateStore()
        runtime.initialize(
            LocalHarnessState(
                sessionId = "session-b",
                usageMode = LocalUsageMode.CHAT,
                chat = LocalChatState(
                    personaId = persona.id,
                    chatPersona = persona,
                    personaCorrectionNotice = ChatPersonaCorrectionNotice(
                        id = 9L,
                        personaId = persona.id,
                        correction = correction,
                    ),
                ),
            ),
        )
        val logs = LocalSessionEventLogRegistry(
            sessionsRoot = File(temporary.root, "sessions-mismatch").apply { mkdirs() },
            json = json,
        )
        val coordinator = LocalChatPersonaCorrectionCoordinator(LocalChatStatePort(runtime), personaStore, logs)

        coordinator.undo(10L, persona.id, correction)

        assertEquals(listOf(correction), personaStore.get(persona.id).corrections)
        assertNull(logs.get("session-b").latest("chat/persona-correction"))
        logs.clearAndEvict(setOf("session-b"))
    }
}
