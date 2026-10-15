package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaPresetCatalogTest {

    @Test
    fun starterPresetsCoverFourFranchisesAndUseStableIds() {
        val presets = PersonaPresetCatalog.presets
        val expectedIds = setOf(
            "genshin-kamisato-ayaka",
            "genshin-xiao",
            "genshin-yoimiya",
            "genshin-klee",
            "genshin-sayu",
            "genshin-furina",
            "hsr-kafka",
            "hsr-dan-heng",
            "hsr-sparkle",
            "hsr-sparxie",
            "hsr-evernight",
            "hsr-firefly",
            "hsr-march-7th",
            "wwm-zhao-er",
            "wwm-hongxian",
            "wwm-jiang-yan",
            "wwm-chen-zixi",
            "wwm-chen-shen",
            "love-deepspace-li-shen",
            "love-deepspace-shen-xinghui",
            "love-deepspace-xia-yizhou",
        )

        assertEquals(expectedIds, presets.map { it.id }.toSet())
        assertEquals(expectedIds.size, presets.size)
        assertEquals(
            setOf("原神", "崩坏：星穹铁道", "燕云十六声", "恋与深空"),
            presets.map { it.franchise }.toSet(),
        )
        assertTrue(presets.all { it.persona.presetId == it.id })
        assertTrue(presets.all { it.persona.franchise == it.franchise })
    }

    @Test
    fun starterPresetsAreSpoilerSafeByDefault() {
        PersonaPresetCatalog.presets.forEach { preset ->
            assertTrue(preset.persona.timelinePosition.isNotBlank())
            assertTrue(preset.persona.knowledgeBoundary.isNotEmpty())
            assertTrue(preset.persona.loreEntries.isNotEmpty())
            assertTrue(preset.persona.loreEntries.all { it.spoilerLevel == 0 })
        }
    }

    @Test
    fun starterPresetsProvideRetrievableCanonBeyondLifeTraits() {
        PersonaPresetCatalog.presets.forEach { preset ->
            val lore = preset.persona.loreEntries
            assertTrue("${preset.id} lacks canonical facts", lore.size >= 3)
            assertTrue("${preset.id} has unsearchable world-book data", lore.all { it.keywords.isNotEmpty() })
            assertTrue("${preset.id} lacks game-world supplements", lore.any { it.id.contains("canon") })
            assertEquals("${preset.id} duplicate lore IDs", lore.size, lore.map { it.id }.distinct().size)
            assertTrue(lore.all { it.spoilerLevel == 0 })
        }
    }

    @Test
    fun regeneratedCardsKeepEachCharacterIdentityAndLifeContext() {
        val identityAnchors = mapOf(
            "genshin-kamisato-ayaka" to listOf("社奉行", "神里绫人", "剑术"),
            "hsr-kafka" to listOf("星核猎手", "银狼", "言语"),
            "wwm-zhao-er" to listOf("少东家", "身份", "江湖"),
            "love-deepspace-li-shen" to listOf("心脏外科", "病人", "医疗"),
            "love-deepspace-shen-xinghui" to listOf("深空猎人", "光", "流浪体"),
            "love-deepspace-xia-yizhou" to listOf("从小", "飞行员", "舰队"),
            "wwm-hongxian" to listOf("摇红女侠", "清河", "少东家"),
            "wwm-jiang-yan" to listOf("养父", "授业", "江湖"),
            "wwm-chen-zixi" to listOf("回春堂", "玉山君", "病人"),
            "wwm-chen-shen" to listOf("回春堂", "小师兄", "医武"),
            "genshin-xiao" to listOf("夜叉", "降魔大圣", "业障"),
            "genshin-yoimiya" to listOf("烟花", "长野原", "火元素"),
            "genshin-klee" to listOf("火花骑士", "艾莉丝", "琴团长"),
            "hsr-dan-heng" to listOf("星穹列车", "智库", "长枪"),
            "hsr-sparkle" to listOf("假面愚者", "伪装", "匹诺康尼"),
            "hsr-sparxie" to listOf("二相乐园", "直播", "花火"),
            "hsr-evernight" to listOf("长夜月", "翁法罗斯", "守护"),
            "hsr-firefly" to listOf("匹诺康尼", "普通", "危险"),
            "hsr-march-7th" to listOf("六相冰", "星穹列车", "拍照"),
            "genshin-sayu" to listOf("终末番", "忍术", "长高"),
            "genshin-furina" to listOf("枫丹", "歌剧院", "身份"),
        )
        assertEquals(identityAnchors.keys, PersonaPresetCatalog.presets.map { it.id }.toSet())
        PersonaPresetCatalog.presets.forEach { preset ->
            val card = preset.persona
            identityAnchors.getValue(preset.id).forEach { anchor ->
                assertTrue("${preset.id} missing identity anchor: $anchor", anchor in card.coreIdentity)
            }
            assertTrue("${preset.id} lost formative life context", card.factText(CharacterFactCategories.BIOGRAPHY).isNotBlank())
            assertTrue("${preset.id} lost inner motivation", card.factText(CharacterFactCategories.IDENTITY_GAP).isNotBlank())
            assertTrue("${preset.id} lost current story boundary", card.knowledgeBoundary.isNotEmpty())
            assertEquals("${preset.id} repeats a lore source", card.loreEntries.size,
                card.loreEntries.map(PersonaLoreEntry::id).distinct().size)
        }
    }

    @Test
    fun allTwentyOnePresetsHaveReauthoredIdentityAndNineDistinctFactCategories() {
        val required = listOf(
            CharacterFactCategories.PERSONALITY,
            CharacterFactCategories.VALUES_AND_TRADEOFFS,
            CharacterFactCategories.SELF_NARRATIVE,
            CharacterFactCategories.SUBJECTIVE_BELIEFS,
            CharacterFactCategories.IDENTITY_GAP,
            CharacterFactCategories.PREFERENCES_AND_HABITS,
            CharacterFactCategories.VOICE_STYLE,
            CharacterFactCategories.BIOGRAPHY,
            CharacterFactCategories.RELATIONSHIPS,
        )
        assertEquals(21, PersonaPresetCatalog.presets.size)
        PersonaPresetCatalog.presets.forEach { preset ->
            val persona = preset.persona
            assertTrue("${preset.id}: empty identity", persona.coreIdentity.isNotBlank())
            assertEquals("${preset.id}: missing new category",
                required.toSet(), persona.facts.map(CharacterFact::category).toSet())
            assertEquals("${preset.id}: duplicate fact categories",
                persona.facts.size, persona.facts.map(CharacterFact::category).distinct().size)
            assertTrue("${preset.id}: unsupported fact",
                persona.facts.all { CharacterFactCategories.isSupported(it.category) })
            assertTrue("${preset.id}: no story knowledge boundaries", persona.knowledgeBoundary.isNotEmpty())
            assertTrue("${preset.id}: world background missing",
                persona.loreEntries.any { it.id == "${preset.id}-world-context" })
        }
    }

    @Test
    fun eachPresetUsesPersonSpecificVoiceAndRelationships() {
        val voices = PersonaPresetCatalog.presets.map {
            it.persona.factText(CharacterFactCategories.VOICE_STYLE)
        }
        val relationships = PersonaPresetCatalog.presets.map {
            it.persona.factText(CharacterFactCategories.RELATIONSHIPS)
        }
        assertEquals(21, voices.distinct().size)
        assertEquals(21, relationships.distinct().size)
        assertTrue(voices.all(String::isNotBlank))
        assertTrue(relationships.all(String::isNotBlank))
    }

    @Test
    fun bundledPresetArtworkMustStayUnderDedicatedAssetDirectory() {
        val artworkPaths = PersonaPresetCatalog.presets.associate { preset ->
            preset.id to requireNotNull(preset.artwork).assetPath
        }.toMap()

        assertEquals(
            PersonaPresetCatalog.presets.associate { preset ->
                preset.id to "persona-presets/${preset.id}.webp"
            },
            artworkPaths,
        )
        artworkPaths.values.forEach { assetPath ->
            assertTrue(assetPath.startsWith("persona-presets/"))
            assertTrue(".." !in assetPath)
            assertTrue(
                assetPath.substringAfterLast('.', "").lowercase() in
                    setOf("jpg", "jpeg", "png", "webp"),
            )
        }
    }
}
