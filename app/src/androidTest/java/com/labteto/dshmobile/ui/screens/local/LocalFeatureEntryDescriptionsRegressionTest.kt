package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.shenyan.SyContextActionMenu
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.shenyan.designsystem.components.SyQuickActionTile
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class LocalFeatureEntryDescriptionsRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun chatNewSessionShowsOnlyTitlesAndKeepsSelection() {
        var selected: LocalConversationMode? = null
        compose.setContent {
            DshTheme {
                NewSessionModeDialog(
                    usageMode = LocalUsageMode.CHAT,
                    onDismiss = {},
                    onSelect = { selected = it },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_new_session_chat_continue_hint)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_new_session_chat_independent_hint)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_new_session_chat_continue)).performClick()
        compose.runOnIdle { assertEquals(LocalConversationMode.CONTINUATION, selected) }
        compose.onNodeWithText(context.getString(R.string.local_new_session_chat_new)).performClick()
        compose.runOnIdle { assertEquals(LocalConversationMode.INDEPENDENT, selected) }
    }

    @Test
    fun workNewSessionHidesAllExplanationsAndKeepsOptions() {
        var selected: LocalConversationMode? = null
        compose.setContent {
            DshTheme {
                NewSessionModeDialog(
                    usageMode = LocalUsageMode.WORK,
                    onDismiss = {},
                    onSelect = { selected = it },
                )
            }
        }
        listOf(
            R.string.local_new_session_dialog_intro,
            R.string.local_new_session_continue_hint,
            R.string.local_new_session_independent_hint,
        ).forEach { resource ->
            compose.onNodeWithText(context.getString(resource)).assertDoesNotExist()
        }
        compose.onNodeWithText(context.getString(R.string.local_new_session_continue)).assertExists()
        compose.onNodeWithText("用当前项目开始新工作").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_new_session_independent)).performClick()
        compose.runOnIdle { assertEquals(LocalConversationMode.INDEPENDENT, selected) }
    }

    @Test
    fun groupNewSessionHasNoExplanatoryTextAndKeepsActions() {
        var selected = ""
        compose.setContent {
            DshTheme {
                GroupNewSessionDialog(
                    onDismiss = {},
                    onNewGroup = { selected = "group" },
                    onNewSingle = { selected = "single" },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_group_new_session_intro)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_group_new_group)).performClick()
        compose.runOnIdle { assertEquals("group", selected) }
        compose.onNodeWithText(context.getString(R.string.local_group_new_single)).performClick()
        compose.runOnIdle { assertEquals("single", selected) }
    }

    @Test
    fun agentTeamLaunchEntryHasNoSubtitleAndStillResponds() {
        var clicks = 0
        compose.setContent {
            DshTheme {
                LocalAgentSwarmLaunchEntry(
                    selected = false,
                    onClick = { clicks++ },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_team_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.local_team_high_usage_tip)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_team_title)).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
    @Test
    fun selectedTeamComposerHasNoRepeatedTextAndCanBeCleared() {
        var clearCount = 0
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false,
                        configured = true,
                        sessionId = "selected-team-compact",
                        usageMode = LocalUsageMode.WORK,
                    ),
                    activeModelProfile = null,
                    input = "",
                    attachments = emptyList(),
                    onInputChange = {},
                    onRemoveAttachment = {},
                    onClearAttachments = {},
                    onOpenAttachmentPicker = {},
                    onShowReplySuggestions = {},
                    onGenerateReplySuggestions = { false },
                    onSend = { _, _ -> LocalSendResult.Empty },
                    teamDispatchSelected = true,
                    onClearTeamDispatch = { clearCount++ },
                    onStop = {},
                    onPlanModeChange = {},
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.local_team_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_team_selected_hint)).assertDoesNotExist()
        compose.onNodeWithContentDescription(
            context.getString(R.string.local_team_mode_clear),
        ).performClick()
        compose.runOnIdle { assertEquals(1, clearCount) }
    }

    @Test
    fun quickActionIconsAndLabelsRemainVisibleOnNarrowPhone() {
        var lastAction = ""
        compose.setContent {
            DshTheme {
                Row(Modifier.width(320.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SyQuickActionTile(
                        icon = FeatherIcons.Camera,
                        label = "拍照",
                        modifier = Modifier.weight(1f),
                        onClick = { lastAction = "camera" },
                    )
                    SyQuickActionTile(
                        icon = FeatherIcons.Image,
                        label = "图片",
                        modifier = Modifier.weight(1f),
                        onClick = { lastAction = "image" },
                    )
                    SyQuickActionTile(
                        icon = FeatherIcons.Folder,
                        label = "文件",
                        modifier = Modifier.weight(1f),
                        onClick = { lastAction = "file" },
                    )
                    SyQuickActionTile(
                        icon = FeatherIcons.Sparkles,
                        label = "多模态创作",
                        modifier = Modifier.weight(1f),
                        onClick = { lastAction = "media" },
                    )
            }
        }
        listOf("拍照", "图片", "文件", "多模态创作").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        val positions = listOf("拍照", "图片", "文件", "多模态创作")
            .map { compose.onNodeWithText(it).getUnclippedBoundsInRoot() }
        assertEquals(positions.first().top.value, positions.last().top.value, 3f)
        compose.onNodeWithText("多模态创作").performClick()
        compose.runOnIdle { assertEquals("media", lastAction) }
    }

    @Test
    fun messageMediaActionsHaveThreeDistinctIcons() {
        val message = LocalHarnessMessage(
            id = "media-action-icons", role = "assistant",
            content = "内容", createdAt = 0L,
        )
        var actions = emptyList<SyMenuItem>()
        compose.setContent {
            DshTheme {
                actions = fluidMessageMediaItems(message, onCreate = { _, _ -> })
            }
        }
        compose.runOnIdle {
            assertEquals(3, actions.size)
            assertEquals(3, actions.map { it.icon }.distinct().size)
            actions.forEach { assertNotNull(it.icon) }
        }
    }

    @Test
    fun workMessageContextActionsAllFitAndRespondOnMobile() {
        var clicked = ""
        val labels = listOf("复制内容", "编辑消息", "查看记录", "重新生成", "插入图片", "继续执行")
        compose.setContent {
            DshTheme {
                SyContextActionMenu(
                    expanded = true,
                    onDismiss = {},
                    items = labels.map { label ->
                        SyMenuItem(
                            text = label,
                            icon = FeatherIcons.Copy,
                            onClick = { clicked = label },
                        )
                    },
                )
            }
        }
        labels.forEach { label -> compose.onNodeWithText(label).assertIsDisplayed() }
        compose.onNodeWithText(labels.last()).performClick()
        compose.runOnIdle { assertEquals(labels.last(), clicked) }
    }

}
