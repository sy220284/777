package com.labteto.dshmobile.ui

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.labteto.dshmobile.MainActivity
import org.junit.Rule
import org.junit.Test

/** Device gate: a fully installed UI Pack must paint the real home page, not an admission error. */
class ZhaoxiHomeSmokeAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsInteractiveSessionAndMainRoutes() {
        compose.onNodeWithText("朝夕").assertExists()
        compose.onNodeWithText("＋ 新会话").assertExists()
        compose.onNodeWithText("人物").assertExists()
        compose.onNodeWithText("设置").assertExists()
    }
}
