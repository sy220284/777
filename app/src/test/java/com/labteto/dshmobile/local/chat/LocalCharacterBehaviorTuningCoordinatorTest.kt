package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.localAggregateChatStatePort

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
            state = localAggregateChatStatePort(state),
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
                val savedTuning = personas.get(original.id).behaviorTuning
                assertEquals(tuning.intimacy, savedTuning.intimacy)
                assertTrue(savedTuning.updatedAt > tuning.updatedAt)
                assertEquals(
                    savedTuning,
                    ChatPersonaGalleryStore(galleryFile, Json).list().single().persona.behaviorTuning,
                )
                assertEquals(CharacterBehaviorTuning(), state.value.chat.chatState.behaviorTuning)
                assertEquals(savedTuning, committed.chat.chatState.behaviorTuning)
                commits++
            },
            enqueueSnapshot = { true },
        )

        assertTrue(coordinator.configure(original.copy(behaviorTuning = tuning)).isSuccess)
        assertEquals(1, commits)
        assertEquals(personas.get(original.id).behaviorTuning, state.value.chat.chatState.behaviorTuning)
        assertFalse(leaseHeld)
    }

    @Test
    fun composerTemperatureDescendingFromMaximumStaysSyncedAcrossPersonaGalleryAndRestore() = runBlocking {
        val personas = ChatPersonaStore(File(temporary.root, "temperature-personas.json"), Json)
        val gallery = ChatPersonaGalleryStore(File(temporary.root, "temperature-gallery.json"), Json)
        val maximum = CharacterBehaviorTuning(expressionVariation = 100, updatedAt = 10_000L)
        val original = PersonaProfile(id = "persona", name = "阿青", behaviorTuning = maximum)
        personas.upsert(original)
        val entry = gallery.save(original, "", emptyList(), ChatCharacterState(), "").entry
        val state = MutableStateFlow(LocalHarnessState(
            loading = false,
            usageMode = LocalUsageMode.CHAT,
            sessionId = "temperature-session",
            chat = LocalChatState(
                personaId = original.id,
                galleryId = entry.id,
                chatPersona = original,
                chatState = ChatCharacterState(behaviorTuning = maximum),
            ),
        ))
        val coordinator = LocalCharacterBehaviorTuningCoordinator(
            state = localAggregateChatStatePort(state),
            personaStore = personas,
            galleryStore = gallery,
            acquireLease = { object : AutoCloseable { override fun close() = Unit } },
            commitDomainState = { _, _ -> },
            enqueueSnapshot = { true },
        )
        var lastTimestamp = maximum.updatedAt
        // The composer intentionally reuses the previous tuning timestamp. Every explicit
        // descent, including the natural midpoint, must nevertheless outrank the old MAX.
        for (level in listOf(3, 2, 1, 0)) {
            val current = state.value.chat
            val changed = current.chatState.behaviorTuning.withComposerTemperatureLevel(level)
            assertTrue(coordinator.configure(current.chatPersona.copy(behaviorTuning = changed)).isSuccess)
            val currentTuning = state.value.chat.chatState.behaviorTuning
            assertEquals(level * 25, currentTuning.expressionVariation)
            assertTrue(currentTuning.updatedAt > lastTimestamp)
            lastTimestamp = currentTuning.updatedAt
            assertEquals(currentTuning, state.value.chat.chatPersona.behaviorTuning)
            assertEquals(currentTuning, personas.get(original.id).behaviorTuning)
            assertEquals(currentTuning, gallery.findEntry(entry.id)!!.persona.behaviorTuning)

            // The post-reply restore/reconcile path must not resurrect the old maximum.
            val restored = reconcileCharacterBehaviorTuning(
                personas, gallery, original.id, entry.id, state.value.chat.chatState,
            )
            assertEquals(level * 25, restored.chatState.behaviorTuning.expressionVariation)
            assertEquals(currentTuning, restored.persona.behaviorTuning)
        }
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
            state = localAggregateChatStatePort(state),
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
            state = localAggregateChatStatePort(state),
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
