package com.labteto.dshmobile.ui

import androidx.compose.ui.test.junit4.createComposeRule
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
    @get:Rule val compose = createComposeRule()

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

        // Wait for real page content, not host-window focus: the focused window can vary
        // across Android versions. Dispatch the real system Back and verify the callback.
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(
            R.string.persona_gallery_add_title,
        )).assertIsDisplayed()
        pressDeviceBack()
        // A previous test's IME can retain focus on an emulator: the first real
        // Back closes that IME, and the next one must return from the gallery.
        compose.waitForIdle()
        if (backCount.get() == 0) {
            pressDeviceBack()
        }
        compose.waitUntil(timeoutMillis = 8_000L) { backCount.get() != 0 }
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }


}
