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

        assertDeviceBackDeliveredToForegroundActivity(backCount)
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

        assertDeviceBackDeliveredToForegroundActivity(backCount)
    }
    /** Ensure the Compose owner actually has focus before injecting a real system Back.
     *  Never retry the key press: that would hide a double-dispatch navigation bug.
     */
    private fun assertDeviceBackDeliveredToForegroundActivity(backCount: AtomicInteger) {
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.activity.window.decorView.hasWindowFocus()
        }

        pressDeviceBack()
        // Shell keyevent completion and Activity dispatch can be one frame apart;
        // wait for the observable callback instead of asserting on a scheduler race.
        compose.waitUntil(timeoutMillis = 5_000L) { backCount.get() != 0 }
        assertEquals(1, backCount.get())
    }

}
