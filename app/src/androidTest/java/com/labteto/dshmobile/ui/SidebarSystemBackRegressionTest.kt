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

        compose.waitForIdle() // ensure BackHandler is registered before the key
        pressDeviceBack()
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

        compose.waitForIdle() // ensure BackHandler is registered before the key
        pressDeviceBack()
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }
}
