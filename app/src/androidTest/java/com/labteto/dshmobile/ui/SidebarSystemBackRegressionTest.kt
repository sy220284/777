package com.labteto.dshmobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

        // Shell-injected Back targets the currently focused window. Wait until
        // the Compose root is attached and idle before sending the real key.
        compose.waitForIdle()
        compose.onRoot().assertIsDisplayed()
        pressDeviceBack()
        // A synchronous shell command may finish before Android dispatches
        // the registered OnBackPressedCallback on the main thread.
        compose.waitUntil(timeoutMillis = 10_000L) { backCount.get() != 0 }
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

        // Shell-injected Back targets the currently focused window. Wait until
        // the Compose root is attached and idle before sending the real key.
        compose.waitForIdle()
        compose.onRoot().assertIsDisplayed()
        pressDeviceBack()
        // A synchronous shell command may finish before Android dispatches
        // the registered OnBackPressedCallback on the main thread.
        compose.waitUntil(timeoutMillis = 10_000L) { backCount.get() != 0 }
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }
}
