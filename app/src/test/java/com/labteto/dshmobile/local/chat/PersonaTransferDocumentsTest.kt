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
    fun markdownRoundTripKeepsProfileMemoryDiaryAndDialogue() {
        val document = PersonaTransferDocuments.encode(
            json = json,
            entry = sampleEntry(),
            format = PersonaTransferFormat.MARKDOWN,
            diaryEntries = sampleDiaryEntries(),
        )

        val markdown = String(document.bytes, StandardCharsets.UTF_8)
        assertTrue(markdown.contains("# 人物档案：小岚"))
        assertTrue(markdown.contains("## 人物生命资料"))
        assertTrue(markdown.contains("## 记忆摘要"))
        assertTrue(markdown.contains("剧情提要：第一次一起去海边"))
        assertTrue(markdown.contains("## 人物日记"))
        assertTrue(markdown.contains("我已经开始期待下次一起出门了"))
        assertTrue(markdown.contains("披露范围：仅单聊"))
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
        assertEquals(1, archive.diaryEntries.size)
        assertEquals("", archive.diaryEntries.single().subjectKey)
        assertEquals(ChatDiaryDisclosure.SHAREABLE, archive.diaryEntries.single().disclosure)
        assertTrue(archive.diaryEntries.single().sources.isEmpty())
        assertTrue(archive.diaryEntries.single().revisions.single().sources.isEmpty())
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
            diaryEntries = sampleDiaryEntries(),
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
        assertTrue(wordXml.contains("人物日记"))
        assertTrue(wordXml.contains("我已经开始期待下次一起出门了"))
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
        val prepared = target.preparePersonaDocumentImport(
            bytes = second.bytes,
            fileName = "小岚.persona.md",
            mimeType = "text/markdown",
        ) as PersonaGalleryPreparedImport.Archive
        assertEquals(2, prepared.rollbackEntry?.stories?.single()?.history?.size)
        val merged = target.commitPersonaDocumentImport(prepared)

        assertEquals(imported.id, merged.id)
        assertEquals(2, merged.stories.size)
        assertEquals(4, merged.totalDialogueCount())
        assertTrue(merged.stories.any { it.notes == "后来一起去了山里" })
    }

    @Test
    fun archivePreparationDoesNotMutateGalleryBeforeCommit() {
        val target = ChatPersonaGalleryStore(File(temporary.root, "prepared-target.json"), json)
        val document = PersonaTransferDocuments.encode(
            json = json,
            entry = sampleEntry(),
            format = PersonaTransferFormat.JSON,
            diaryEntries = sampleDiaryEntries(),
        )

        val prepared = target.preparePersonaDocumentImport(
            bytes = document.bytes,
            fileName = "小岚.persona.json",
            mimeType = "application/json",
        )

        assertTrue(target.list().isEmpty())
        assertTrue(prepared is PersonaGalleryPreparedImport.Archive)
        val committed = target.commitPersonaDocumentImport(prepared)
        assertEquals("小岚", committed.persona.name)
        assertEquals(1, target.list().size)
    }

    @Test
    fun failedGalleryDocumentCommitRemovesNewColdDialogueArchive() {
        val root = File(temporary.root, "gallery-commit-failure").apply { mkdirs() }
        val targetFile = File(root, "personas.json")
        val target = ChatPersonaGalleryStore(targetFile, json)
        val document = PersonaTransferDocuments.encode(
            json = json,
            entry = sampleEntry(),
            format = PersonaTransferFormat.JSON,
            diaryEntries = sampleDiaryEntries(),
        )
        val prepared = target.preparePersonaDocumentImport(
            bytes = document.bytes,
            fileName = "小岚.persona.json",
            mimeType = "application/json",
        )

        targetFile.mkdirs()
        File(targetFile, "block").writeText("block")
        val result = runCatching { target.commitPersonaDocumentImport(prepared) }

        assertTrue(result.isFailure)
        val historyRoot = File(root, "persona-history-v5")
        assertTrue(historyRoot.walkTopDown().none { it.isFile })
    }

    @Test
    fun repeatedLongArchiveImportKeepsSingleStoryAndFullHistory() {
        val longStory = sampleEntry().stories.single().copy(
            history = (1L..40L).map { index ->
                LocalHarnessMessage(
                    id = "long-$index",
                    role = if (index % 2L == 0L) "assistant" else "user",
                    content = "第$index 条对话",
                    createdAt = index,
                )
            },
            historyTotalCount = 40,
            updatedAt = 40L,
        )
        val source = sampleEntry().copy(stories = listOf(longStory), updatedAt = 40L)
        val document = PersonaTransferDocuments.encode(
            json = json,
            entry = source,
            format = PersonaTransferFormat.JSON,
        )
        val target = ChatPersonaGalleryStore(File(temporary.root, "long-repeat.json"), json)

        val first = target.importPersonaDocument(
            bytes = document.bytes,
            fileName = "小岚.persona.json",
            mimeType = "application/json",
        )
        val second = target.importPersonaDocument(
            bytes = document.bytes,
            fileName = "小岚.persona.json",
            mimeType = "application/json",
        )

        assertEquals(first.id, second.id)
        assertEquals(1, target.list().single().stories.size)

        val exported = target.exportPersonaDocument(second.id, PersonaTransferFormat.JSON)
        val canonical = PersonaTransferDocuments.decodeToCanonicalJson(
            bytes = exported.bytes,
            fileName = "小岚.persona.json",
            mimeType = "application/json",
        )
        val archive = PersonaTransferDocuments.decodeArchive(json, canonical)
        assertEquals(1, archive.entry.stories.size)
        assertEquals(40, archive.entry.stories.single().history.size)
    }

    @Test
    fun unsafeOrDuplicateStoryIdsStaySeparatedAndIdempotent() {
        val source = sampleEntry().copy(
            stories = listOf(
                sampleEntry().stories.single().copy(
                    id = "story/a",
                    history = listOf(
                        LocalHarnessMessage(
                            id = "unsafe-a",
                            role = "user",
                            content = "第一段独立历史",
                            createdAt = 1L,
                        ),
                    ),
                    historyTotalCount = 1,
                ),
                sampleEntry().stories.single().copy(
                    id = "story?a",
                    title = "另一段",
                    history = listOf(
                        LocalHarnessMessage(
                            id = "unsafe-b",
                            role = "assistant",
                            content = "第二段独立历史",
                            createdAt = 2L,
                        ),
                    ),
                    historyTotalCount = 1,
                ),
            ),
        )
        val document = PersonaTransferDocuments.encode(
            json = json,
            entry = source,
            format = PersonaTransferFormat.JSON,
        )
        val target = ChatPersonaGalleryStore(File(temporary.root, "unsafe-story-ids.json"), json)

        val first = target.importPersonaDocument(
            bytes = document.bytes,
            fileName = "小岚.persona.json",
            mimeType = "application/json",
        )
        val second = target.importPersonaDocument(
            bytes = document.bytes,
            fileName = "小岚.persona.json",
            mimeType = "application/json",
        )

        assertEquals(first.id, second.id)
        assertEquals(2, target.list().single().stories.size)
        assertEquals(2, target.list().single().stories.map { it.id }.distinct().size)
        assertTrue(target.list().single().stories.all { Regex("[A-Za-z0-9._-]{1,120}").matches(it.id) })

        val exported = target.exportPersonaDocument(second.id, PersonaTransferFormat.JSON)
        val canonical = PersonaTransferDocuments.decodeToCanonicalJson(
            bytes = exported.bytes,
            fileName = "小岚.persona.json",
            mimeType = "application/json",
        )
        val archive = PersonaTransferDocuments.decodeArchive(json, canonical)
        val histories = archive.entry.stories.map { story -> story.history.map(LocalHarnessMessage::content).toSet() }
        assertTrue(histories.contains(setOf("第一段独立历史")))
        assertTrue(histories.contains(setOf("第二段独立历史")))
    }

    @Test
    fun galleryChangeAfterPreparationRollsBackDiaryImport() {
        val gallery = ChatPersonaGalleryStore(File(temporary.root, "transaction-gallery.json"), json)
        val diary = ChatDiaryStore(File(temporary.root, "transaction-diary"), json)
        val document = PersonaTransferDocuments.encode(
            json = json,
            entry = sampleEntry(),
            format = PersonaTransferFormat.JSON,
            diaryEntries = sampleDiaryEntries(),
        )
        val prepared = gallery.preparePersonaDocumentImport(
            bytes = document.bytes,
            fileName = "小岚.persona.json",
            mimeType = "application/json",
        ) as PersonaGalleryPreparedImport.Archive
        val subjectKey = "gallery:${prepared.entry.id}"

        gallery.save(
            persona = PersonaProfile(name = "旁观者", portrait = "用于制造并发图集变更"),
            sourceSessionId = "",
            history = emptyList(),
            chatState = ChatCharacterState(),
            notes = "",
        )

        val result = runCatching {
            diary.importForTransfer(
                subjectKey = subjectKey,
                personaName = prepared.entry.persona.name,
                entries = prepared.diaryEntries,
            ) {
                gallery.commitPersonaDocumentImport(prepared)
            }
        }

        assertTrue(result.isFailure)
        assertTrue(diary.listForTransfer(subjectKey).isEmpty())
        assertEquals(listOf("旁观者"), gallery.list().map { it.persona.name })
    }

    @Test
    fun currentPersonaShareJsonImportsWithoutStories() {
        val source = ChatPersonaGalleryStore(
            File(temporary.root, "legacy-source.json"),
            json,
        )
        val saved = source.save(
            persona = PersonaProfile(
                name = "阿青",
                portrait = "剑客，直接",
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
        assertTrue(imported.persona.portrait.contains("剑客"))
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
            .replace("\"schema\":4", "\"schema\":5")

        val result = runCatching {
            target.importPersonaDocument(
                bytes = future.toByteArray(StandardCharsets.UTF_8),
                fileName = "小岚.persona.json",
                mimeType = "application/json",
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("版本不受支持") == true)
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

    private fun sampleDiaryEntries(): List<ChatDiaryEntry> = listOf(
        ChatDiaryEntry(
            id = "diary-1",
            subjectKey = "gallery:gallery-lan",
            personaName = "小岚",
            event = "第一次一起去海边以后，我们又约了下一次短途旅行",
            feeling = "想到这件事会忍不住开心",
            innerThought = "我已经开始期待下次一起出门了",
            relationshipMeaning = "我们开始有了会继续兑现的共同计划",
            unresolvedEcho = "还没定下具体出发时间",
            importance = 5,
            disclosure = ChatDiaryDisclosure.SHAREABLE,
            sources = listOf(
                ChatDiarySourceRef(
                    sessionId = "local-session-1",
                    userMessageId = "m1",
                    assistantMessageId = "m2",
                ),
            ),
            revisions = listOf(
                ChatDiaryRevision(
                    event = "第一次一起去海边以后，我们又约了下一次短途旅行",
                    feeling = "想到这件事会忍不住开心",
                    innerThought = "我已经开始期待下次一起出门了",
                    relationshipMeaning = "我们开始有了会继续兑现的共同计划",
                    unresolvedEcho = "还没定下具体出发时间",
                    importance = 5,
                    disclosure = ChatDiaryDisclosure.SHAREABLE,
                    sources = listOf(
                        ChatDiarySourceRef(
                            sessionId = "local-session-1",
                            userMessageId = "m1",
                            assistantMessageId = "m2",
                        ),
                    ),
                    updatedAt = 2L,
                ),
            ),
            createdAt = 1L,
            updatedAt = 2L,
        ),
    )

    private fun sampleEntry(): PersonaGalleryEntry = PersonaGalleryEntry(
        id = "gallery-lan",
        persona = PersonaProfile(
            id = "gallery-lan",
            name = "小岚",
            portrait = "旅行摄影师，爽快、细心，说话简短自然。",
            lifeContext = "常年在沿海城市旅行。",
            initialUserImpression = "和用户是多年朋友",
            worldSetting = "现代城市",
            coreValues = listOf("记录真实生活"),
            stableTraits = listOf("答应的事会做到"),
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
