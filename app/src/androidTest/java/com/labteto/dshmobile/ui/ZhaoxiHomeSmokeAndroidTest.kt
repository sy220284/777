package com.labteto.dshmobile.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.labteto.dshmobile.MainActivity
import org.junit.Rule
import org.junit.Test

/** Device gate: a fully installed UI Pack must paint the real home page, not an admission error. */
class ZhaoxiHomeSmokeAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsInteractiveSessionAndMainRoutes() {
        compose.onNodeWithContentDescription("打开侧栏").assertExists()
        compose.onNodeWithTag("zhaoxi.home.create_chat").assertExists()
        compose.onNodeWithText("人物").assertExists()
        compose.onNodeWithContentDescription("打开侧栏").performClick()
        compose.onNodeWithText("搜索全部人物、任务、文件").assertExists()
        compose.onNodeWithText("人物图集").assertExists()
        compose.onNodeWithContentDescription("关闭侧栏").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("人物图集").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("模型与服务").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("zhaoxi.home.create_chat").assertExists()
    }
    @Test fun newConversationDraftSurvivesActivityRecreationAndReturn() {
        val owner = androidx.lifecycle.ViewModelProvider(compose.activity)[com.labteto.dshmobile.ui.host.LocalApplicationFeaturePortsOwner::class.java]
        compose.waitUntil(30_000) { owner.sessions.snapshot().identity != null && !owner.sessions.snapshot().loading }
        compose.onNodeWithTag("zhaoxi.home.create_chat").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("待发送的草稿")
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction()).assertTextEquals("待发送的草稿")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("聊天").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("待发送的草稿")
    }
    @Test fun allE817IconsLoadAsNativeVectors() {
        val names = com.labteto.dshmobile.uipack.zhaoxi.components.ZhaoxiIconResources.all
        org.junit.Assert.assertEquals(43, names.size)
        compose.runOnIdle {
            names.forEach { (name, resource) ->
                val vector = requireNotNull(androidx.core.content.res.ResourcesCompat.getDrawable(compose.activity.resources, resource, compose.activity.theme)) { name }
                vector.setBounds(0, 0, 96, 96)
                val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                vector.draw(android.graphics.Canvas(bitmap))
                val pixels = IntArray(96 * 96)
                bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
                org.junit.Assert.assertTrue("$name has no painted geometry", pixels.any { android.graphics.Color.alpha(it) != 0 })
                bitmap.recycle()
            }
        }
    }

}
