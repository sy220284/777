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
                    // The explicitly clearer endpoint keeps strict foreground contrast.
                    val fill = syResolveMaterialFill(palette, tier, scene(background, transparency = 0f))
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
        val sample = scene(Color.Gray, transparency = 0f).copy(regionExtremes = List(3) { listOf(Color.White, Color.Black) })
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

    @Test fun adaptiveContrastNeverCancelsWallpaperTransparencyPreference() {
        listOf(ShenyanPalettes.light, ShenyanPalettes.dark).forEach { palette ->
            backgrounds.forEach { background ->
                listOf(SyMaterialTier.L1_SECTION, SyMaterialTier.L2_BAR,
                    SyMaterialTier.L3_CARD, SyMaterialTier.L4_OVERLAY).forEach { tier ->
                    val clearer = syResolveMaterialFill(palette, tier, scene(background, 0f))
                    val balanced = syResolveMaterialFill(palette, tier, scene(background, .5f))
                    val airier = syResolveMaterialFill(palette, tier, scene(background, 1f))
                    val label = "theme=${palette.canvas} tier=$tier wallpaper=$background"
                    assertTrue("$label clearer=$clearer balanced=$balanced",
                        clearer.alpha > balanced.alpha)
                    assertTrue("$label balanced=$balanced airier=$airier",
                        balanced.alpha > airier.alpha)
                    assertTrue("$label transparency range too small",
                        clearer.alpha - airier.alpha >= .10f)
                }
            }
        }
    }

    @Test fun airierGlassKeepsMoreOfTheWallpaperHueInsteadOfTurningIntoAChalkySlab() {
        val clearer = syResolveMaterialFill(ShenyanPalettes.light, SyMaterialTier.L3_CARD,
            scene(backgrounds[0], 0f))
        val airier = syResolveMaterialFill(ShenyanPalettes.light, SyMaterialTier.L3_CARD,
            scene(backgrounds[0], 1f))
        // This saturated purple background contributes progressively more violet to the plate.
        assertTrue(airier.blue - airier.green > clearer.blue - clearer.green)
        assertTrue(airier.alpha < clearer.alpha)
    }

    @Test fun transparencyPreferenceStillChangesTheSurfaceWhenAdaptiveContrastIsDisabled() {
        val palette = ShenyanPalettes.light
        val clearer = syResolveMaterialFill(palette, SyMaterialTier.L3_CARD, scene(backgrounds[0], 1f, false))
        val protective = syResolveMaterialFill(palette, SyMaterialTier.L3_CARD, scene(backgrounds[0], 0f, false))
        assertTrue(clearer.alpha < protective.alpha)
        assertTrue("With adaptive contrast off, airier glass must blend in more wallpaper hue",
            clearer.blue - clearer.green > protective.blue - protective.green)
    }

    @Test fun missingSceneKeepsDesignedPaletteAndSolidFallbackIsStillTinted() {
        assertEquals(ShenyanPalettes.light.materialL3,
            syResolveMaterialFill(ShenyanPalettes.light, SyMaterialTier.L3_CARD, null))
        val solid = syResolveMaterialFill(ShenyanPalettes.light, SyMaterialTier.L5_SOLID, scene(backgrounds[0]))
        assertEquals(1f, solid.alpha)
        assertNotEquals(ShenyanPalettes.light.materialL5, solid)
    }
}
