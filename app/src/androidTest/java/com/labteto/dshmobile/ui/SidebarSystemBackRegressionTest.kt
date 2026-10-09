package com.labteto.dshmobile.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
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

        // Await a fully composed foreground Activity before routing a real Back key.
        // Shell-level 'input keyevent' can be consumed by a different window on CI emulators.
        compose.waitForIdle()
        Espresso.pressBack()
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

        // Await a fully composed foreground Activity before routing a real Back key.
        // Shell-level 'input keyevent' can be consumed by a different window on CI emulators.
        compose.waitForIdle()
        Espresso.pressBack()
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }
}
