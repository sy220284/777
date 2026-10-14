package com.shenyan.designsystem.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.analyzeBackground
import com.shenyan.designsystem.foundation.ShenyanPalettes
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ShenyanMaterialQualityTest {
    @get:Rule val compose = createComposeRule()

    private fun wallpaper(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "material-quality-test.png").also { file ->
            val bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(100, 26, 160))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun colouredWallpaperTintsRealGlassAndFieldsKeepTheirCallbacks() {
        val file = wallpaper()
        val ready = AtomicBoolean()
        val value = mutableStateOf("draft")
        var clicks = 0
        try {
            compose.setContent { DshTheme(preference = ThemePreference.LIGHT, backgroundPath = file.absolutePath) {
                val loaded = LocalAppBackgroundState.current.hasImage
                SideEffect { ready.set(loaded) }
                SyMaterialFrame(modifier = Modifier.fillMaxWidth().testTag("glass")) {
                    Text("清晰的正文")
                    SyTextField(value.value, { value.value = it }, modifier = Modifier.testTag("field"))
                    SyPill("selected-filter", selected = true, onClick = {})
                    SyButton("apply", { clicks++ })
                    SyButton("disabled", { clicks++ }, enabled = false)
                }
            } }
            compose.waitUntil(5_000) { ready.get() }
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("glass").captureToImage().toPixelMap()
            val fill = pixels[pixels.width / 2, 6]
            assertTrue("Wallpaper hue must remain in the glass", fill.blue > fill.green + .02f)
            compose.onNodeWithTag("field").performTextReplacement("edited")
            compose.onNodeWithText("selected-filter").assertIsSelected()
            compose.onNodeWithText("apply").performClick()
            compose.onNodeWithText("disabled").assertIsNotEnabled().performClick()
            compose.runOnIdle { assertEquals("edited", value.value); assertEquals(1, clicks) }
        } finally { file.delete() }
    }

    @Test fun landscapeWallpaperSamplingExcludesColoursCroppedOffTheScreen() {
        val bitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.RED)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.GREEN }
            canvas.drawRect(100f, 0f, 200f, 100f, paint)
            paint.color = android.graphics.Color.BLUE
            canvas.drawRect(200f, 0f, 300f, 100f, paint)
            val full = analyzeBackground(bitmap)
            val visible = analyzeBackground(bitmap, IntSize(100, 200))
            // Portrait centre-crop retains the green middle only, not the hidden red/blue sides.
            assertTrue(visible.middle.averageColor.green > .9f)
            assertTrue(visible.middle.averageColor.red < .1f)
            assertTrue(full.middle.averageColor.green < .6f)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun changingTransparencyUpdatesRenderedGlassWithoutReplacingWallpaper() {
        val file = wallpaper()
        val ready = AtomicBoolean()
        val transparency = androidx.compose.runtime.mutableFloatStateOf(0f)
        try {
            compose.setContent {
                DshTheme(preference = ThemePreference.LIGHT, backgroundPath = file.absolutePath,
                    wallpaperSurfaceTransparency = transparency.floatValue) {
                    val loaded = LocalAppBackgroundState.current.hasImage
                    SideEffect { ready.set(loaded) }
                    SyMaterialFrame(modifier = Modifier.fillMaxWidth().testTag("adjustable-glass")) {
                        Text("前景保持清晰")
                    }
                }
            }
            compose.waitUntil(5_000) { ready.get() }
            compose.waitForIdle()
            val before = compose.onNodeWithTag("adjustable-glass").captureToImage().toPixelMap()
            val darker = before[before.width / 2, 6]
            compose.runOnIdle { transparency.floatValue = 1f }
            compose.waitForIdle()
            val after = compose.onNodeWithTag("adjustable-glass").captureToImage().toPixelMap()
            val airier = after[after.width / 2, 6]
            // The same purple image should be substantially more visible at the airy endpoint.
            assertTrue("Changing the saved control must change actual rendered pixels",
                (airier.blue - airier.green) > (darker.blue - darker.green) + .05f)
        } finally { file.delete() }
    }

    @Test fun replacingWallpaperPathReloadsImageAndRecalculatesRegionColour() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun colouredFile(name: String, colour: Int): File =
            File(context.cacheDir, name).also { file ->
                val bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(colour)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        val first = colouredFile("test-background-blue.png", android.graphics.Color.rgb(12, 35, 94))
        val second = colouredFile("test-background-yellow.png", android.graphics.Color.rgb(245, 233, 103))
        val path = androidx.compose.runtime.mutableStateOf(first.absolutePath)
        val displayedLuminance = java.util.concurrent.atomic.AtomicReference<Float?>(null)
        try {
            compose.setContent {
                DshTheme(preference = ThemePreference.LIGHT, backgroundPath = path.value) {
                    val luminance = LocalAppBackgroundState.current.analysis?.luminance
                    SideEffect { displayedLuminance.set(luminance) }
                    SyMaterialFrame(modifier = Modifier.fillMaxWidth()) { Text("更换背景后保持正常显示") }
                }
            }
            compose.waitUntil(5_000) { (displayedLuminance.get() ?: 1f) < .3f }
            compose.runOnIdle { path.value = second.absolutePath }
            compose.waitUntil(5_000) { (displayedLuminance.get() ?: 0f) > .7f }
            compose.onNodeWithText("更换背景后保持正常显示").assertExists()
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test fun matteKeepsAnOpaqueSemanticSurfaceOverTheSameWallpaper() {
        val file = wallpaper()
        val ready = AtomicBoolean()
        try {
            compose.setContent { DshTheme(preference = ThemePreference.MATTE_BLACK, backgroundPath = file.absolutePath) {
                val loaded = LocalAppBackgroundState.current.hasImage
                SideEffect { ready.set(loaded) }
                SyMaterialFrame(modifier = Modifier.fillMaxWidth().testTag("matte")) { Text("实色正文") }
            } }
            compose.waitUntil(5_000) { ready.get() }
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("matte").captureToImage().toPixelMap()
            val fill = pixels[pixels.width / 2, 6]
            val expected = ShenyanPalettes.matte.materialL3
            assertEquals(expected.red, fill.red, .01f)
            assertEquals(expected.green, fill.green, .01f)
            assertEquals(expected.blue, fill.blue, .01f)
        } finally { file.delete() }
    }
}
