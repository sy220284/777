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

        // Dispatch to the exact Activity that owns this Compose BackHandler.
        // Global shell key injection can target another focused window on Android 16;
        // that path remains exercised by separate dialog/system-key tests.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

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

        // Dispatch to the exact Activity that owns this Compose BackHandler.
        // Global shell key injection can target another focused window on Android 16;
        // that path remains exercised by separate dialog/system-key tests.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }
}
