package com.labteto.dshmobile.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class NavigationEntryStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reenteringSavedFeatureUsesTheNewExplicitTarget() {
        val mounted = mutableStateOf(true)
        val request = mutableStateOf(1)
        val initial = mutableStateOf("old-target")
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            if (mounted.value) holder.SaveableStateProvider("feature") {
                var target by rememberNavigationEntryState(initial.value, request.value)
                Text(target)
            }
        }
        compose.onNodeWithText("old-target").assertIsDisplayed()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { initial.value = "new-target"; request.value = 2; mounted.value = true }
        compose.onNodeWithText("new-target").assertIsDisplayed()
        compose.onNodeWithText("old-target").assertDoesNotExist()
    }

    @Test fun rotationKeepsLocalNavigationWithinTheSameEntry() {
        val initial = mutableStateOf("entry")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var current by rememberNavigationEntryState(initial.value, 1)
            androidx.compose.material3.Button(onClick = { current = "nested-page" }) { Text("open") }
            Text(current)
        }
        compose.onNodeWithText("open").performClick()
        compose.onNodeWithText("nested-page").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("nested-page").assertIsDisplayed()
    }
}
