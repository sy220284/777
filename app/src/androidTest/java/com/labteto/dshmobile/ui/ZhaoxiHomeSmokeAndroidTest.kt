package com.labteto.dshmobile.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
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
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("模型与服务").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("＋ 新会话").assertExists()
    }
    @Test fun newConversationDraftSurvivesActivityRecreationAndReturn() {
        val owner = androidx.lifecycle.ViewModelProvider(compose.activity)[com.labteto.dshmobile.ui.host.LocalApplicationFeaturePortsOwner::class.java]
        compose.waitUntil(30_000) { owner.sessions.snapshot().identity != null && !owner.sessions.snapshot().loading }
        compose.onNodeWithText("＋ 新会话").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("待发送的草稿")
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction()).assertTextEquals("待发送的草稿")
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("聊天").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("待发送的草稿")
    }
}
