package com.labteto.dshmobile.ui.screens.local

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

class LocalFeatureAnimatedNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidlyReturningToTheOutgoingPageDoesNotDuplicateItsSavedStateProvider() {
        val stack = mutableStateOf(localFeatureHome())
        compose.setContent { DshTheme {
            val holder = rememberSaveableStateHolder()
            LocalFeatureAnimatedHost(stack.value) { page ->
                holder.SaveableStateProvider(page.name) { Text(page.name) }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { stack.value = localFeaturePush(stack.value, LocalFeaturePage.SETTINGS) }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { stack.value = localFeaturePop(stack.value) }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { stack.value = localFeaturePush(stack.value, LocalFeaturePage.WORKSPACE) }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { stack.value = localFeaturePop(stack.value) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(LocalFeaturePage.HOME.name).assertIsDisplayed()
    }
}
