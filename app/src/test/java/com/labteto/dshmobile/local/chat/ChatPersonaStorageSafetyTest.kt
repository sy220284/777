package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessMessage
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatPersonaStorageSafetyTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val personaFile: File
        get() = File(temporary.root, "personas.json")

    private val galleryFile: File
        get() = File(temporary.root, "persona-gallery.json")

    private fun personaStore() = ChatPersonaStore(personaFile, json)

    private fun galleryStore() = ChatPersonaGalleryStore(galleryFile, json)

    @Test
    fun corruptedPersonaPrimaryRecoversBackupBeforeNextWrite() {
        personaStore().upsert(
            PersonaProfile(
                id = "persona-a",
                name = "阿青",
                personality = "嘴硬心软",
            ),
        )
        assertTrue(File(temporary.root, "personas.json.bak").isFile)

        personaFile.writeText("{broken")
        assertEquals("阿青", personaStore().get("persona-a").name)
        assertTrue(
            temporary.root.listFiles().orEmpty()
                .any { it.name.startsWith("personas.json.corrupt-") },
        )

        personaStore().upsert(PersonaProfile(id = "persona-b", name = "小岚"))
        val ids = personaStore().list().map { it.id }.toSet()
        assertTrue("persona-a" in ids)
        assertTrue("persona-b" in ids)
    }

    @Test
    fun corruptedGalleryPrimaryRecoversArchivedCharacterBeforeNextWrite() {
        val saved = galleryStore().save(
            persona = PersonaProfile(name = "阿青", identity = "剑客"),
            sourceSessionId = "session-a",
            history = listOf(
                LocalHarnessMessage("m1", "user", "你来了", createdAt = 1L),
                LocalHarnessMessage("m2", "assistant", "嗯。", createdAt = 2L),
            ),
            chatState = ChatCharacterState(),
            notes = "桥边重逢",
        )
        assertTrue(File(temporary.root, "persona-gallery.json.bak").isFile)

        galleryFile.writeText("{broken")
        val recovered = galleryStore().list().single()
        assertEquals(saved.id, recovered.id)
        assertEquals("阿青", recovered.persona.name)
        assertTrue(
            temporary.root.listFiles().orEmpty()
                .any { it.name.startsWith("persona-gallery.json.corrupt-") },
        )

        galleryStore().save(
            persona = PersonaProfile(name = "小岚", identity = "花店店主"),
            sourceSessionId = "session-b",
            history = emptyList(),
            chatState = ChatCharacterState(),
            notes = "",
        )
        assertEquals(setOf("阿青", "小岚"), galleryStore().list().map { it.persona.name }.toSet())
    }
}
