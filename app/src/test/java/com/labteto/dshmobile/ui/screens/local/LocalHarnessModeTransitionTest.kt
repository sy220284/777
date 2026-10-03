package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalUsageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHarnessModeTransitionTest {
    @Test
    fun pendingModeWinsImmediatelyInDrawer() {
        assertEquals(
            LocalUsageMode.CHAT,
            localHarnessDrawerUsageMode(
                current = LocalUsageMode.WORK,
                pending = LocalUsageMode.CHAT,
            ),
        )
        assertEquals(
            LocalUsageMode.WORK,
            localHarnessDrawerUsageMode(
                current = LocalUsageMode.WORK,
                pending = null,
            ),
        )
    }

    @Test
    fun runningWorkCanSwitchToChatWhileRunningChatKeepsItsGuard() {
        assertTrue(
            localHarnessModeSwitchEnabled(
                current = LocalUsageMode.WORK,
                running = true,
            ),
        )
        assertFalse(
            localHarnessModeSwitchEnabled(
                current = LocalUsageMode.CHAT,
                running = true,
            ),
        )
        assertTrue(
            localHarnessModeSwitchEnabled(
                current = LocalUsageMode.WORK,
                running = false,
            ),
        )
        assertTrue(
            localHarnessModeSwitchEnabled(
                current = LocalUsageMode.CHAT,
                running = false,
            ),
        )
    }
}
