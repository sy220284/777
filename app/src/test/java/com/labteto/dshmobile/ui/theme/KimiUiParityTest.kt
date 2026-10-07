package com.labteto.dshmobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class KimiUiParityTest {
    @Test
    fun lightPaletteMatchesKimi313FoundationTokens() {
        assertEquals(Color(0xFF1783FF), DsLight.accent)
        assertEquals(Color(0xFFFFFFFF), DsLight.bgBase)
        assertEquals(Color(0xFFF5F5F5), DsLight.bgModulePlatform)
        assertEquals(Color(0xE6000000), DsLight.labelPrimary)
        assertEquals(Color(0x99000000), DsLight.labelSecondary)
        assertEquals(Color(0x73000000), DsLight.labelTertiary)
        assertEquals(Color(0x21000000), DsLight.borderL3)
        assertEquals(Color(0xFFF5F5F5), DsLight.userBubble)
    }

    @Test
    fun darkPaletteMatchesKimi313FoundationTokens() {
        assertEquals(Color(0xFF1A88FF), DsDark.accent)
        assertEquals(Color(0xFF121212), DsDark.bgBase)
        assertEquals(Color(0xFF1F1F1F), DsDark.bgModulePlatform)
        assertEquals(Color(0xD6FFFFFF), DsDark.labelPrimary)
        assertEquals(Color(0x8FFFFFFF), DsDark.labelSecondary)
        assertEquals(Color(0x6BFFFFFF), DsDark.labelTertiary)
        assertEquals(Color(0xFF292929), DsDark.userBubble)
    }

    @Test
    fun motionDurationsMatchKimi313FoundationTokens() {
        assertEquals(60, DsAnimations.MICRO_MS)
        assertEquals(120, DsAnimations.FAST_MS)
        assertEquals(180, DsAnimations.VIEW_MS)
        assertEquals(180, DsAnimations.LIST_MS)
        assertEquals(200, DsAnimations.NORMAL_MS)
        assertEquals(240, DsAnimations.PANEL_MS)
        assertEquals(300, DsAnimations.SLOW_MS)
    }
}
