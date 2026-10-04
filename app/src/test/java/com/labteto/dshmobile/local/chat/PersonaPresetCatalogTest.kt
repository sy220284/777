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
