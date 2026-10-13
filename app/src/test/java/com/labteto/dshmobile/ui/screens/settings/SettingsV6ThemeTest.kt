package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.labteto.dshmobile.ui.theme.AccentPalettes
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.ui.theme.resolveV6ThemePalette
import com.shenyan.designsystem.foundation.ShenyanPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validate the actual DshTheme palette resolver consumed by SettingsScreen/SettingsCard,
 * not two reads from the same static palette constant. Wallpaper pixels and native
 * screenshots require device evidence and are not covered by these JVM checks.
 */
class SettingsV6ThemeTest {
    private val variants = listOf(
        ThemePreference.LIGHT to ShenyanPalettes.light,
        ThemePreference.DARK to ShenyanPalettes.dark,
        ThemePreference.MATTE_BLACK to ShenyanPalettes.matte,
    )

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.luminance()
        val b = background.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }

    @Test fun settingsUseActualV6ThemeProjectionInAllThreeModes() {
        for ((mode, canonical) in variants) {
            val resolved = resolveV6ThemePalette(mode, accentKey = null, systemDark = false)
            assertEquals(canonical, resolved.controls)
            assertEquals(canonical.canvas, resolved.page.bgBase)
            assertEquals(canonical.text, resolved.page.labelPrimary)
            assertEquals(canonical.textSecondary, resolved.page.labelSecondary)
            assertEquals(canonical.materialL3.compositeOver(canonical.canvas), resolved.page.bgLayer1)
            assertEquals(canonical.materialL2.compositeOver(canonical.canvas), resolved.page.bgLayer2)
            assertEquals(canonical.primary, resolved.controls.primary)
            assertTrue(resolved.isDefaultAccent)
            assertEquals(mode != ThemePreference.LIGHT, resolved.dark)
            // SettingsCard uses the shared L3 material, not a separately tinted old card.
            assertTrue("settings body contrast for $mode",
                contrast(resolved.controls.text, resolved.controls.materialL3.compositeOver(canonical.canvas)) >= 4.5f)
            assertTrue("settings secondary text contrast for $mode",
                contrast(resolved.controls.textSecondary, canonical.canvas) >= 4.5f)
        }
    }

    @Test fun selectedAccentUpdatesActualSettingsControlsButNotMaterialOrStatusColors() {
        for ((mode, canonical) in variants) {
            val dark = mode != ThemePreference.LIGHT
            for (accent in AccentPalettes.ALL.drop(1)) {
                val resolved = resolveV6ThemePalette(mode, accent.key, systemDark = !dark)
                val controls = resolved.controls
                val expected = if (dark) accent.darkAccent else accent.lightAccent
                assertFalse(resolved.isDefaultAccent)
                assertEquals(expected, controls.primary)
                assertEquals(expected, resolved.page.accent)
                assertEquals(if (dark) accent.onDark else accent.onLight, controls.onPrimary)
                assertEquals(canonical.canvas, controls.canvas)
                assertEquals(canonical.materialL1, controls.materialL1)
                assertEquals(canonical.materialL2, controls.materialL2)
                assertEquals(canonical.materialL3, controls.materialL3)
                assertEquals(canonical.materialL4, controls.materialL4)
                assertEquals(canonical.materialL5, controls.materialL5)
                assertEquals(canonical.text, controls.text)
                assertEquals(canonical.textSecondary, controls.textSecondary)
                assertEquals(canonical.warning, controls.warning)
                assertEquals(canonical.danger, controls.danger)
                assertEquals(canonical.mint, controls.mint)
            }
        }
    }

    @Test fun systemModeAndLegacyCeladonSelectionUseConsistentSourceOfTruth() {
        val light = resolveV6ThemePalette(ThemePreference.SYSTEM, "celadon", systemDark = false)
        val dark = resolveV6ThemePalette(ThemePreference.SYSTEM, "celadon", systemDark = true)
        assertEquals(ShenyanPalettes.light, light.controls)
        assertEquals(ShenyanPalettes.dark, dark.controls)
        assertFalse(light.dark)
        assertTrue(dark.dark)
        val matte = resolveV6ThemePalette(ThemePreference.MATTE_BLACK, null, systemDark = false)
        assertNotEquals(matte.controls.materialL1, matte.controls.materialL3)
    }
}
