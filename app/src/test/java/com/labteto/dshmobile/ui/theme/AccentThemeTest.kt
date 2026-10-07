package com.labteto.dshmobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AccentThemeTest {
    @Test
    fun legacyDefaultCeladonResolvesToKimi() {
        assertEquals("kimi", AccentPalettes.of(null).key)
        assertEquals("kimi", AccentPalettes.of("").key)
        assertEquals("kimi", AccentPalettes.of("celadon").key)
        assertEquals("kimi", AccentPalettes.of("unknown").key)
    }

    @Test
    fun explicitCeladonRemainsSelectable() {
        assertEquals("celadon_explicit", AccentPalettes.of("celadon_explicit").key)
    }
}
