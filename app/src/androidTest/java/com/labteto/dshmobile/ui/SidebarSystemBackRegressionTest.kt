package com.labteto.dshmobile.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.SideEffect
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

    @Test
    fun personaGalleryAddPageConsumesSystemBackBeforeLeavingGallery() {
        val backCount = AtomicInteger(0)
        val backDispatcher = AtomicReference<OnBackPressedDispatcher>()

        compose.setContent {
            val backOwner = requireNotNull(LocalOnBackPressedDispatcherOwner.current) {
                "Compose host must expose its AndroidX system Back dispatcher"
            }
            SideEffect { backDispatcher.set(backOwner.onBackPressedDispatcher) }
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

        compose.waitForIdle()
        dispatchHostBack(requireNotNull(backDispatcher.get()))
        compose.runOnIdle { assertEquals(1, backCount.get()) }
    }

    @Test
    fun remoteRelayStatusConsumesSystemBackAndReturnsToLocalAction() {
        val backCount = AtomicInteger(0)
        val backDispatcher = AtomicReference<OnBackPressedDispatcher>()

        compose.setContent {
            val backOwner = requireNotNull(LocalOnBackPressedDispatcherOwner.current) {
                "Compose host must expose its AndroidX system Back dispatcher"
            }
            SideEffect { backDispatcher.set(backOwner.onBackPressedDispatcher) }
            DshTheme {
                RemoteRelayStatus(
                    failed = true,
                    onRetryPairing = {},
                    onBack = { backCount.incrementAndGet() },
                )
            }
        }

        compose.waitForIdle()
        dispatchHostBack(requireNotNull(backDispatcher.get()))
        compose.runOnIdle { assertEquals(1, backCount.get()) }
    }
}
