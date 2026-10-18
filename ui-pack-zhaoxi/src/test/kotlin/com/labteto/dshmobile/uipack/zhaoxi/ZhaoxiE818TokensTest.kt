package com.labteto.dshmobile.uipack.zhaoxi

import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiHue
import com.labteto.dshmobile.uipack.zhaoxi.theme.accentFor
import com.labteto.dshmobile.uipack.zhaoxi.theme.wallpaperVeilAlpha
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

}
