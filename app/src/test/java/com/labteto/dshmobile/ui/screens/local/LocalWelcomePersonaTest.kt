package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaPresetCatalog
import com.labteto.dshmobile.local.chat.PersonaProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWelcomePersonaTest {
    @Test
    fun emptyUserGalleryDisplaysBundledPortraits() {
        val presets = PersonaPresetCatalog.presets
        val result = welcomePersonaArtworks(emptyList(), presets)
        assertEquals(presets.count { !it.artwork?.assetPath.isNullOrBlank() }, result.size)
        assertTrue(result.all { it.presetAssetPath.startsWith("persona-presets/") })
    }

    @Test
    fun savedPortraitsComeFirstAndDoNotDuplicateInstalledPreset() {
        val presets = PersonaPresetCatalog.presets.take(2)
        val saved = listOf(
            PersonaGalleryEntry(id = "chibi", persona = PersonaProfile(name = "自定义Q版"), portraitPath = "/private/chibi.webp"),
            PersonaGalleryEntry(id = "installed", persona = presets.first().persona, portraitPath = "/private/installed.webp"),
            PersonaGalleryEntry(id = "no-image", persona = PersonaProfile(name = "无图人物")),
        )
        val result = welcomePersonaArtworks(saved, presets)
        assertEquals(3, result.size)
        assertEquals("saved:chibi", result[0].id)
        assertEquals("saved:installed", result[1].id)
        assertEquals("preset:${presets[1].id}", result[2].id)
    }

    @Test
    fun fiveHolographicCharactersHaveDedicatedArtworkAndUniqueIds() {
        val showcase = welcomeMotionPersonas
        assertEquals(listOf("神里绫华", "三月七", "沈星回", "夏以昼", "可莉"), showcase.map { it.name })
        assertEquals(5, showcase.map { it.id }.distinct().size)
        assertEquals(5, showcase.map { it.assetPath }.distinct().size)
        assertTrue(showcase.all { it.assetPath.startsWith("persona-motion/") && it.assetPath.endsWith(".webp") })
        assertTrue(showcase.none { it.assetPath.startsWith("persona-presets/") })
    }

    @Test
    fun tapsWrapAroundAndHandleSingleImage() {
        assertEquals(1, nextWelcomePersonaIndex(0, 3))
        assertEquals(2, nextWelcomePersonaIndex(1, 3))
        assertEquals(0, nextWelcomePersonaIndex(2, 3))
        assertEquals(0, nextWelcomePersonaIndex(10, 3))
        assertEquals(0, nextWelcomePersonaIndex(0, 1))
        assertEquals(0, nextWelcomePersonaIndex(0, 0))
    }
}