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
    fun oldPersonaSchemaMigratesIntoCharacterLifeFieldsWithoutOverwritingSource() {
        val legacyFile = File(temporary.root, "personas-v1.json")
        val marker = File(temporary.root, "personas-v1-to-v2.done")
        legacyFile.writeText(
            json.encodeToString(
                LegacyPersonaDocumentV1.serializer(),
                LegacyPersonaDocumentV1(
                    personas = listOf(
                        LegacyPersonaProfileV1(
                            id = "legacy",
                            name = "旧人物",
                            identity = "游历各地的剑客",
                            background = "常年独自接护送委托，也会回旧城看师父。",
                            personality = "直接，但不会轻易把担心说出口。",
                            speechStyle = "句子短，熟人面前偶尔会吐槽。",
                            relationship = "把用户当成刚建立信任的同行者。",
                            coreMotivations = listOf("保护同行者"),
                            valuePriorities = listOf("守诺"),
                            behaviorPatterns = listOf("遇到危险先观察出口"),
                            internalContradictions = listOf("习惯独自扛事，又不想把重要的人推远"),
                            exampleDialogues = listOf("跟紧我。"),
                            signaturePhrases = listOf("少逞强。"),
                            hardConstraints = listOf("不会无故抛下同行者"),
                        ),
                    ),
                ),
            ),
        )

        val migrated = ChatPersonaStore(
            file = personaFile,
            json = json,
            legacyFile = legacyFile,
            migrationMarker = marker,
        ).get("legacy")

        assertTrue(migrated.portrait.contains("游历各地的剑客"))
        assertTrue(migrated.portrait.contains("直接"))
        assertTrue(migrated.portrait.contains("表达倾向"))
        assertEquals("常年独自接护送委托，也会回旧城看师父。", migrated.lifeContext)
        assertEquals(listOf("守诺", "保护同行者"), migrated.coreValues)
        assertEquals("习惯独自扛事，又不想把重要的人推远", migrated.coreTension)
        assertTrue(migrated.stableTraits.isEmpty())
        assertEquals(listOf("常见习惯：遇到危险先观察出口"), migrated.quirks)
        assertEquals("把用户当成刚建立信任的同行者。", migrated.initialUserImpression)
        assertEquals(listOf("跟紧我。", "少逞强。"), migrated.voiceSamples)
        assertTrue(marker.isFile)
        assertTrue(legacyFile.isFile)
        assertTrue(legacyFile.readText().contains("\"identity\":\"游历各地的剑客\""))
    }

    @Test
    fun oldGallerySchemaMigratesPersonaAndFullColdHistory() {
        val legacyFile = File(temporary.root, "persona-gallery-v4.json")
        val marker = File(temporary.root, "persona-gallery-v4-to-v5.done")
        val legacyHistoryRoot = File(temporary.root, "persona-history")
        val currentHistoryRoot = File(temporary.root, "persona-history-v5")
        val messages = (1..48).map { index ->
            LocalHarnessMessage(
                id = "m$index",
                role = if (index % 2 == 0) "assistant" else "user",
                content = "旧对白$index",
                createdAt = index.toLong(),
            )
        }
        PersonaGalleryHistoryStore(legacyHistoryRoot, json).merge("legacy-entry", "story-1", messages)
        legacyFile.writeText(
            json.encodeToString(
                LegacyGalleryDocumentV4.serializer(),
                LegacyGalleryDocumentV4(
                    entries = listOf(
                        LegacyPersonaGalleryEntryV4(
                            id = "legacy-entry",
                            persona = LegacyPersonaProfileV1(
                                id = "legacy-entry",
                                name = "阿青",
                                identity = "江湖剑客",
                                personality = "嘴硬心软",
                                relationship = "和用户已经并肩走过一段路",
                            ),
                            stories = listOf(
                                PersonaGalleryStory(
                                    id = "story-1",
                                    title = "旧故事",
                                    history = messages.takeLast(2),
                                    historyTotalCount = messages.size,
                                    historyArchived = true,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val store = ChatPersonaGalleryStore(
            file = galleryFile,
            json = json,
            historyRoot = currentHistoryRoot,
            legacyFile = legacyFile,
            legacyHistoryRoot = legacyHistoryRoot,
            migrationMarker = marker,
        )
        val migrated = store.list().single()
        val full = store.loadStoryHistory(migrated.id, "story-1", 100)

        assertEquals("阿青", migrated.persona.name)
        assertTrue(migrated.persona.portrait.contains("江湖剑客"))
        assertEquals("和用户已经并肩走过一段路", migrated.persona.initialUserImpression)
        assertEquals(48, full.totalCount)
        assertEquals("旧对白1", full.messages.first().content)
        assertEquals("旧对白48", full.messages.last().content)
        assertTrue(marker.isFile)
        assertTrue(legacyFile.isFile)
    }

    @Test
    fun corruptedPersonaPrimaryRecoversBackupBeforeNextWrite() {
        val store = personaStore()
        store.upsert(
            PersonaProfile(
                id = "persona-a",
                name = "阿青",
                portrait = "嘴硬心软",
            ),
        )
        assertTrue(File(temporary.root, "personas.json.bak").isFile)

        personaFile.writeText("{broken")
        assertEquals("阿青", store.get("persona-a").name)
        assertTrue(
            temporary.root.listFiles().orEmpty()
                .any { it.name.startsWith("personas.json.corrupt-") },
        )

        store.upsert(PersonaProfile(id = "persona-b", name = "小岚"))
        val ids = store.list().map { it.id }.toSet()
        assertTrue("persona-a" in ids)
        assertTrue("persona-b" in ids)
    }

    @Test
    fun corruptedGalleryPrimaryRecoversArchivedCharacterBeforeNextWrite() {
        val store = galleryStore()
        val saved = store.save(
            persona = PersonaProfile(name = "阿青", portrait = "剑客"),
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
        val recovered = store.list().single()
        assertEquals(saved.entry.id, recovered.id)
        assertEquals("阿青", recovered.persona.name)
        assertTrue(
            temporary.root.listFiles().orEmpty()
                .any { it.name.startsWith("persona-gallery.json.corrupt-") },
        )

        store.save(
            persona = PersonaProfile(name = "小岚", portrait = "花店店主"),
            sourceSessionId = "session-b",
            history = emptyList(),
            chatState = ChatCharacterState(),
            notes = "",
        )
        assertEquals(setOf("阿青", "小岚"), store.list().map { it.persona.name }.toSet())
    }
}
