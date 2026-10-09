package com.labteto.dshmobile.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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

    private val dispatcher = AtomicReference<OnBackPressedDispatcher>()

    private fun dispatchHostBack() {
        // These tests assert the registered BackHandler path. A shell keyevent
        // can be delivered before the ComposeTestActivity window has focus on
        // overloaded emulators; dispatch through the actual Activity owner.
        compose.runOnIdle {
            checkNotNull(dispatcher.get()) { "Compose back dispatcher owner is missing" }
                .onBackPressed()
        }
    }

    @Test
    fun personaGalleryAddPageConsumesSystemBackBeforeLeavingGallery() {
        val backCount = AtomicInteger(0)

        compose.setContent {
            DshTheme {
                dispatcher.set(
                    checkNotNull(LocalOnBackPressedDispatcherOwner.current) {
                        "BackHandler needs an Activity dispatcher owner"
                    }.onBackPressedDispatcher,
                )
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
        dispatchHostBack()
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }

    @Test
    fun remoteRelayStatusConsumesSystemBackAndReturnsToLocalAction() {
        val backCount = AtomicInteger(0)

        compose.setContent {
            DshTheme {
                dispatcher.set(
                    checkNotNull(LocalOnBackPressedDispatcherOwner.current) {
                        "BackHandler needs an Activity dispatcher owner"
                    }.onBackPressedDispatcher,
                )
                RemoteRelayStatus(
                    failed = true,
                    onRetryPairing = {},
                    onBack = { backCount.incrementAndGet() },
                )
            }
        }

        compose.waitForIdle()
        dispatchHostBack()
        compose.waitForIdle()

        assertEquals(1, backCount.get())
    }
}
