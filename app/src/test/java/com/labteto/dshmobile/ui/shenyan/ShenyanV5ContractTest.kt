package com.labteto.dshmobile.ui.shenyan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenyanV5ContractTest {
    @Test fun materialTransparencyHasExplicitTierOrder() {
        val light = SyPalettes.Light
        val dark = SyPalettes.Dark
        assertTrue(light.l1.alpha < light.l2.alpha)
        assertTrue(light.l2.alpha < light.l3.alpha)
        assertTrue(light.l3.alpha < light.l4.alpha)
        assertTrue(dark.l1.alpha < dark.l2.alpha)
        assertTrue(dark.l2.alpha < dark.l3.alpha)
        assertTrue(dark.l3.alpha < dark.l4.alpha)
        assertEquals(1f, SyPalettes.Matte.l1.alpha, .001f)
        assertEquals(1f, SyPalettes.Matte.l4.alpha, .001f)
    }

    @Test fun semanticStatusColoursAreDistinct() {
        val p = SyPalettes.Light
        assertTrue(p.clay != p.danger)
        assertTrue(p.mint != p.peach)
        assertTrue(p.lilac != p.mint)
    }

    @Test fun overlayMatchesThePageSpecAndMatteIsOpaqueAtEveryTier() {
        val light = com.shenyan.designsystem.foundation.ShenyanPalettes.light
        assertEquals(.88f, light.materialL4.alpha, .004f)
        assertEquals(250f / 255f, light.materialL4.red, .001f)
        assertEquals(247f / 255f, light.materialL4.green, .001f)
        assertEquals(242f / 255f, light.materialL4.blue, .001f)
        val matte = com.shenyan.designsystem.foundation.ShenyanPalettes.matte
        listOf(matte.materialL1, matte.materialL2, matte.materialL3, matte.materialL4, matte.materialL5)
            .forEach { assertEquals(1f, it.alpha, 0f) }
        assertEquals(360, com.shenyan.designsystem.foundation.SyMotion.pushMs)
    }

    @Test fun viewportContractMatchesV5() {
        assertEquals(258f, SyDimensions.navigationExpanded.value, 0f)
        assertEquals(64f, SyDimensions.navigationCollapsed.value, 0f)
        assertEquals(318f, SyDimensions.inspector.value, 0f)
        assertEquals(48f, SyDimensions.minimumTouch.value, 0f)
        assertEquals(280, SyDurations.sidebar)
        assertEquals(380, SyDurations.sheet)
    }

    @Test fun compatibilityPaletteIsAnAliasNotAnIndependentTheme() {
        val canonical = com.shenyan.designsystem.foundation.ShenyanPalettes
        assertEquals(canonical.light.materialL4, SyPalettes.Light.l4)
        assertEquals(canonical.light.topBar, SyPalettes.Light.topBar)
        assertEquals(canonical.dark.primary, SyPalettes.Dark.peach)
        assertEquals(canonical.dark.materialL3, SyPalettes.Dark.l3)
        assertEquals(canonical.matte.materialL5, SyPalettes.Matte.solid)
        assertEquals(canonical.matte.topBar, SyPalettes.Matte.topBar)
        assertEquals(canonical.matte.overlay, SyPalettes.Matte.scrim)
    }

    @Test fun systemModeHasDeterministicFallback() {
        assertEquals(SyPalettes.Light, SyPalettes.forMode(SyMode.SYSTEM, false))
        assertEquals(SyPalettes.Dark, SyPalettes.forMode(SyMode.SYSTEM, true))
    }
}
