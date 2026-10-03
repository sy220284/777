package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaPresetCatalogTest {

    @Test
    fun starterPresetsCoverFourFranchisesAndUseStableIds() {
        val presets = PersonaPresetCatalog.presets

        assertEquals(4, presets.size)
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
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
        val artworkPaths = PersonaPresetCatalog.presets.associate { preset ->
            preset.id to requireNotNull(preset.artwork).assetPath
        }

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
