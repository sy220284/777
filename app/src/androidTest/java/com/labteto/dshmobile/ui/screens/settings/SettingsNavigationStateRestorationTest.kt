package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.components.rememberNavigationEntryState
import com.labteto.dshmobile.ui.shenyan.SyHierarchyPage
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Rule
import org.junit.Test

class SettingsNavigationStateRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rotationRetainsTheActualCrossLinkSourceAndBackOrder() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DshTheme {
            var stack by rememberNavigationEntryState(listOf(SettingsDestination.USAGE), 1)
            Column {
                Button(onClick = { stack = settingsNavigationPush(stack, SettingsDestination.PRICING) }) { Text("pricing") }
                Button(onClick = { settingsNavigationPop(stack)?.let { stack = it } }) { Text("back") }
                SyHierarchyPage(page = stack, pageKey = { it.last().name }, depth = { it.size }) {
                    Text(it.last().name)
                }
            }
        } }
        compose.onNodeWithText("pricing").performClick()
        compose.onNodeWithText(SettingsDestination.PRICING.name).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(SettingsDestination.PRICING.name).assertIsDisplayed()
        compose.onNodeWithText("back").performClick()
        compose.onNodeWithText(SettingsDestination.USAGE.name).assertIsDisplayed()
        compose.onNodeWithText(SettingsDestination.ROOT.name).assertDoesNotExist()
    }
}
