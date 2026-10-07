package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppSettingsMenuRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun groupedSettingsRowsRemainIndependentlyClickable() {
        val selected = mutableIntStateOf(0)
        compose.setContent {
            DshTheme {
                Column {
                    SettingsGroupTitle("体验")
                    AppSettingsSection {
                        AppSettingsRow(
                            icon = FeatherIcons.Sliders,
                            title = "外观与阅读",
                            value = "跟随系统",
                            onClick = { selected.intValue = 1 },
                        )
                        AppSettingsDivider()
                        AppSettingsRow(
                            icon = FeatherIcons.BookOpen,
                            title = "个性化与记忆",
                            value = "12",
                            onClick = { selected.intValue = 2 },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("外观与阅读").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, selected.intValue) }
        compose.onNodeWithText("个性化与记忆").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, selected.intValue) }
    }

    @Test
    fun longStatusDoesNotHideSettingsDestination() {
        compose.setContent {
            DshTheme {
                AppSettingsSection {
                    AppSettingsRow(
                        icon = FeatherIcons.Globe,
                        title = "模型设置",
                        value = "a-model-with-a-very-long-dynamic-provider-and-version-identifier",
                        onClick = {},
                    )
                }
            }
        }

        compose.onNodeWithText("模型设置").assertIsDisplayed()
    }
}
