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

        // These isolated Compose screens have a BackHandler but no app navigation host.
        // Dispatch through the Activity's real OnBackPressedDispatcher, as the system
        // does after key/gesture routing. This preserves the handler assertion without
        // depending on an emulator's unrelated focused window (Android 16).
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitUntil(timeoutMillis = 5_000) { backCount.get() == 1 }
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

        // These isolated Compose screens have a BackHandler but no app navigation host.
        // Dispatch through the Activity's real OnBackPressedDispatcher, as the system
        // does after key/gesture routing. This preserves the handler assertion without
        // depending on an emulator's unrelated focused window (Android 16).
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitUntil(timeoutMillis = 5_000) { backCount.get() == 1 }
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }
}
