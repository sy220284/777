package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsSearchUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aliasSearchOpensTheRealPermissionDestination() {
        var selected: SettingsSearchEntry? = null
        compose.setContent {
            DshTheme {
                var query by remember { mutableStateOf("") }
                Column(Modifier.fillMaxSize()) {
                    SettingsSearchResults(query, { query = it }, { selected = it })
                }
            }
        }
        compose.onNodeWithTag("settings-search-input").performTextInput("通知使用权")
        compose.onNodeWithText("通知读取").assertIsDisplayed()
        com.labteto.dshmobile.ui.audit.captureUiAudit(compose, "dsh-flow-settings-search")
        compose.onNodeWithText("通知读取").performClick()
        compose.runOnIdle { assertEquals(SettingsDestination.PERMISSIONS, selected?.destination) }
    }

    @Test fun searchTargetScrollsIntoViewAndHighlightsTheActualHeading() {
        compose.setContent {
            DshTheme {
                CompositionLocalProvider(LocalSettingsSearchFocus provides SettingsSearchFocus("目标设置", "target")) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        repeat(30) { SettingsGroupTitle("其他设置 $it") }
                        SettingsGroupTitle("目标设置")
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-search-target").assertIsDisplayed()
    }
}
