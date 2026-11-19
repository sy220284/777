package com.shenyan.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import java.util.ArrayDeque
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.ShenyanPalettes
import com.shenyan.designsystem.foundation.SySceneBackdrop
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyGlassOpticsTest {
    private fun policy(
        tier: SyMaterialTier,
        fill: Color = Color(0xC2FFFFFF),
        reduced: Boolean = false,
        nested: Int = 0,
        background: Boolean = true,
    ) = syGlassOptics(ShenyanPalettes.light, tier, fill, reduced, nested, background)

    @Test fun contextualChromeHasDistinctOpticalLayers() {
        val bar = policy(SyMaterialTier.L2_BAR)
        val card = policy(SyMaterialTier.L3_CARD)
        val sheet = policy(SyMaterialTier.L4_OVERLAY)
        assertTrue(bar.glass)
        assertEquals(0.dp, bar.elevation)
        assertTrue(card.glass)
        assertEquals(0.dp, card.elevation)
        assertTrue(sheet.glass)
        assertEquals(0.dp, sheet.elevation)
        assertTrue(sheet.reflection > card.reflection)
    }

    @Test fun fullResolutionShaderTargetsRealPixelsWithoutSpendingGpuOnAllCards() {
        assertTrue(syShouldRefract(SyMaterialTier.L2_BAR, 0, false))
        assertTrue(syShouldRefract(SyMaterialTier.L4_OVERLAY, 0, false))
        assertFalse(syShouldRefract(SyMaterialTier.L3_CARD, 0, false))
        assertTrue(syShouldRefract(SyMaterialTier.L3_CARD, 0, false, interactive = true))
        assertFalse(syShouldRefract(SyMaterialTier.L3_CARD, 1, false, interactive = true))
        assertFalse(syShouldRefract(SyMaterialTier.L2_BAR, 1, false))
        assertTrue(syShouldRefract(SyMaterialTier.L3_CARD, 1, true))
        assertFalse(syShouldRefract(SyMaterialTier.L5_SOLID, 0, false))
        assertTrue(SyLiquidShader.PROGRAM.contains("source.eval"))
        assertTrue(SyLiquidShader.PROGRAM.contains("signedDistance"))
        assertTrue(SyLiquidShader.PROGRAM.contains("touchAmount"))
        assertTrue(SyLiquidShader.PROGRAM.contains("glassTint"))
    }

    @Test fun liquidVeilRetainsBackgroundAndAccentKeepsTheSameOpacity() {
        val fill = Color(0xF7FFFFFF)
        val clear = syLiquidTint(fill, Color.Unspecified)
        val accented = syLiquidTint(fill, Color(0xFFAACCFF))
        assertTrue(clear.alpha in .65f.. .74f)
        assertEquals(clear.alpha, accented.alpha, .002f)
        assertTrue(accented.red < clear.red)
    }

    @Test fun matteChromeAndOverlayRefractWhileSolidReadingAreasStayOpaque() {
        val dark = ShenyanPalettes.matte
        for (tier in listOf(SyMaterialTier.L2_BAR, SyMaterialTier.L3_CARD, SyMaterialTier.L4_OVERLAY)) {
            val fill = when (tier) {
                SyMaterialTier.L2_BAR -> dark.materialL2
                SyMaterialTier.L3_CARD -> dark.materialL3
                else -> dark.materialL4
            }
            val optics = syGlassOptics(dark, tier, fill, false, 0, true)
            assertTrue("Matte $tier should retain glass optics", optics.glass)
            assertEquals(0.dp, optics.elevation)
        }
        for (tier in listOf(SyMaterialTier.L1_SECTION, SyMaterialTier.L5_SOLID)) {
            assertFalse(syGlassOptics(dark, tier, dark.materialL5, false, 0, true).glass)
        }
        assertFalse(syGlassOptics(dark, SyMaterialTier.L2_BAR,
            dark.materialL2, true, 0, true).glass)
    }

    @Test fun reducedTransparencyNoSceneOpaqueFillsAndNonGlassTiersStaySolid() {
        val cases = listOf(
            policy(SyMaterialTier.L2_BAR, background = false),
            policy(SyMaterialTier.L2_BAR, fill = Color.White),
            policy(SyMaterialTier.L2_BAR, reduced = true),
            policy(SyMaterialTier.L2_BAR, nested = 3),
            policy(SyMaterialTier.L5_SOLID),
            policy(SyMaterialTier.L1_SECTION),
        )
        for (style in cases) {
            assertFalse(style.glass)
            assertEquals(0f, style.reflection)
        }
    }

    @Test fun fortyEightMillisecondWindowSuppressesSingleEventVelocitySpikes() {
        val history = ArrayDeque<Pair<Long, Offset>>()
        val start = syWindowedGlassDrag(history, 0L, Offset.Zero)
        assertEquals(0f, start.x, 0.001f)
        val first = syWindowedGlassDrag(history, 8L, Offset(10f, 0f))
        assertEquals(20f, first.x, 0.001f)
        val second = syWindowedGlassDrag(history, 16L, Offset(20f, 0f))
        assertEquals(20f, second.x, 0.001f)
        // An extra 1ms event must not produce a 12x flicker in curvature.
        val third = syWindowedGlassDrag(history, 17L, Offset(20.5f, 0f))
        assertTrue(third.x in 18f..21f)
        val rested = syWindowedGlassDrag(history, 120L, Offset(20.5f, 0f))
        assertEquals(0f, rested.x, 0.001f)
    }

    @Test fun nestedSurfacesStayFreeOfDropShadows() {
        for (tier in listOf(SyMaterialTier.L3_CARD, SyMaterialTier.L4_OVERLAY)) {
            val style = policy(tier, nested = 1)
            assertTrue(style.glass)
            assertEquals(0.dp, style.elevation)
        }
    }
    @Test fun globalGlassStaysOpticallyActiveAcrossSolidAndWallpaperColors() {
        val backgrounds = listOf(
            Color.White,
            Color.Black,
            Color(0xFFBA4A31),
            Color(0xFF22655B),
            Color(0xFFFFD24D),
            Color(0xFF353C7F),
        )
        for (palette in listOf(ShenyanPalettes.light, ShenyanPalettes.matte)) {
            for (background in backgrounds) {
                val scene = SySceneBackdrop(
                    viewportOrigin = IntOffset.Zero,
                    viewportSize = IntSize(412, 915),
                    canvas = palette.canvas,
                    image = null,
                    regionColors = listOf(background, background, background),
                    regionExtremes = listOf(
                        listOf(background, background),
                        listOf(background, background),
                        listOf(background, background),
                    ),
                    surfaceTransparency = .5f,
                )
                for (tier in listOf(SyMaterialTier.L2_BAR, SyMaterialTier.L3_CARD, SyMaterialTier.L4_OVERLAY)) {
                    val fill = syResolveMaterialFill(palette, tier, scene)
                    assertTrue("Glass should remain translucent for $background / $tier", fill.alpha < 1f)
                    assertTrue("Optics disabled on $background / $tier",
                        syGlassOptics(palette, tier, fill, false, 0, true).glass)
                }
                assertEquals(1f,
                    syResolveMaterialFill(palette, SyMaterialTier.L5_SOLID, scene).alpha, .0001f)
            }
        }
    }

}
