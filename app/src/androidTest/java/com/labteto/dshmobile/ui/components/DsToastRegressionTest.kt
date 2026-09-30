package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

class DsToastRegressionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var show: (String) -> Unit

    private fun installToast() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val toast = rememberDsToast()
            SideEffect { show = toast.second }
            DshTheme { DsToastHost(toast) }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun showMessage(message: String) {
        compose.runOnIdle { show(message) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test
    fun sameMessageRestartsExpiryAndCanBeShownAfterExpiry() {
        installToast()
        showMessage("operation complete")
        compose.mainClock.advanceTimeBy(2_000)
        showMessage("operation complete")
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("operation complete").assertExists()
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithText("operation complete").assertDoesNotExist()
        showMessage("operation complete")
        compose.onNodeWithText("operation complete").assertExists()
    }

    @Test
    fun replacedMessageKeepsItsOwnExpiry() {
        installToast()
        showMessage("first result")
        compose.mainClock.advanceTimeBy(2_000)
        showMessage("second result")
        compose.onNodeWithText("first result").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("second result").assertExists()
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithText("second result").assertDoesNotExist()
    }
}
