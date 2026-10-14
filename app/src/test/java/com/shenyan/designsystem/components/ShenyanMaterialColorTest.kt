package com.shenyan.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shenyan.designsystem.foundation.ShenyanPalettes
import com.shenyan.designsystem.foundation.SySceneBackdrop
import org.junit.Assert.*
import org.junit.Test

class ShenyanMaterialColorTest {
    private val backgrounds = listOf(Color(0xFF641AA0), Color(0xFF136B51), Color.White, Color.Black,
        Color(0xFF164D92), Color(0xFFE34B36), Color(0xFFDECA86))
    private fun scene(color: Color, transparency: Float = .5f, adaptive: Boolean = true) =
        SySceneBackdrop(IntOffset.Zero, IntSize(393, 852), color, regionColors = listOf(color, color, color),
            surfaceTransparency = transparency, adaptiveContrast = adaptive)

    @Test fun lightAndDarkTextAndStatusInksStayReadableAcrossColouredBrightAndDarkBackgrounds() {
        listOf(ShenyanPalettes.light, ShenyanPalettes.dark).forEach { palette ->
            backgrounds.forEach { background ->
                SyMaterialTier.entries.forEach { tier ->
                    val fill = syResolveMaterialFill(palette, tier, scene(background))
                    val composed = fill.compositeOver(background)
                    listOf(palette.text, palette.textSecondary, palette.textTertiary,
                        palette.successInk, palette.warningInk, palette.dangerInk).forEach { ink ->
                        assertTrue("$tier background=$background ink=$ink fill=$fill",
                            syContrastRatio(ink, composed) >= 4.49f)
                    }
                }
            }
        }
    }

    @Test fun colouredWallpaperTintsTheMaterialInsteadOfUsingPureWhite() {
        val fill = syResolveMaterialFill(ShenyanPalettes.light, SyMaterialTier.L3_CARD, scene(backgrounds.first()))
        assertTrue(fill.blue > fill.green)
        assertTrue(fill.red > fill.green)
        assertNotEquals(Color.White, fill.copy(alpha = 1f))
    }

    @Test fun cachedTopAndBottomRegionsUseTheirOwnColour() {
        val sample = scene(backgrounds[0]).copy(regionColors = listOf(backgrounds[0], backgrounds[1], backgrounds[4]))
        val top = syResolveMaterialFill(ShenyanPalettes.light, SyMaterialTier.L3_CARD, sample, .1f)
        val bottom = syResolveMaterialFill(ShenyanPalettes.light, SyMaterialTier.L3_CARD, sample, .9f)
        assertNotEquals(top, bottom)
    }

    @Test fun mixedBrightAndDarkTextureProtectsInkAtBothSampledExtremes() {
        val sample = scene(Color.Gray).copy(regionExtremes = List(3) { listOf(Color.White, Color.Black) })
        listOf(ShenyanPalettes.light, ShenyanPalettes.dark).forEach { palette ->
            val fill = syResolveMaterialFill(palette, SyMaterialTier.L3_CARD, sample)
            listOf(Color.White, Color.Black).forEach { background ->
                assertTrue(syContrastRatio(palette.textTertiary, fill.compositeOver(background)) >= 4.49f)
            }
        }
    }

    @Test fun matteStaysOpaqueAndKeepsExactSemanticTiersWithAnyWallpaper() {
        val palette = ShenyanPalettes.matte
        backgrounds.forEach { background ->
            assertEquals(palette.materialL1, syResolveMaterialFill(palette, SyMaterialTier.L1_SECTION, scene(background)))
            assertEquals(palette.materialL3, syResolveMaterialFill(palette, SyMaterialTier.L3_CARD, scene(background)))
            assertEquals(palette.materialL4, syResolveMaterialFill(palette, SyMaterialTier.L4_OVERLAY, scene(background)))
        }
    }

    @Test fun transparencyPreferenceStillChangesTheSurfaceWhenAdaptiveContrastIsDisabled() {
        val palette = ShenyanPalettes.light
        val clearer = syResolveMaterialFill(palette, SyMaterialTier.L3_CARD, scene(backgrounds[0], 1f, false))
        val protective = syResolveMaterialFill(palette, SyMaterialTier.L3_CARD, scene(backgrounds[0], 0f, false))
        assertTrue(clearer.alpha < protective.alpha)
        assertEquals(clearer.red, protective.red, .0001f)
    }

    @Test fun fluidBackgroundDerivesAllFiveTiersFromTheSameCanvasAndGlow() {
        val palette = ShenyanPalettes.light
        val plain = SySceneBackdrop(IntOffset.Zero, IntSize(393, 852), palette.canvas)
        val glow = plain.copy(glowColors = listOf(
            palette.primary, palette.mint, palette.lilac.copy(alpha = .18f),
        ))
        val top = SyMaterialTier.entries.map { syResolveMaterialFill(palette, it, glow, .15f) }
        val bottom = SyMaterialTier.entries.map { syResolveMaterialFill(palette, it, glow, .9f) }
        SyMaterialTier.entries.forEachIndexed { index, tier ->
            val base = syResolveMaterialFill(palette, tier, plain, .15f)
            assertNotEquals("The glow must participate in $tier", base, top[index])
            assertNotEquals("No tier should have a fixed white fill", Color.White, top[index])
            assertNotEquals("Top and bottom background colours must differ", top[index], bottom[index])
        }
        assertTrue(top.zipWithNext().all { (a, b) -> a.alpha <= b.alpha })
        assertEquals(1f, top.last().alpha)
    }

    @Test fun materialTokensMatchTheMainThemeHueEvenWithoutScene() {
        val palette = ShenyanPalettes.light
        assertEquals(palette.materialL5, syResolveMaterialFill(palette, SyMaterialTier.L5_SOLID, null))
        val root = palette.canvas
        SyMaterialTier.entries.forEach { tier ->
            val fill = syResolveMaterialFill(palette, tier, null)
            assertTrue("Material must stay warm rather than neutral white", fill.red >= fill.blue)
            assertTrue("Material must not introduce a bright white base",
                fill.copy(alpha = 1f) != Color.White)
        }
        assertTrue("Canvas colour is not a pure white surface", root != Color.White)
    }

    @Test fun missingSceneKeepsDesignedPaletteAndSolidFallbackIsStillTinted() {
        assertEquals(ShenyanPalettes.light.materialL3,
            syResolveMaterialFill(ShenyanPalettes.light, SyMaterialTier.L3_CARD, null))
        val solid = syResolveMaterialFill(ShenyanPalettes.light, SyMaterialTier.L5_SOLID, scene(backgrounds[0]))
        assertEquals(1f, solid.alpha)
        assertNotEquals(ShenyanPalettes.light.materialL5, solid)
    }
}
