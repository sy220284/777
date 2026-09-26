package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessMessage
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonaTransferDocumentsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun markdownRoundTripKeepsProfileMemoryAndDialogue() {
        val document = PersonaTransferDocuments.encode(
            json = json,
            entry = sampleEntry(),
            format = PersonaTransferFormat.MARKDOWN,
        )

        val markdown = String(document.bytes, StandardCharsets.UTF_8)
        assertTrue(markdown.contains("# 人物档案：小岚"))
        assertTrue(markdown.contains("## 人物设定"))
        assertTrue(markdown.contains("## 记忆摘要"))
        assertTrue(markdown.contains("剧情提要：第一次一起去海边"))
        assertTrue(markdown.contains("## 对话记录"))
        assertTrue(markdown.contains("用户：明天还去海边吗？"))
        assertTrue(markdown.contains("小岚：去，还是老地方。"))

        val canonical = PersonaTransferDocuments.decodeToCanonicalJson(
            bytes = document.bytes,
            fileName = "小岚.persona.md",
            mimeType = "text/markdown",
        )
        val archive = PersonaTransferDocuments.decodeArchive(json, canonical)

        assertEquals("小岚", archive.entry.persona.name)
        assertEquals(1, archive.entry.stories.size)
        assertEquals(2, archive.entry.stories.single().history.size)
        assertEquals("挚友", archive.entry.stories.single().chatState.relationshipState)
        assertEquals(listOf("一起看过日出"), archive.memorySummaries.single().sharedMoments)
        assertTrue(archive.memorySummaries.single().continuitySummary.contains("剧情提要：第一次一起去海边"))
        assertTrue(archive.memorySummaries.single().continuitySummary.contains("保存时的关系：挚友"))
        assertTrue(archive.entry.stories.single().sourceSessionIds.isEmpty())
        assertTrue(archive.entry.stories.single().excludedMessageKeys.isEmpty())
    }

    @Test
    fun wordRoundTripProducesReadableDocxAndKeepsArchive() {
        val document = PersonaTransferDocuments.encode(
            json = json,
            entry = sampleEntry(),
            format = PersonaTransferFormat.WORD,
        )

        assertTrue(document.bytes.size > 4)
        assertEquals(0x50, document.bytes[0].toInt() and 0xff)
        assertEquals(0x4b, document.bytes[1].toInt() and 0xff)

        val entries = mutableMapOf<String, String>()
        ZipInputStream(document.bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".xml")) {
                    entries[entry.name] = String(zip.readBytes(), StandardCharsets.UTF_8)
                }
            }
        }
        val wordXml = entries.getValue("word/document.xml")
        assertTrue(wordXml.contains("人物档案：小岚"))
        assertTrue(wordXml.contains("记忆摘要"))
        assertTrue(wordXml.contains("明天还去海边吗？"))
        assertTrue(entries.containsKey("customXml/persona-transfer.xml"))

        val canonical = PersonaTransferDocuments.decodeToCanonicalJson(
            bytes = document.bytes,
            fileName = "小岚.persona.docx",
            mimeType = PERSONA_WORD_MIME,
        )
        val archive = PersonaTransferDocuments.decodeArchive(json, canonical)
        assertEquals("小岚", archive.entry.persona.name)
        assertEquals("第一次一起去海边", archive.entry.stories.single().notes)
        assertEquals("去，还是老地方。", archive.entry.stories.single().history.last().content)
    }

    @Test
    fun fullArchiveImportRestoresStoriesAndMergesSameCharacter() {
        val target = ChatPersonaGalleryStore(
            File(temporary.root, "personas.json"),
            json,
        )
        val first = PersonaTransferDocuments.encode(
            json = json,
            entry = sampleEntry(),
            format = PersonaTransferFormat.JSON,
        )

        val imported = target.importPersonaDocument(
            bytes = first.bytes,
            fileName = "小岚.persona.json",
            mimeType = "application/json",
        )
        assertEquals(1, imported.stories.size)
        assertEquals(2, imported.stories.single().history.size)
        assertEquals("第一次一起去海边", imported.stories.single().notes)

        val secondEntry = sampleEntry().copy(
            stories = listOf(
                PersonaGalleryStory(
                    id = "story-2",
                    title = "第二次出游",
                    notes = "后来一起去了山里",
                    history = listOf(
                        LocalHarnessMessage(
                            id = "m3",
                            role = "user",
                            content = "这次去山里吧。",
                            createdAt = 3L,
                        ),
                        LocalHarnessMessage(
                            id = "m4",
                            role = "assistant",
                            content = "行，我带路。",
                            createdAt = 4L,
                        ),
                    ),
                    chatState = ChatCharacterState(
                        relationshipState = "挚友",
                        mood = "期待",
                        updatedAt = 4L,
                    ),
                    updatedAt = 4L,
                ),
            ),
        )
        val second = PersonaTransferDocuments.encode(
            json = json,
            entry = secondEntry,
            format = PersonaTransferFormat.MARKDOWN,
        )
        val merged = target.importPersonaDocument(
            bytes = second.bytes,
            fileName = "小岚.persona.md",
            mimeType = "text/markdown",
        )

        assertEquals(imported.id, merged.id)
        assertEquals(2, merged.stories.size)
        assertEquals(4, merged.totalDialogueCount())
        assertTrue(merged.stories.any { it.notes == "后来一起去了山里" })
    }

    @Test
    fun legacyPersonaJsonStillImports() {
        val source = ChatPersonaGalleryStore(
            File(temporary.root, "legacy-source.json"),
            json,
        )
        val saved = source.save(
            persona = PersonaProfile(
                name = "阿青",
                identity = "剑客",
                personality = "直接",
            ),
            sourceSessionId = "",
            history = emptyList(),
            chatState = ChatCharacterState(),
            notes = "",
        ).entry
        val legacy = source.exportPersona(saved.id)

        val target = ChatPersonaGalleryStore(
            File(temporary.root, "legacy-target.json"),
            json,
        )
        val imported = target.importPersonaDocument(
            bytes = legacy.toByteArray(StandardCharsets.UTF_8),
            fileName = "阿青.persona.json",
            mimeType = "application/json",
        )

        assertEquals("阿青", imported.persona.name)
        assertEquals("剑客", imported.persona.identity)
        assertTrue(imported.stories.isEmpty())
    }

    @Test
    fun futureArchiveSchemaIsRejectedWithoutLegacyFallback() {
        val target = ChatPersonaGalleryStore(
            File(temporary.root, "future-target.json"),
            json,
        )
        val document = PersonaTransferDocuments.encode(
            json = json,
            entry = sampleEntry(),
            format = PersonaTransferFormat.JSON,
        )
        val future = String(document.bytes, StandardCharsets.UTF_8)
            .replace("\"schema\":2", "\"schema\":3")

        val result = runCatching {
            target.importPersonaDocument(
                bytes = future.toByteArray(StandardCharsets.UTF_8),
                fileName = "小岚.persona.json",
                mimeType = "application/json",
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("暂不支持") == true)
    }

    @Test
    fun markdownWithoutMachinePayloadIsRejected() {
        val result = runCatching {
            PersonaTransferDocuments.decodeToCanonicalJson(
                "# 普通文档".toByteArray(StandardCharsets.UTF_8),
                fileName = "notes.md",
                mimeType = "text/markdown",
            )
        }
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.isNullOrBlank())
    }

    private fun sampleEntry(): PersonaGalleryEntry = PersonaGalleryEntry(
        id = "gallery-lan",
        persona = PersonaProfile(
            id = "gallery-lan",
            name = "小岚",
            identity = "旅行摄影师",
            background = "常年在沿海城市旅行。",
            personality = "爽快、细心",
            speechStyle = "简短自然",
            relationship = "和用户是多年朋友",
            worldSetting = "现代城市",
            coreMotivations = listOf("记录真实生活"),
            behaviorPatterns = listOf("答应的事会做到"),
            hardConstraints = listOf("不故意撒谎"),
        ),
        stories = listOf(
            PersonaGalleryStory(
                id = "story-1",
                title = "海边",
                notes = "第一次一起去海边",
                history = listOf(
                    LocalHarnessMessage(
                        id = "m1",
                        role = "user",
                        content = "明天还去海边吗？",
                        createdAt = 1L,
                    ),
                    LocalHarnessMessage(
                        id = "m2",
                        role = "assistant",
                        content = "去，还是老地方。",
                        createdAt = 2L,
                    ),
                ),
                chatState = ChatCharacterState(
                    relationshipState = "挚友",
                    mood = "开心",
                    currentFocus = "准备下一次旅行",
                    recentImpression = "用户最近很忙",
                    activeGoal = "约一次短途旅行",
                    unresolvedThreads = listOf("还没决定出发时间"),
                    dynamics = RelationshipDynamics(
                        sharedMoments = listOf("一起看过日出"),
                    ),
                    updatedAt = 2L,
                ),
                sourceSessionIds = listOf("local-session-1"),
                excludedMessageKeys = listOf("local-tombstone"),
                updatedAt = 2L,
            ),
        ),
        updatedAt = 2L,
    )
}
