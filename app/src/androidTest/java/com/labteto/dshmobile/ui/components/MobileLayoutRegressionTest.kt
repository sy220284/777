package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MobileLayoutRegressionTest {
    @get:Rule val compose = createComposeRule()

    // Representative phone window widths in dp. Emulator viewports must be >= 412dp:
    // otherwise a 412dp test would silently be constrained to the physical screen width.
    private val phoneWidthsDp = listOf(360, 393, 412)

    @Test
    fun mainstreamPhoneLargeTypeHeaderKeepsActionsOutsideText() {
        var back = 0
        var refreshed = 0
        val widthDp = mutableIntStateOf(phoneWidthsDp.first())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                DshTheme {
                    Box(Modifier.width(widthDp.intValue.dp)) {
                        DsTopBar(
                            title = "个性化与长期记忆",
                            subtitle = "查看和管理聊天中记住的内容",
                            onBack = { back++ },
                            backContentDescription = "返回",
                            actionIcon = FeatherIcons.RefreshCw,
                            actionContentDescription = "刷新",
                            onAction = { refreshed++ },
                        )
                    }
                }
            }
        }
        phoneWidthsDp.forEachIndexed { index, viewport ->
            compose.runOnIdle { widthDp.intValue = viewport }
            compose.waitForIdle()
            val title = compose.onNodeWithText("个性化与长期记忆").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val subtitle = compose.onNodeWithText("查看和管理聊天中记住的内容").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val backBounds = compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
            val actionBounds = compose.onNodeWithContentDescription("刷新").fetchSemanticsNode().boundsInRoot
            assertTrue("宽度 ${viewport}dp：标题不得覆盖操作按钮",
                title.left >= backBounds.right && title.right <= actionBounds.left)
            assertTrue("宽度 ${viewport}dp：副标题不得覆盖操作按钮",
                subtitle.top >= backBounds.bottom && subtitle.top >= actionBounds.bottom)
            assertTrue("宽度 ${viewport}dp：标题与副标题不得重叠", subtitle.top >= title.bottom)
            assertTextFits("个性化与长期记忆")
            assertTextFits("查看和管理聊天中记住的内容")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("刷新").performClick()
            compose.runOnIdle {
                assertEquals(index + 1, back)
                assertEquals(index + 1, refreshed)
            }
        }
    }

    @Test
    fun extraLargeTypeHeaderWrapsWithoutClippingOnMainstreamPhones() {
        val widthDp = mutableIntStateOf(phoneWidthsDp.first())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                DshTheme {
                    Box(Modifier.width(widthDp.intValue.dp)) {
                        DsTopBar(
                            title = "个性化与长期记忆",
                            subtitle = "查看和管理聊天中记住的内容",
                            onBack = {},
                            backContentDescription = "返回",
                            actionIcon = FeatherIcons.RefreshCw,
                            actionContentDescription = "刷新",
                            onAction = {},
                        )
                    }
                }
            }
        }
        for (viewport in phoneWidthsDp) {
            compose.runOnIdle { widthDp.intValue = viewport }
            compose.waitForIdle()
            val title = compose.onNodeWithText("个性化与长期记忆").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val subtitle = compose.onNodeWithText("查看和管理聊天中记住的内容").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val back = compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
            val action = compose.onNodeWithContentDescription("刷新").fetchSemanticsNode().boundsInRoot
            assertTrue("宽度 ${viewport}dp：超大字体标题不得覆盖操作按钮",
                title.left >= back.right && title.right <= action.left)
            assertTrue("宽度 ${viewport}dp：超大字体副标题不得重叠",
                subtitle.top >= title.bottom && subtitle.top >= back.bottom && subtitle.top >= action.bottom)
            assertTextFits("个性化与长期记忆")
            assertTextFits("查看和管理聊天中记住的内容")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("刷新").performClick()
        }
    }

    @Test
    fun mainstreamPhoneLargeTypeCategoryKeepsValueAndNavigationUsable() {
        var opened = 0
        val widthDp = mutableIntStateOf(phoneWidthsDp.first())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                DshTheme {
                    Box(Modifier.width(widthDp.intValue.dp)) {
                        DsCategoryRow(
                            icon = FeatherIcons.Folder,
                            title = "工作项目与文件",
                            subtitle = "查看项目中保存的文件",
                            value = "9999 个项目",
                            onClick = { opened++ },
                        )
                    }
                }
            }
        }
        phoneWidthsDp.forEachIndexed { index, viewport ->
            compose.runOnIdle { widthDp.intValue = viewport }
            compose.waitForIdle()
            val title = compose.onNodeWithText("工作项目与文件", useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val value = compose.onNodeWithText("9999 个项目", useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("宽度 ${viewport}dp：分类标题与数量不得重叠",
                title.right <= value.left || title.bottom <= value.top)
            assertTextFits("工作项目与文件")
            assertTextFits("9999 个项目")
            compose.onNodeWithText("工作项目与文件").performClick()
            compose.runOnIdle { assertEquals(index + 1, opened) }
        }
    }

    private fun assertTextFits(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayouts ->
                assertTrue(getLayouts(layouts))
            }
        assertTrue(
            "Text overflow for: $text, layouts: ${layouts.map { "size=${it.size} lines=${it.lineCount} overflowWidth=${it.didOverflowWidth} overflowHeight=${it.didOverflowHeight}" }}",
            layouts.all { !it.hasVisualOverflow },
        )
    }
}
