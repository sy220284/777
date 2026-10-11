package com.labteto.dshmobile.ui

import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.screens.local.PersonaGalleryAddPanel
import com.labteto.dshmobile.ui.theme.DshTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SidebarSystemBackRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun personaGalleryAddPageConsumesSystemBackBeforeLeavingGallery() {
        val backCount = AtomicInteger(0)

        compose.setContent {
            DshTheme {
                PersonaGalleryAddPanel(
                    presets = emptyList(),
                    installedPresetIds = emptySet(),
                    busy = false,
                    onBack = { backCount.incrementAndGet() },
                    onCreate = {},
                    onImport = {},
                    onInstallPreset = { _ -> },
                    onRequestHidePreset = { _ -> },
                )
            }
        }

        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(
            R.string.persona_gallery_add_title,
        )).assertIsDisplayed()
        // Composition readiness is not input readiness. Previous field tests may leave an IME
        // dismissal in flight; wait for this activity to own the focused input window first.
        compose.waitUntil(timeoutMillis = 8_000L) { compose.activity.hasWindowFocus() }
        compose.runOnUiThread {
            ViewCompat.getWindowInsetsController(compose.activity.window.decorView)
                ?.hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(timeoutMillis = 8_000L) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        pressDeviceBack()
        compose.waitUntil(timeoutMillis = 8_000L) { backCount.get() != 0 }
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }


}
