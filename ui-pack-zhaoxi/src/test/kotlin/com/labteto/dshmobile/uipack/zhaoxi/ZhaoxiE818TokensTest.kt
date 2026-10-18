package com.labteto.dshmobile.uipack.zhaoxi

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiHue
import com.labteto.dshmobile.uipack.zhaoxi.theme.accentFor
import com.labteto.dshmobile.uipack.zhaoxi.theme.wallpaperVeilAlpha
import com.labteto.dshmobile.uipack.zhaoxi.theme.resolveZhaoxiWallpaper
import com.labteto.dshmobile.uipack.zhaoxi.theme.zhaoxiWallpaperPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E8.18 设计包 tokens.css 是深色强调色的唯一视觉验收基准。 */
class ZhaoxiE818TokensTest {
    @Test
    fun darkAccentPaletteMatchesE818Design() {
        val expected = mapOf(
            ZhaoxiHue.STONE to Color(0xFF938D89),
            ZhaoxiHue.SAGE to Color(0xFF7C9483),
            ZhaoxiHue.STEEL to Color(0xFF6E88A0),
            ZhaoxiHue.MAUVE to Color(0xFF967F95),
            ZhaoxiHue.DUSK to Color(0xFFA08A76),
        )
        expected.forEach { (hue, color) ->
            assertEquals("E8.18 dark accent differs: ${hue.key}", color, accentFor(hue, dark = true).accent)
        }
        assertEquals(Color(0xFF8FA5BC), accentFor(ZhaoxiHue.STEEL, dark = true).accentInk)
        assertEquals(Color(0xFFA896A7), accentFor(ZhaoxiHue.MAUVE, dark = true).accentInk)
        assertEquals(Color(0xFFB29C86), accentFor(ZhaoxiHue.DUSK, dark = true).accentInk)
    }
    /** 点缀填色不变，只检查面向小字的 accentInk，并覆盖次级底色。 */
    @Test
    fun allFiveLightAccentTextColorsMeetAAOnNeutralSurfaces() {
        fun ratio(foreground: Color, background: Color): Float {
            val a = foreground.luminance()
            val b = background.luminance()
            return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
        }
        for (hue in ZhaoxiHue.entries) {
            val ink = accentFor(hue, dark = false).accentInk
            for (surface in listOf(Color.White, Color(0xFFF7F7F7))) {
                assertTrue(
                    "${hue.key} accentInk on $surface must be readable as normal text",
                    ratio(ink, surface) >= 4.5f,
                )
            }
        }
    }

    @Test
    fun wallpaperVeilAdaptsToRealImageBrightness() {
        assertEquals(.36f, wallpaperVeilAlpha(.1f, lightMode = true, adaptive = false), .0001f)
        assertTrue(wallpaperVeilAlpha(.1f, lightMode = true, adaptive = true) >
            wallpaperVeilAlpha(.9f, lightMode = true, adaptive = true))
        assertTrue(wallpaperVeilAlpha(.9f, lightMode = false, adaptive = true) >
            wallpaperVeilAlpha(.1f, lightMode = false, adaptive = true))
        for (light in listOf(true, false)) for (brightness in listOf(0f, .5f, 1f)) {
            assertTrue(wallpaperVeilAlpha(brightness, light, adaptive = true) in .38f..0.88f)
        }
    }

    @Test
    fun wallpaperSelectionKeepsAllTenOriginalChoices() {
        assertEquals((0..9).map { "w$it.webp" }, zhaoxiWallpaperPresets.map { it.key })
        val expected = mapOf(
            ZhaoxiHue.DUSK to 0, ZhaoxiHue.STONE to 2,
            ZhaoxiHue.SAGE to 4, ZhaoxiHue.MAUVE to 6, ZhaoxiHue.STEEL to 8,
        )
        for ((hue, i) in expected) {
            assertEquals("w$i.webp", resolveZhaoxiWallpaper(true, "auto", hue, false))
            assertEquals("w${i + 1}.webp", resolveZhaoxiWallpaper(true, "auto", hue, true))
        }
        assertEquals("w7.webp", resolveZhaoxiWallpaper(true, "w7.webp", ZhaoxiHue.STONE, false))
        assertEquals(null, resolveZhaoxiWallpaper(false, "w7.webp", ZhaoxiHue.STONE, false))
    }
}
