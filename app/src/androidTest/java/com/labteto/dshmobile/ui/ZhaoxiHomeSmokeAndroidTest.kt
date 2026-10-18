package com.labteto.dshmobile.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithTag
import com.labteto.dshmobile.MainActivity
import androidx.compose.ui.unit.dp
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
        val drawer = compose.onNodeWithTag("zhaoxi.drawer.surface", useUnmergedTree = true)
            .assertTopPositionInRootIsEqualTo(0.dp)
        val drawerBottom = drawer.getUnclippedBoundsInRoot().bottom
        val viewportBottom = compose.onRoot(useUnmergedTree = true).getUnclippedBoundsInRoot().bottom
        org.junit.Assert.assertEquals("侧栏背景必须延伸至导航栏底部",
            viewportBottom.value, drawerBottom.value, 1f)
        compose.onNodeWithContentDescription("全局搜索").assertExists()
        compose.onNodeWithContentDescription("新建会话").assertExists()
        compose.onNodeWithText("人物图集").assertExists()
        // #758: search is a real global page, and the drawer no longer needs a redundant close icon.
        compose.onNodeWithContentDescription("全局搜索").performClick()
        compose.onNodeWithText("搜索").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("人物图集").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("模型与账户").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("zhaoxi.home.create_chat").assertExists()
    }
    @Test fun newConversationDraftSurvivesActivityRecreationAndReturn() {
        val owner = androidx.lifecycle.ViewModelProvider(compose.activity)[com.labteto.dshmobile.ui.host.LocalApplicationFeaturePortsOwner::class.java]
        compose.waitUntil(30_000) { owner.sessions.snapshot().identity != null && !owner.sessions.snapshot().loading }
        compose.onNodeWithTag("zhaoxi.home.create_chat").performClick()
        // Select the actual composer. The offscreen session drawer has its own
        // editable search field and must never be mistaken for the conversation draft.
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("zhaoxi.composer.input", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val createdSessionId = owner.sessions.snapshot().identity?.sessionId
            ?: throw AssertionError("New conversation has no active session identity")
        compose.onNodeWithTag("zhaoxi.composer.input", useUnmergedTree = true)
            .performTextInput("待发送的草稿")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("zhaoxi.composer.input", useUnmergedTree = true)
            .assertTextEquals("待发送的草稿")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // Reopen the exact same session through the home recent-session entry.
        // Clicking the generic “聊天” tab only selects a mode; it does not open a session.
        val reopenTag = "zhaoxi.home.reopen_session.$createdSessionId"
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(reopenTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(reopenTag).performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("zhaoxi.composer.input", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("zhaoxi.composer.input", useUnmergedTree = true)
            .assertTextEquals("待发送的草稿")
        compose.runOnIdle {
            org.junit.Assert.assertEquals(createdSessionId, owner.sessions.snapshot().identity?.sessionId)
        }
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
