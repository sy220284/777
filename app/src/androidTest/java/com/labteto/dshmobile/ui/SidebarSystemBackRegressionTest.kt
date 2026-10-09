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

    private fun pressBackWhenWindowFocused() {
        // A device-level key event targets the focused window; cold Android 16
        // emulator runs sometimes finish Compose idle before Activity focus.
        // Keep the OS Back injection (and the exact-once assertion) intact.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.activity.window.decorView.hasWindowFocus()
        }
        pressDeviceBack()
    }

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

        pressBackWhenWindowFocused()
        // A completed shell command does not guarantee UI-thread callback delivery.
        // Wait for the observable result; a second dispatch still fails the assertion.
        compose.waitUntil(timeoutMillis = 5_000) { backCount.get() != 0 }
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

        pressBackWhenWindowFocused()
        // A completed shell command does not guarantee UI-thread callback delivery.
        // Wait for the observable result; a second dispatch still fails the assertion.
        compose.waitUntil(timeoutMillis = 5_000) { backCount.get() != 0 }
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }
}
