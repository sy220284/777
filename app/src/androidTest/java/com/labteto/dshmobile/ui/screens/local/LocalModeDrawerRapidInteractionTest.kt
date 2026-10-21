package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Instrumented surface check: rapidly alternating mode tabs must keep all visible entry actions live. */
class LocalModeDrawerRapidInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidlyAlternateModesAndTapFeatureEntriesWithoutLosingActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var lastMode = LocalUsageMode.CHAT
        var gallery = 0
        var workspace = 0
        var tasks = 0
        compose.setContent {
            DshTheme {
                var mode by remember { mutableStateOf(LocalUsageMode.CHAT) }
                Column {
                    LocalUsageModePill(selected = mode, enabled = true, onSelect = {
                        lastMode = it
                        mode = it
                    })
                    DrawerQuickActions(
                        usageMode = mode,
                        onOpenGroupChat = {},
                        onOpenPersonaGallery = { gallery++ },
                        onOpenDiary = {},
                        onTasks = { tasks++ },
                        onWorkspaceFiles = { workspace++ },
                        onTools = {},
                    )
                }
            }
        }
        repeat(40) {
            compose.onNodeWithText(context.getString(R.string.local_usage_chat)).performClick()
            compose.onNodeWithText(context.getString(R.string.persona_gallery_title)).performClick()
            compose.onNodeWithText(context.getString(R.string.tasks_chat_title)).performClick()

            compose.onNodeWithText(context.getString(R.string.local_usage_work)).performClick()
            compose.onNodeWithText(context.getString(R.string.chatlist_workspace_files)).performClick()
            compose.onNodeWithText(context.getString(R.string.tasks_title)).performClick()
        }
        compose.runOnIdle {
            assertEquals(LocalUsageMode.WORK, lastMode)
            assertEquals(40, gallery)
            assertEquals(40, workspace)
            assertEquals(80, tasks)
        }
    }
}
