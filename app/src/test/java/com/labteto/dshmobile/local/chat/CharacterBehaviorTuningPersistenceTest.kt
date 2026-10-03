package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalGroupChatMember
import com.labteto.dshmobile.local.LocalGroupChatState
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CharacterBehaviorTuningPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun newerGalleryChoiceWinsStaleConversationState() {
        val stale = CharacterBehaviorTuning(intimacy = 25, updatedAt = 10)
        val latest = CharacterBehaviorTuning(intimacy = 75, updatedAt = 20)
        assertEquals(latest, resolveCharacterBehaviorTuning(stale, stale, latest))
    }

    @Test
    fun legacyCustomChoiceIsNotErasedByUntouchedDefaults() {
        val legacyCustom = CharacterBehaviorTuning(initiative = 75)
        assertEquals(
            legacyCustom,
            resolveCharacterBehaviorTuning(
                legacyCustom,
                CharacterBehaviorTuning(),
                CharacterBehaviorTuning(),
            ),
        )
    }

    @Test
    fun oldGroupConversationUsesLatestGalleryTuning() {
        val galleryStore = ChatPersonaGalleryStore(File(temporary.root, "group-gallery.json"), Json)
        val personaStore = ChatPersonaStore(File(temporary.root, "personas.json"), Json)
        val latest = CharacterBehaviorTuning(persistence = 75, updatedAt = 200)
        val entry = galleryStore.save(
            persona = PersonaProfile(name = "阿青", behaviorTuning = latest),
            sourceSessionId = "",
            history = emptyList(),
            chatState = ChatCharacterState(),
            notes = "",
        ).entry
        val stale = CharacterBehaviorTuning(persistence = 25, updatedAt = 100)
        val group = LocalGroupChatState(
            mode = LocalChatMode.GROUP,
            members = listOf(
                LocalGroupChatMember(
                    galleryId = entry.id,
                    personaId = entry.id,
                    displayName = "阿青",
                    persona = entry.persona.copy(behaviorTuning = stale),
                    chatState = ChatCharacterState(behaviorTuning = stale),
                ),
            ),
        )

        val restored = reconcileGroupCharacterBehaviorTuning(group, personaStore, galleryStore)
        assertEquals(latest, restored.members.single().persona.behaviorTuning)
        assertEquals(latest, restored.members.single().chatState.behaviorTuning)
        assertEquals(latest, personaStore.get(entry.id).behaviorTuning)
    }

    @Test
    fun galleryChoiceSurvivesStoreRecreationAndRejectsOlderWrite() {
        val file = File(temporary.root, "gallery.json")
        val store = ChatPersonaGalleryStore(file, Json)
        val entry = store.save(
            persona = PersonaProfile(name = "阿青"),
            sourceSessionId = "",
            history = emptyList(),
            chatState = ChatCharacterState(),
            notes = "",
        ).entry
        val latest = CharacterBehaviorTuning(openness = 75, updatedAt = 200)
        val stale = CharacterBehaviorTuning(openness = 25, updatedAt = 100)

        assertEquals(latest, persistGalleryBehaviorTuning(store, entry.id, latest))
        assertEquals(latest, persistGalleryBehaviorTuning(store, entry.id, stale))
        assertEquals(
            latest,
            ChatPersonaGalleryStore(file, Json).list().single().persona.behaviorTuning,
        )
    }
}
