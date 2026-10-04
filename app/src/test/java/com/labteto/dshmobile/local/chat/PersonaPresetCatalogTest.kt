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
    fun starterPresetsContainLivingCharacterSignals() {
        PersonaPresetCatalog.presets.forEach { preset ->
            assertTrue(preset.persona.coreValues.isNotEmpty())
            assertTrue(preset.persona.attentionBiases.isNotEmpty())
            assertTrue(preset.persona.perceptionBlindSpots.isNotEmpty())
            assertTrue(preset.persona.quirks.isNotEmpty())
            assertTrue(preset.persona.limitations.isNotEmpty())
            assertTrue(preset.persona.mutableTraits.isNotEmpty())
            assertTrue(preset.persona.voiceSamples.size >= 3)
            assertTrue(preset.persona.voiceSamples.all { it.isNotBlank() })
            assertTrue(preset.persona.voiceSamples.distinct().size >= 3)
            assertTrue(preset.persona.hardConstraints.isNotEmpty())
            assertTrue(preset.persona.portrait.isNotBlank())
        }
    }

    @Test
    fun starterPresetsUseCharacterSpecificLifeFieldsInsteadOfLegacyTemplateFiller() {
        val genericTension = "既有身份与个人愿望可能发生拉扯，具体变化必须由当前故事中的真实事件推动。"
        val genericValues = setOf("重要关系与现实责任", "自身判断与边界", "长期目标与个人愿望")
        val modelMetaBans = setOf("作为AI", "根据设定我应该", "身为一个语言模型")

        PersonaPresetCatalog.presets.forEach { preset ->
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
