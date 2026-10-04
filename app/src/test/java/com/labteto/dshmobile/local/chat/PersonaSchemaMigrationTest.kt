package com.labteto.dshmobile.local.chat

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonaSchemaMigrationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun currentV3EditsWinWhileLegacyOnlyFillsMissingInformation() {
        val current = PersonaProfile(
            id = "same",
            name = "阿青",
            portrait = "用户在新版亲手改过的人物整体",
            coreValues = listOf("新版价值"),
            voiceSamples = listOf("新版台词"),
        )
        val migrated = PersonaSchemaMigration.toCurrent(
            LegacyPersonaProfileV1(
                id = "same",
                name = "阿青",
                identity = "旧版身份",
                background = "旧版独立生活",
                valuePriorities = listOf("旧版价值"),
                exampleDialogues = listOf("旧版台词"),
            ),
        )

        val merged = PersonaSchemaMigration.mergeCurrentFirst(current, migrated)

        assertEquals("用户在新版亲手改过的人物整体", merged.portrait)
        assertEquals("旧版独立生活", merged.lifeContext)
        assertEquals(listOf("新版价值", "旧版价值"), merged.coreValues)
        assertEquals(listOf("新版台词", "旧版台词"), merged.voiceSamples)
    }

    @Test
    fun installedLegacyPresetKeepsUserContentAndGainsV3OnlyLifeDimensions() {
        val migrated = PersonaSchemaMigration.toCurrent(
            LegacyPersonaProfileV1(
                id = "ayaka",
                name = "神里绫华",
                identity = "用户自己补写过的神里家大小姐",
                personality = "用户自己调整过的沉静性格",
                presetId = "genshin-kamisato-ayaka",
            ),
        )

        assertTrue(migrated.portrait.contains("用户自己补写过"))
        assertTrue(migrated.portrait.contains("用户自己调整过"))
        assertTrue(migrated.attentionBiases.isNotEmpty())
        assertTrue(migrated.attentionKeywords.isNotEmpty())
        assertTrue(migrated.perceptionBlindSpots.isNotEmpty())
        assertTrue(migrated.quirks.isNotEmpty())
        assertTrue(migrated.limitations.isNotEmpty())
        assertTrue(migrated.mutableTraits.isNotEmpty())
    }

    @Test
    fun legacyShareJsonImportsIntoCurrentPersona() {
        val payload = json.encodeToString(
            LegacyPersonaShareEnvelopeV1.serializer(),
            LegacyPersonaShareEnvelopeV1(
                persona = LegacyPersonaProfileV1(
                    name = "小岚",
                    identity = "街角花店店主",
                    personality = "嘴硬心软",
                    relationship = "和用户认识多年",
                    exampleDialogues = listOf("花拿好，别压坏了。"),
                ),
            ),
        )
        val store = ChatPersonaGalleryStore(File(temporary.root, "share-target.json"), json)

        val imported = store.importPersona(payload)

        assertEquals("小岚", imported.persona.name)
        assertTrue(imported.persona.portrait.contains("街角花店店主"))
        assertEquals("和用户认识多年", imported.persona.initialUserImpression)
        assertEquals(listOf("花拿好，别压坏了。"), imported.persona.voiceSamples)
    }

    @Test
    fun legacyArchiveMarkdownImportsWithStories() {
        val archiveJson = json.encodeToString(
            LegacyPersonaArchiveEnvelopeV2.serializer(),
            LegacyPersonaArchiveEnvelopeV2(
                entry = LegacyPersonaGalleryEntryV4(
                    id = "legacy-entry",
                    persona = LegacyPersonaProfileV1(
                        id = "legacy-entry",
                        name = "阿青",
                        identity = "江湖剑客",
                        background = "常年在外行走",
                    ),
                    stories = listOf(
                        PersonaGalleryStory(
                            id = "story-1",
                            title = "桥边旧事",
                            history = listOf(
                                com.labteto.dshmobile.local.LocalHarnessMessage(
                                    id = "m1",
                                    role = "user",
                                    content = "还记得这里吗？",
                                    createdAt = 1L,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val encoded = Base64.getEncoder().encodeToString(archiveJson.toByteArray())
        val markdown = buildString {
            appendLine("# 旧人物档案")
            appendLine("<!-- SHENYU_PERSONA_ARCHIVE_V2_BASE64")
            appendLine(encoded)
            appendLine("SHENYU_PERSONA_ARCHIVE_V2_BASE64_END -->")
        }
        val store = ChatPersonaGalleryStore(File(temporary.root, "archive-target.json"), json)

        val imported = store.importPersonaDocument(
            bytes = markdown.toByteArray(),
            fileName = "旧人物.md",
            mimeType = "text/markdown",
        )

        assertEquals("阿青", imported.persona.name)
        assertTrue(imported.persona.portrait.contains("江湖剑客"))
        assertEquals("常年在外行走", imported.persona.lifeContext)
        assertEquals("桥边旧事", imported.stories.single().title)
    }
}
