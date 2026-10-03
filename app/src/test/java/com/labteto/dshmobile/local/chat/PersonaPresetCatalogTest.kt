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
    fun starterPresetsContainBehaviorInsteadOfOnlyBiography() {
        PersonaPresetCatalog.presets.forEach { preset ->
            assertTrue(preset.persona.coreMotivations.isNotEmpty())
            assertTrue(preset.persona.behaviorPatterns.isNotEmpty())
            assertTrue(preset.persona.hardConstraints.isNotEmpty())
            assertTrue(preset.persona.speechStyle.isNotBlank())
        }
    }

    @Test
    fun bundledPresetArtworkMustStayUnderDedicatedAssetDirectory() {
        val artworkPaths = PersonaPresetCatalog.presets.mapNotNull { preset ->
            preset.artwork?.let { artwork -> preset.id to artwork.assetPath }
        }.toMap()

        assertEquals(
            mapOf(
                "genshin-kamisato-ayaka" to "persona-presets/genshin-kamisato-ayaka.webp",
                "hsr-kafka" to "persona-presets/hsr-kafka.webp",
                "wwm-zhao-er" to "persona-presets/wwm-zhao-er.webp",
                "love-deepspace-li-shen" to "persona-presets/love-deepspace-li-shen.webp",
            ),
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
