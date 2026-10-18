package com.labteto.dshmobile.uipack.zhaoxi

import androidx.compose.ui.graphics.Color
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiHue
import com.labteto.dshmobile.uipack.zhaoxi.theme.accentFor
import com.labteto.dshmobile.uipack.zhaoxi.theme.zhaoxiColors
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
    @Test
    fun lightAccentTextMatchesOriginalE818TokensAndPassesWhiteContrast() {
        val expected = mapOf(
            ZhaoxiHue.STONE to Color(0xFF78716C),
            ZhaoxiHue.SAGE to Color(0xFF65796B),
            ZhaoxiHue.STEEL to Color(0xFF60778C),
            ZhaoxiHue.MAUVE to Color(0xFF846F83),
            ZhaoxiHue.DUSK to Color(0xFF867363),
        )
        expected.forEach { (hue, ink) ->
            val colors = zhaoxiColors(hue, dark = false)
            assertEquals("E8.18 light --accent-ink mismatch: ${hue.key}", ink, colors.accentInk)
            val foreground = ink.luminance()
            val background = colors.bg.luminance()
            val ratio = (maxOf(foreground, background) + 0.05f) /
                (minOf(foreground, background) + 0.05f)
            assertTrue("${hue.key} foreground/background contrast below 4.5:1: $ratio",
                ratio >= 4.5f)
        }
    }

}
