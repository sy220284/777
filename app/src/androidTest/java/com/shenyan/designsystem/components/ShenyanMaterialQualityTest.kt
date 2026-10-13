package com.shenyan.designsystem.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
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
            compose.onNodeWithText("apply").performClick()
            compose.onNodeWithText("disabled").assertIsNotEnabled().performClick()
            compose.runOnIdle { assertEquals("edited", value.value); assertEquals(1, clicks) }
        } finally { file.delete() }
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
