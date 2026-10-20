package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.shenyan.designsystem.foundation.LocalSyColors
import org.junit.Rule
import org.junit.Test

class LocalReplyCompositionVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowReplyEmbedsMediaAtFullReadingWidthWithoutDuplicateResultHeader() {
        compose.setContent {
            DshTheme(preference = ThemePreference.LIGHT) {
                Column(Modifier.width(320.dp).background(LocalSyColors.current.canvas)) {
                    Text("报告正文：已经整理好预览图。")
                    MediaMessageCard(title = "", subtitle = "", inline = true,
                        modifier = Modifier.testTag("inline-media")) {
                        Text("预览图位置")
                    }
                    Text("报告结论：下一步验证移动端布局。")
                }
            }
        }
        compose.onNodeWithText("报告正文：已经整理好预览图。").assertIsDisplayed()
        compose.onNodeWithText("预览图位置").assertIsDisplayed()
        compose.onNodeWithText("报告结论：下一步验证移动端布局。").assertIsDisplayed()
        compose.onNodeWithTag("inline-media").assertWidthIsEqualTo(320.dp)
    }

    @Test fun standaloneMediaPreservesTheOriginalNarrowerContentLayoutInDarkMode() {
        compose.setContent {
            DshTheme(preference = ThemePreference.DARK) {
                Column(Modifier.width(320.dp)) {
                    MediaMessageCard(title = "视频处理中", subtitle = "创作记录",
                        modifier = Modifier.testTag("standalone-media")) {
                        Text("等待提供商处理")
                    }
                }
            }
        }
        compose.onNodeWithText("视频处理中").assertIsDisplayed()
        compose.onNodeWithText("创作记录").assertIsDisplayed()
        compose.onNodeWithTag("standalone-media").assertWidthIsEqualTo(288.dp)
    }
}
