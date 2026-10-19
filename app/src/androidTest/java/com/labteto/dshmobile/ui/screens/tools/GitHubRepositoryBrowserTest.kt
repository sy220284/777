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
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("owner/demo", substring = true).performClick()
        compose.runOnIdle { assertEquals("owner/demo", opened) }
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
