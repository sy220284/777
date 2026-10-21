package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Regression: compact drawer entries, shared top bar and message-to-Work handoff. */
class V6SeamlessNavigationInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun drawerShowsOnlyFeatureEntriesWithoutInventoryBadges() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        var opened = ""
        compose.setContent {
            DshTheme {
                DrawerQuickActions(
                    usageMode = LocalUsageMode.CHAT,
                    onOpenGroupChat = { opened = "group" },
                    onOpenPersonaGallery = { opened = "gallery" },
                    onOpenDiary = {},
                    onTasks = {},
                    onWorkspaceFiles = {},
                    onTools = {},
                )
            }
        }
        compose.onNodeWithText(ctx.getString(R.string.persona_gallery_title)).performClick()
        compose.runOnIdle { assertEquals("gallery", opened) }
        compose.onNodeWithText(ctx.getString(R.string.local_group_chat_title)).performClick()
        compose.runOnIdle { assertEquals("group", opened) }
        compose.onNodeWithText("2").assertDoesNotExist()
    }

    @Test fun flatSettingsEntryRemainsClickable() {
        var opens = 0
        compose.setContent {
            DshTheme {
                DrawerQuickAction(
                    icon = com.labteto.dshmobile.ui.components.SyEntryIcons.Settings,
                    title = "设置",
                    onClick = { opens++ },
                )
            }
        }
        compose.onNodeWithText("设置").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun sharedTopBarBackActionRemainsAvailableWithoutABackgroundPanel() {
        var backs = 0
        compose.setContent {
            DshTheme {
                com.shenyan.designsystem.components.SyTopBar(
                    title = "消耗计算",
                    onBack = { backs++ },
                    backContentDescription = "返回",
                    backIcon = com.labteto.dshmobile.ui.components.FeatherIcons.ChevronLeft,
                )
            }
        }
        compose.onNodeWithText("消耗计算").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun selectedUserMessageOpensTheRealWorkHandoffWithExactText() {
        val message = LocalHarnessMessage(
            id = "user-selected-67", role = "user",
            content = "把最近的研究整理成工作计划", createdAt = 1L,
        )
        var selected: LocalHarnessMessage? = null
        compose.setContent {
            DshTheme {
                LocalMessageRow(
                    message = message, chatMode = true, groupMode = false,
                    canEdit = true, canRegenerate = false, canSelectVariant = false,
                    branchInfo = null, onEdit = {}, onSelectVariant = { _, _ -> false },
                    onRegenerate = { false },
                    onConvertToWork = { selected = it },
                )
            }
        }
        compose.onNodeWithText("把最近的研究整理成工作计划", substring = true)
            .performTouchInput { longClick() }
        compose.onNodeWithText("转为任务").performClick()
        compose.runOnIdle {
            assertEquals("user-selected-67", selected?.id)
            assertEquals("把最近的研究整理成工作计划", selected?.content)
            assertEquals("把最近的研究整理成工作计划", message.content)
        }
        compose.onNodeWithText("转为任务").assertDoesNotExist()
    }

    @Test fun workMessageMustNotExposeTheChatOnlyHandoffAction() {
        val message = LocalHarnessMessage(
            id = "work-selected", role = "user", content = "完成构建", createdAt = 1L,
        )
        compose.setContent {
            DshTheme {
                LocalMessageRow(
                    message = message, chatMode = false, groupMode = false,
                    canEdit = true, canRegenerate = false, canSelectVariant = false,
                    branchInfo = null, onEdit = {}, onSelectVariant = { _, _ -> false },
                    onRegenerate = { false }, onConvertToWork = {},
                )
            }
        }
        compose.onNodeWithText("完成构建", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("转为任务").assertDoesNotExist()
    }
    @Test fun chatAssistantDoesNotExposeInteractiveDocumentGeneration() {
        val message = LocalHarnessMessage(
            id = "assistant-chat-1", role = "assistant",
            content = "普通聊天回复", createdAt = 1L,
        )
        var generated = 0
        compose.setContent {
            DshTheme {
                LocalMessageRow(
                    message = message, chatMode = true, groupMode = false,
                    canEdit = false, canRegenerate = false, canSelectVariant = false,
                    branchInfo = null, onEdit = {}, onSelectVariant = { _, _ -> false },
                    onRegenerate = { false },
                    onCreateDocument = { generated++ },
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText("普通聊天回复").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.fluid_message_actions))
            .assertDoesNotExist()
        compose.onNodeWithText("为此回复生成交互文档").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, generated) }
    }

    @Test fun workAssistantStillOffersInteractiveDocumentGeneration() {
        val message = LocalHarnessMessage(
            id = "assistant-doc-1", role = "assistant",
            content = "这是一条等待整理的回复", createdAt = 1L,
        )
        var selectedId: String? = null
        compose.setContent {
            DshTheme {
                LocalMessageRow(
                    message = message, chatMode = false, groupMode = false,
                    canEdit = false, canRegenerate = false, canSelectVariant = false,
                    branchInfo = null, onEdit = {}, onSelectVariant = { _, _ -> false },
                    onRegenerate = { false },
                    onCreateDocument = { selectedId = it.id },
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.fluid_message_actions))
            .performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("为此回复生成交互文档")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("为此回复生成交互文档").performClick()
        compose.runOnIdle { assertEquals("assistant-doc-1", selectedId) }
    }

}
