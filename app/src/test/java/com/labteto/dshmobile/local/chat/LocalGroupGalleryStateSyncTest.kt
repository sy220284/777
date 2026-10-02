package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalGroupChatMember
import com.labteto.dshmobile.local.LocalGroupChatState
import com.labteto.dshmobile.local.projectGroupGalleryState
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalGroupGalleryStateSyncTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun currentSessionStateProjectsIdempotentlyWithoutCompensationReplay() {
        val gallery = ChatPersonaGalleryStore(File(temporary.root, "gallery.json"), json)
        val saved = gallery.save(
            persona = PersonaProfile(name = "阿青"),
            sourceSessionId = "session-a",
            history = emptyList(),
            chatState = ChatCharacterState(),
            notes = "",
        ).entry
        val state = ChatCharacterState(
            trust = 77,
            closeness = 66,
            updatedAt = 1234L,
        )
        val group = LocalGroupChatState(
            mode = LocalChatMode.GROUP,
            members = listOf(
                LocalGroupChatMember(
                    galleryId = saved.id,
                    personaId = saved.persona.id,
                    displayName = saved.persona.name,
                    persona = saved.persona,
                    chatState = state,
                ),
            ),
        )

        assertEquals(1, projectGroupGalleryState(group, gallery).projected)
        assertEquals(1, projectGroupGalleryState(group, gallery).projected)

        val projected = gallery.list().single { it.id == saved.id }.groupChatState
        assertEquals(77, projected.trust)
        assertEquals(66, projected.closeness)
    }

    @Test
    fun emptySessionMemberStateDoesNotOverwriteExistingGalleryState() {
        val gallery = ChatPersonaGalleryStore(File(temporary.root, "gallery.json"), json)
        val saved = gallery.save(
            persona = PersonaProfile(name = "阿青"),
            sourceSessionId = "session-a",
            history = emptyList(),
            chatState = ChatCharacterState(),
            notes = "",
        ).entry
        gallery.updateGroupChatState(
            saved.id,
            ChatCharacterState(trust = 88, closeness = 70, updatedAt = 2000L),
        )

        val group = LocalGroupChatState(
            mode = LocalChatMode.GROUP,
            members = listOf(
                LocalGroupChatMember(
                    galleryId = saved.id,
                    personaId = saved.persona.id,
                    displayName = saved.persona.name,
                    persona = saved.persona,
                    chatState = ChatCharacterState(),
                ),
            ),
        )

        val result = projectGroupGalleryState(group, gallery)
        assertEquals(0, result.projected)
        assertTrue(result.failures.isEmpty())
        assertEquals(88, gallery.list().single { it.id == saved.id }.groupChatState.trust)
    }
}
