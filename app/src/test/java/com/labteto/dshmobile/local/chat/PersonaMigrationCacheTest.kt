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

    @Test fun unchangedPersonaHotReadsDoNotDecodeButExternalReplacementInvalidates() {
        val file = File(temporary.root, "personas-v4.json")
        val marker = File(temporary.root, "personas.done").apply { writeText("v2") }
        val legacy = File(temporary.root, "legacy.json")
        var decodes = 0
        val store = ChatPersonaStore(file, json, onDocumentDecode = { decodes++ })
        store.upsert(PersonaProfile(id = "a", name = "阿青"))
        val before = decodes
        repeat(20) { assertEquals("阿青", store.get("a").name); store.list() }
        assertEquals(before, decodes)
        file.writeText(file.readText().replace("阿青", "阿红"))
        assertTrue(file.setLastModified(file.lastModified() + 2000L))
        assertEquals("阿红", store.get("a").name)
        assertTrue(decodes > before)
        assertEquals("阿红", ChatPersonaStore(file, json).get("a").name)
    }

    @Test fun galleryMigrationCachesVerifiedStateAndRechecksAfterSourceRemovalOrCorruption() {
        val file = File(temporary.root, "persona-gallery-v5.json")
        val source = json.encodeToString(GalleryDocument.serializer(), GalleryDocument())
        file.writeText(source)
        File(temporary.root, file.name + ".bak").writeText(source)
        File(temporary.root, "persona-gallery-v1-v4-to-v5.done").writeText("v5")
        var decodes = 0
        val migration = PersonaGallerySchemaMigrationCoordinator(file, json) { decodes++ }
        migration.migrateIfNeeded()
        val before = decodes
        repeat(20) { migration.migrateIfNeeded() }
        assertEquals(before, decodes)
        file.writeText("broken")
        migration.migrateIfNeeded()
        assertTrue(decodes > before)
        assertTrue(file.delete())
        val after = decodes
        migration.migrateIfNeeded()
        assertTrue(decodes > after)
    }
}
