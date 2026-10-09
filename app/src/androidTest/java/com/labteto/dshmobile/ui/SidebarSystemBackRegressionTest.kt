package com.labteto.dshmobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

        // Shell input targets the focused Android window, which can change
        // between emulator instrumentation cases. Confirm our Compose host owns
        // focus before sending exactly one real system Back key.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.activity.window.decorView.hasWindowFocus()
        }
        pressDeviceBack()
        compose.waitUntil(timeoutMillis = 8_000L) { backCount.get() != 0 }
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }

    @Test
    fun remoteRelayStatusConsumesSystemBackAndReturnsToLocalAction() {
        val backCount = AtomicInteger(0)

        compose.setContent {
            DshTheme {
                RemoteRelayStatus(
                    failed = true,
                    onRetryPairing = {},
                    onBack = { backCount.incrementAndGet() },
                )
            }
        }

        // Shell input targets the focused Android window, which can change
        // between emulator instrumentation cases. Confirm our Compose host owns
        // focus before sending exactly one real system Back key.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.activity.window.decorView.hasWindowFocus()
        }
        pressDeviceBack()
        compose.waitUntil(timeoutMillis = 8_000L) { backCount.get() != 0 }
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }
}
