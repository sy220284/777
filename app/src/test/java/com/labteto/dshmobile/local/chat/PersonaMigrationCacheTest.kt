package com.labteto.dshmobile.local.chat

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonaMigrationCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun unchangedV4PersonaHotReadsDoNotDecodeButExternalReplacementInvalidates() {
        val file = File(temporary.root, "personas-v4.json")
        var decodes = 0
        val store = ChatPersonaStore(file, json, onDocumentDecode = { decodes++ })
        store.upsert(PersonaProfile(id = "a", name = "阿青", coreIdentity = "原作人物"))
        val before = decodes
        repeat(20) { assertEquals("阿青", store.get("a").name); store.list() }
        assertEquals(before, decodes)
        file.writeText(file.readText().replace("阿青", "阿红"))
        assertTrue(file.setLastModified(file.lastModified() + 2000L))
        assertEquals("阿红", store.get("a").name)
        assertTrue(decodes > before)
        assertEquals("阿红", ChatPersonaStore(file, json).get("a").name)
    }

    @Test fun independentGalleryDoesNotImportOldPathOrSessions() {
        val oldFile = File(temporary.root, "persona-gallery-v5.json")
        oldFile.writeText(json.encodeToString(GalleryDocument.serializer(), GalleryDocument()))
        val v4 = File(temporary.root, "persona-gallery-v6.json")
        val gallery = ChatPersonaGalleryStore(v4, json)
        assertTrue(gallery.list().isEmpty())
        assertTrue(oldFile.isFile)
    }
}
