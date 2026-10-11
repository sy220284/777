package com.labteto.dshmobile.ui.theme

import org.junit.Assert.assertEquals
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
    fun explicitBlueRemainsSelectable() {
        assertEquals("default_blue", AccentPalettes.of("default_blue").key)
    }

    @Test
    fun explicitCeladonRemainsSelectable() {
        assertEquals("celadon_explicit", AccentPalettes.of("celadon_explicit").key)
    }
}
