package com.labteto.dshmobile.ui

import androidx.compose.ui.test.junit4.createComposeRule
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

        // Keep the real system key event. Wait for the composable BackHandler to
        // register before dispatch; then wait for its observable callback result.
        compose.waitForIdle()
        pressDeviceBack()
        compose.waitUntil(timeoutMillis = 3_000) { backCount.get() == 1 }
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

        // Keep the real system key event. Wait for the composable BackHandler to
        // register before dispatch; then wait for its observable callback result.
        compose.waitForIdle()
        pressDeviceBack()
        compose.waitUntil(timeoutMillis = 3_000) { backCount.get() == 1 }
        assertEquals(1, backCount.get())
    }
}
