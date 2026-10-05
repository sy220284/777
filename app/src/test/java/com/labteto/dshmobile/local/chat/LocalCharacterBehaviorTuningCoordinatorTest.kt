package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalKernelState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalCharacterBehaviorTuningCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun successCommitsDomainEventBeforePublishingRuntimeProjection() = runBlocking {
        val personas = ChatPersonaStore(File(temporary.root, "personas.json"), Json)
        val galleryFile = File(temporary.root, "gallery.json")
        val gallery = ChatPersonaGalleryStore(galleryFile, Json)
        val original = PersonaProfile(id = "persona", name = "阿青")
        val entry = gallery.save(original, "", emptyList(), ChatCharacterState(), "").entry
        personas.upsert(original)
        val state = MutableStateFlow(LocalHarnessState(
            loading = false,
            usageMode = LocalUsageMode.CHAT,
            sessionId = "session",
            chat = LocalChatState(
                personaId = original.id,
                chatPersona = original,
                galleryId = entry.id,
            ),
        ))
        val tuning = CharacterBehaviorTuning(intimacy = 75, updatedAt = 200)
        var leaseHeld = false
        var commits = 0
        val coordinator = LocalCharacterBehaviorTuningCoordinator(
            state = LocalChatStatePort(state),
            personaStore = personas,
            galleryStore = gallery,
            acquireLease = {
                check(!leaseHeld)
                leaseHeld = true
                object : AutoCloseable {
                    override fun close() {
                        leaseHeld = false
                    }
                }
            },
            commitDomainState = { committed, _ ->
                assertTrue(leaseHeld)
                assertEquals(tuning, personas.get(original.id).behaviorTuning)
                assertEquals(
                    tuning,
                    ChatPersonaGalleryStore(galleryFile, Json).list().single().persona.behaviorTuning,
                )
                assertEquals(CharacterBehaviorTuning(), state.value.chat.chatState.behaviorTuning)
                assertEquals(tuning, committed.chat.chatState.behaviorTuning)
                commits++
            },
            enqueueSnapshot = { true },
        )

        assertTrue(coordinator.configure(original.copy(behaviorTuning = tuning)).isSuccess)
        assertEquals(1, commits)
        assertEquals(tuning, state.value.chat.chatState.behaviorTuning)
        assertFalse(leaseHeld)
    }

    @Test
    fun failedDomainCommitRestoresPersonaAndGalleryAndLeavesStateUntouched() = runBlocking {
        val personas = ChatPersonaStore(File(temporary.root, "personas.json"), Json)
        val galleryFile = File(temporary.root, "gallery.json")
        val gallery = ChatPersonaGalleryStore(galleryFile, Json)
        val original = PersonaProfile(id = "persona", name = "阿青")
        personas.upsert(original)
        val entry = gallery.save(original, "", emptyList(), ChatCharacterState(), "").entry
        val state = MutableStateFlow(LocalHarnessState(
            loading = false,
            usageMode = LocalUsageMode.CHAT,
            sessionId = "session",
            chat = LocalChatState(
                personaId = original.id,
                chatPersona = original,
                galleryId = entry.id,
            ),
        ))
        val coordinator = LocalCharacterBehaviorTuningCoordinator(
            state = LocalChatStatePort(state),
            personaStore = personas,
            galleryStore = gallery,
            acquireLease = { object : AutoCloseable { override fun close() = Unit } },
            commitDomainState = { _, _ -> throw IOException("disk full") },
            enqueueSnapshot = { error("权威事件失败后不得写快照") },
        )

        val result = coordinator.configure(
            original.copy(behaviorTuning = CharacterBehaviorTuning(openness = 75)),
        )

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(CharacterBehaviorTuning(), state.value.chat.chatState.behaviorTuning)
        assertEquals(CharacterBehaviorTuning(), personas.get(original.id).behaviorTuning)
        assertEquals(
            CharacterBehaviorTuning(),
            ChatPersonaGalleryStore(galleryFile, Json).list().single().persona.behaviorTuning,
        )
    }

    @Test
    fun busyRuntimeRejectsEditsBeforeAnyDurableWrite() = runBlocking {
        val personas = ChatPersonaStore(File(temporary.root, "personas.json"), Json)
        val gallery = ChatPersonaGalleryStore(File(temporary.root, "gallery.json"), Json)
        val state = MutableStateFlow(LocalHarnessState(
            loading = false,
            kernel = LocalKernelState(running = true),
            usageMode = LocalUsageMode.CHAT,
        ))
        var commits = 0
        val coordinator = LocalCharacterBehaviorTuningCoordinator(
            state = LocalChatStatePort(state),
            personaStore = personas,
            galleryStore = gallery,
            acquireLease = { object : AutoCloseable { override fun close() = Unit } },
            commitDomainState = { _, _ -> commits++ },
            enqueueSnapshot = { true },
        )

        assertTrue(coordinator.configure(PersonaProfile()).isFailure)
        assertEquals(listOf(PersonaProfile()), personas.list())
        assertEquals(0, commits)
    }
}
