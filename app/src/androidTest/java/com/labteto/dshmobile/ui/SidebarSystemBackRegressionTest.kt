package com.labteto.dshmobile.ui

import androidx.activity.ComponentActivity
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
        // Verify the real AndroidX BackHandler registration through its owning Activity.
        // Injecting shell KEYCODE_BACK can target another focused emulator window in CI.
        compose.waitForIdle()
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }


}
