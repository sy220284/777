package com.labteto.dshmobile.ui

import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import com.labteto.dshmobile.ui.screens.local.PersonaGalleryAddPanel
import com.labteto.dshmobile.ui.theme.DshTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SidebarSystemBackRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val composeHostView = AtomicReference<View>()

    @Test
    fun personaGalleryAddPageConsumesSystemBackBeforeLeavingGallery() {
        val backCount = AtomicInteger(0)

        compose.setContent {
            val host = LocalView.current
            SideEffect { composeHostView.set(host) }
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

        // Shell input must target the focused ComposeTestActivity window. Capture
        // the actual Compose view without launching a second, undeclared Activity,
        // then dispatch exactly one real system Back key.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000L) {
            composeHostView.get()?.hasWindowFocus() == true
        }
        pressDeviceBack()
        compose.waitUntil(timeoutMillis = 8_000L) { backCount.get() != 0 }
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }


}
