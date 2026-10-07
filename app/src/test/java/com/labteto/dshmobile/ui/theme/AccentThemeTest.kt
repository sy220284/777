package com.labteto.dshmobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AccentThemeTest {
    @Test
    fun legacyDefaultCeladonResolvesToDefaultBlue() {
        assertEquals("default_blue", AccentPalettes.of(null).key)
        assertEquals("default_blue", AccentPalettes.of("").key)
        assertEquals("default_blue", AccentPalettes.of("celadon").key)
        assertEquals("default_blue", AccentPalettes.of("kimi").key)
        assertEquals("default_blue", AccentPalettes.of("unknown").key)
    }

    @Test
    fun explicitCeladonRemainsSelectable() {
        assertEquals("celadon_explicit", AccentPalettes.of("celadon_explicit").key)
    }
}
