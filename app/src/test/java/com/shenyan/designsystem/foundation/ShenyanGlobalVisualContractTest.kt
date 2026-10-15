package com.shenyan.designsystem.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V6 visual tokens are one pack-owned contract, not page-local style snapshots. */
class ShenyanGlobalVisualContractTest {
    @Test fun allNormalThemesUseOrderedMaterialTiers() {
        listOf(ShenyanPalettes.light, ShenyanPalettes.dark).forEach { theme ->
            val tiers = listOf(theme.materialL1, theme.materialL2, theme.materialL3,
                theme.materialL4, theme.materialL5)
            assertTrue(tiers.zipWithNext().all { (a, b) -> a.alpha < b.alpha })
            assertEquals(1f, tiers.last().alpha, 0f)
        }
        val matte = ShenyanPalettes.matte
        listOf(matte.materialL1, matte.materialL2, matte.materialL3,
            matte.materialL4, matte.materialL5).forEach {
            assertEquals(1f, it.alpha, 0f)
        }
    }

    @Test fun wallpaperAndGlassShareOneTransparencyResponse() {
        assertEquals(.23f, SyMaterialPolicy.adjustment(0f), .0001f)
        assertEquals(0f, SyMaterialPolicy.adjustment(.5f), .0001f)
        assertEquals(-.23f, SyMaterialPolicy.adjustment(1f), .0001f)
        assertTrue(SyMaterialPolicy.adjustment(.25f) > SyMaterialPolicy.adjustment(.75f))
    }

    @Test fun motionTokensKeepNavigationPriorityAndRespectReducedMotion() {
        assertTrue(SyMotion.pressMs < SyMotion.selectMs)
        assertTrue("主页面动画应快速收敛", SyMotion.pushMs <= 300)
        assertTrue("同级页面切换不应拖沓", SyMotion.pagePeerMs <= 160)
        assertEquals(0, SyMotion.duration(SyMotion.pushMs, true, 1.3f))
        assertTrue(SyMotion.pagePeerMs < SyMotion.pagePopMs)
        assertTrue(SyMotion.pagePopMs < SyMotion.pushMs)
        assertTrue(SyMotion.pressedScale in 0f..1f)
        val specs = listOf(SyMotion.pressMs, SyMotion.selectMs, SyMotion.dialogMs,
            SyMotion.sheetMs, SyMotion.pagePeerMs, SyMotion.pushMs)
        specs.forEach { ms ->
            assertEquals(ms, SyMotion.duration(ms, false))
            assertEquals(0, SyMotion.duration(ms, true))
        }
        assertTrue(SyMotion.hapticIntervalMs > 0)
    }

    @Test fun advancedAppearanceDefaultsDoNotShiftTheDesign() {
        val defaults = com.labteto.dshmobile.connection.AppSettings()
        assertEquals(.5f, defaults.ambientGlow, .0001f)
        assertEquals(.5f, defaults.componentEmphasis, .0001f)
        assertEquals(.5f, defaults.wallpaperSurfaceTransparency, .0001f)
        assertEquals(1f, defaults.motionDurationScale, .0001f)
        assertTrue(defaults.backgroundAdaptiveContrast)
    }

    @Test fun unifiedMotionTempoNeverOverridesReducedMotion() {
        assertEquals(140, SyMotion.duration(200, false, .7f))
        assertEquals(200, SyMotion.duration(200, false, 1f))
        assertEquals(260, SyMotion.duration(200, false, 1.3f))
        assertEquals(0, SyMotion.duration(200, true, 1.3f))
    }
}
