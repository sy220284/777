package com.labteto.dshmobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccentThemeTest {
    @Test
    fun legacyDefaultFollowsFluidApricot() {
        assertEquals("fluid_apricot", AccentPalettes.of(null).key)
        assertEquals("fluid_apricot", AccentPalettes.of("").key)
        assertEquals("fluid_apricot", AccentPalettes.of("celadon").key)
        assertEquals("流体杏", AccentPalettes.DEFAULT.cnName)
        assertEquals("fluid_apricot", AccentPalettes.of("unknown").key)
    }

    @Test
    fun explicitAccentColorsUpdateAllInteractiveFeedbackButPreserveStatusSemantics() {
        for (base in listOf(DsThemeTokens.light, DsThemeTokens.dark, DsThemeTokens.matteBlack)) {
            val dark = base !== DsThemeTokens.light
            for (palette in AccentPalettes.ALL.filter { it != AccentPalettes.DEFAULT }) {
                val themed = base.withAccent(palette, dark)
                assertEquals(palette.lightAccent.takeIf { !dark } ?: palette.darkAccent, themed.accent)
                assertNotEquals(themed.accent, themed.brandPrimary)
                assertNotEquals(base.buttonPrimaryDimmed, themed.buttonPrimaryDimmed)
                assertNotEquals(base.hoverAccent, themed.hoverAccent)
                assertNotEquals(base.active, themed.active)
                assertNotEquals(base.sidebarNavAccent, themed.sidebarNavAccent)
                if (!dark) assertNotEquals(base.hover, themed.hover)
                else assertEquals(base.hover, themed.hover)
                assertEquals(base.success, themed.success)
                assertEquals(base.warn, themed.warn)
                assertEquals(base.error, themed.error)
                assertEquals(base.characterAccent, themed.characterAccent)
                assertEquals(base.progressFailedStart, themed.progressFailedStart)
                assertEquals(base.progressFailedEnd, themed.progressFailedEnd)
            }
        }
    }

    @Test
    fun explicitBlueRemainsSelectable() {
        assertEquals("default_blue", AccentPalettes.of("default_blue").key)
    }

    @Test
    fun explicitCeladonRemainsSelectable() {
        assertEquals("celadon_explicit", AccentPalettes.of("celadon_explicit").key)
    }
}
