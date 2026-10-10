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
                assertTrue("${preset.id} missing identity anchor: $anchor", anchor in card.portrait)
            }
            assertTrue("${preset.id} lost formative life context", card.lifeContext.isNotBlank())
            assertTrue("${preset.id} lost inner motivation", card.coreTension.isNotBlank())
            assertTrue("${preset.id} lost current story boundary", card.knowledgeBoundary.isNotEmpty())
            assertTrue("${preset.id} contains duplicated encyclopedia items", card.loreEntries.size <= 3)
        }
    }

    @Test
    fun starterPresetsDescribeCharacterWithoutRuntimeScripts() {
        PersonaPresetCatalog.presets.forEach { preset ->
            assertTrue(preset.persona.coreValues.isNotEmpty())
            assertTrue(preset.persona.coreTension.isNotBlank())
            assertTrue(preset.persona.attentionBiases.isNotEmpty())
            assertTrue(preset.persona.attentionKeywords.isNotEmpty())
            assertTrue(preset.persona.perceptionBlindSpots.isNotEmpty())
            assertTrue(preset.persona.quirks.isNotEmpty())
            assertTrue(preset.persona.limitations.isNotEmpty())
            assertTrue("${preset.id}: prewritten growth goals", preset.persona.mutableTraits.isEmpty())
            assertTrue("${preset.id}: synthetic reply scripts", preset.persona.voiceSamples.isEmpty())
            assertTrue("${preset.id}: predefined user relationship", preset.persona.initialUserImpression.isBlank())
            assertEquals("${preset.id}: situational behavior scripted as stable traits", 1, preset.persona.stableTraits.size)
            assertTrue("${preset.id}: relationship pacing scripted in hard limits",
                preset.persona.hardConstraints.none { "亲密" in it || "几句" in it || "关系变化" in it })
            assertTrue(preset.persona.portrait.isNotBlank())
        }
    }

    @Test
    fun starterPresetsUseCharacterSpecificLifeFieldsInsteadOfLegacyTemplateFiller() {
        val genericTension = "既有身份与个人愿望可能发生拉扯，具体变化必须由当前故事中的真实事件推动。"
        val genericValues = setOf("重要关系与现实责任", "自身判断与边界", "长期目标与个人愿望")
        val modelMetaBans = setOf("作为AI", "根据设定我应该", "身为一个语言模型")

        PersonaPresetCatalog.presets.forEach { preset ->
            assertTrue(preset.persona.coreTension.isNotBlank())
            assertTrue(preset.persona.coreTension != genericTension)
            assertTrue(preset.persona.coreValues.none { it in genericValues })
            assertTrue(preset.persona.bannedPhrases.none { it in modelMetaBans })
        }
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
