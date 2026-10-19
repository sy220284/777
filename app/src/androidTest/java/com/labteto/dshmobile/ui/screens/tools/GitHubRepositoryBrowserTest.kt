package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.labteto.dshmobile.uicontract.UiGitHubEntry
import com.labteto.dshmobile.uicontract.UiGitHubRepo
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The V6 browser must expose real navigation actions without adding write shortcuts. */
class GitHubRepositoryBrowserTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingRepoOpensExactlyThatRepository() {
        var opened = ""
        compose.setContent {
            DshTheme {
                GitHubRepositoryBrowserSheet(
                    state = GitHubBrowseState(
                        repositories = listOf(UiGitHubRepo("owner/demo", "演示项目", false, "main")),
                    ),
                    onBack = {},
                    onOpenRepository = { opened = it },
                    onOpenFolder = {},
                    onRefresh = {},
                    onOpenFile = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("owner/demo", substring = true).performClick()
        compose.runOnIdle { assertEquals("owner/demo", opened) }
    }

    @Test fun selectingFileOpensRepositoryBranchAndEncodedPath() {
        var opened = ""
        compose.setContent {
            DshTheme {
                GitHubRepositoryBrowserSheet(
                    state = GitHubBrowseState(
                        repositories = listOf(UiGitHubRepo("owner/demo", "演示", false, "main")),
                        selectedRepository = "owner/demo",
                        entries = listOf(UiGitHubEntry("a b.kt", "src/a b.kt", false, 12)),
                    ),
                    onBack = {}, onOpenRepository = {}, onOpenFolder = {},
                    onRefresh = {}, onOpenFile = { opened = it }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("文件 · a b.kt").performClick()
        compose.runOnIdle { assertEquals("https://github.com/owner/demo/blob/main/src/a%20b.kt", opened) }
    }

    @Test fun refreshIsAlwaysAvailableForRetry() {
        var count = 0
        compose.setContent {
            DshTheme {
                GitHubRepositoryBrowserSheet(
                    state = GitHubBrowseState(error = "网络暂时不可用"),
                    onBack = {}, onOpenRepository = {}, onOpenFolder = {},
                    onRefresh = { count++ }, onOpenFile = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("刷新仓库").performClick()
        compose.runOnIdle { assertEquals(1, count) }
    }

    @Test fun openingDirectoryAndReturningUseNavigationCallbacks() {
        var folder = ""
        var backs = 0
        compose.setContent {
            DshTheme {
                GitHubRepositoryBrowserSheet(
                    state = GitHubBrowseState(
                        selectedRepository = "owner/demo",
                        entries = listOf(UiGitHubEntry("src", "src", true, 0)),
                    ),
                    onBack = { backs++ },
                    onOpenRepository = {},
                    onOpenFolder = { folder = it },
                    onRefresh = {},
                    onOpenFile = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("目录 · src").performClick()
        compose.onNodeWithText("返回仓库列表").performClick()
        compose.runOnIdle {
            assertEquals("src", folder)
            assertEquals(1, backs)
        }
    }
}
