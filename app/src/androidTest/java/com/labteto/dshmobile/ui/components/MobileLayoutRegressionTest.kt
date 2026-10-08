package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
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

    @Test
    fun narrowLargeTypeHeaderKeepsActionsOutsideText() {
        var back = 0
        var refreshed = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                DshTheme {
                    Box(Modifier.width(320.dp)) {
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
        val title = compose.onNodeWithText("个性化与长期记忆").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val subtitle = compose.onNodeWithText("查看和管理聊天中记住的内容").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val backBounds = compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
        val actionBounds = compose.onNodeWithContentDescription("刷新").fetchSemanticsNode().boundsInRoot
        assertTrue(title.left >= backBounds.right && title.right <= actionBounds.left)
        assertTrue(subtitle.top >= backBounds.bottom && subtitle.top >= actionBounds.bottom)
        assertTrue(subtitle.top >= title.bottom)
        assertTextFits("个性化与长期记忆")
        assertTextFits("查看和管理聊天中记住的内容")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("刷新").performClick()
        compose.runOnIdle {
            assertEquals(1, back)
            assertEquals(1, refreshed)
        }
    }

    @Test
    fun narrowLargeTypeCategoryKeepsValueAndNavigationUsable() {
        var opened = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                DshTheme {
                    Box(Modifier.width(320.dp)) {
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
        val title = compose.onNodeWithText("工作项目与文件", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("9999 个项目", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(title.right <= value.left)
        assertTextFits("工作项目与文件")
        assertTextFits("9999 个项目")
        compose.onNodeWithText("工作项目与文件").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    private fun assertTextFits(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayouts ->
                assertTrue(getLayouts(layouts))
            }
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.all { !it.hasVisualOverflow })
    }

}
