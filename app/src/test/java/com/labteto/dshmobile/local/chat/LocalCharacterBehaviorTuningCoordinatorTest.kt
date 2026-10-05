package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalKernelState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
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
    fun successWaitsForSnapshotWriteAfterPersonaAndGalleryAreDurable() = runBlocking {
        val personas = ChatPersonaStore(File(temporary.root, "personas.json"), Json)
        val galleryFile = File(temporary.root, "gallery.json")
        val gallery = ChatPersonaGalleryStore(galleryFile, Json)
        val original = PersonaProfile(id = "persona", name = "阿青")
        val entry = gallery.save(original, "", emptyList(), ChatCharacterState(), "").entry
        personas.upsert(original)
        val state = MutableStateFlow(LocalHarnessState(
            loading = false, usageMode = LocalUsageMode.CHAT, sessionId = "session",
            chat = LocalChatState(
                personaId = original.id,
                chatPersona = original,
                galleryId = entry.id,
            ),
        ))
        val tuning = CharacterBehaviorTuning(intimacy = 75, updatedAt = 200)
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lock = Mutex()
        val coordinator = LocalCharacterBehaviorTuningCoordinator(
            state = state,
            personaStore = personas,
            galleryStore = gallery,
            acquireLease = {
                check(lock.tryLock())
                object : AutoCloseable {
                    override fun close() {
                        lock.unlock()
                    }
                }
            },
            persistNow = {
                assertEquals(tuning, personas.get(original.id).behaviorTuning)
                assertEquals(tuning, ChatPersonaGalleryStore(galleryFile, Json).list().single().persona.behaviorTuning)
                assertEquals(tuning, state.value.chat.chatState.behaviorTuning)
                reached.complete(Unit)
                release.await()
                true
            },
        )
        val saving = async { coordinator.configure(original.copy(behaviorTuning = tuning)) }
        withTimeout(2_000) { reached.await() }
        assertFalse(saving.isCompleted)
        assertTrue(lock.isLocked)
        release.complete(Unit)
        assertTrue(withTimeout(2_000) { saving.await() }.isSuccess)
        assertFalse(lock.isLocked)
    }

    @Test
    fun failedSnapshotWriteReturnsFailureAndReleasesTransitionLock() = runBlocking {
        val personas = ChatPersonaStore(File(temporary.root, "personas.json"), Json)
        val gallery = ChatPersonaGalleryStore(File(temporary.root, "gallery.json"), Json)
        val state = MutableStateFlow(LocalHarnessState(loading = false, usageMode = LocalUsageMode.CHAT))
        val lock = Mutex()
        val coordinator = LocalCharacterBehaviorTuningCoordinator(
            state = state,
            personaStore = personas,
            galleryStore = gallery,
            acquireLease = {
                check(lock.tryLock())
                object : AutoCloseable {
                    override fun close() {
                        lock.unlock()
                    }
                }
            },
            persistNow = { throw IOException("disk full") },
        )
        val result = coordinator.configure(PersonaProfile(behaviorTuning = CharacterBehaviorTuning(openness = 75)))
        assertTrue(result.exceptionOrNull() is IOException)
        assertFalse(lock.isLocked)
        assertFalse(state.value.chat.personaId == PersonaProfile.DEFAULT_PERSONA_ID)
        assertEquals(CharacterBehaviorTuning(), personas.get(PersonaProfile.DEFAULT_PERSONA_ID).behaviorTuning)
    }

    @Test
    fun busyRuntimeRejectsEditsBeforeAnyDurableWrite() = runBlocking {
        val personas = ChatPersonaStore(File(temporary.root, "personas.json"), Json)
        val gallery = ChatPersonaGalleryStore(File(temporary.root, "gallery.json"), Json)
        val state = MutableStateFlow(LocalHarnessState(loading = false, kernel = LocalKernelState(running = true), usageMode = LocalUsageMode.CHAT))
        var writes = 0
        val coordinator = LocalCharacterBehaviorTuningCoordinator(
            state = state,
            personaStore = personas,
            galleryStore = gallery,
            acquireLease = { object : AutoCloseable { override fun close() = Unit } },
            persistNow = { writes++; true },
        )
        assertTrue(coordinator.configure(PersonaProfile()).isFailure)
        assertEquals(listOf(PersonaProfile()), personas.list())
        assertEquals(0, writes)
    }
}
