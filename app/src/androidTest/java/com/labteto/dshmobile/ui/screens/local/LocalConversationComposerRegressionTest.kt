package com.labteto.dshmobile.ui.screens.local


import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.ui.shenyan.SY_COMPOSER_FIELD_TAG
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocalConversationComposerRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun plusButtonOpensAttachmentPickerDirectly() {
        var attachmentPickerOpenCount = 0
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false,
                        configured = true,
                        sessionId = "attachment-direct",
                        usageMode = LocalUsageMode.CHAT,
                    ),
                    activeModelProfile = null,
                    input = "",
                    attachments = emptyList(),
                    onInputChange = {},
                    onRemoveAttachment = {},
                    onClearAttachments = {},
                    onOpenAttachmentPicker = { attachmentPickerOpenCount++ },
                    onShowReplySuggestions = {},
                    onGenerateReplySuggestions = { false },
                    onSend = { _, _ -> LocalSendResult.Empty },
                    onStop = {},
                    onPlanModeChange = {},
                )
            }
        }

        compose.onNodeWithContentDescription(
            context.getString(R.string.local_composer_more_actions),
        ).performClick()

        compose.runOnIdle {
            assertEquals(1, attachmentPickerOpenCount)
        }
    }


    @Test
    fun focusedPlusOpensFullPickerAndPreservesDraft() {
        var openCount = 0
        val draft = "未发送的草稿"
        val focusChanges = mutableListOf<Boolean>()
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false,
                        configured = true,
                        sessionId = "attachment-focused",
                        usageMode = LocalUsageMode.CHAT,
                    ),
                    activeModelProfile = null,
                    input = draft,
                    attachments = emptyList(),
                    onInputChange = {},
                    onRemoveAttachment = {},
                    onClearAttachments = {},
                    onOpenAttachmentPicker = { openCount++ },
                    onShowReplySuggestions = {},
                    onGenerateReplySuggestions = { false },
                    onSend = { _, _ -> LocalSendResult.Empty },
                    onStop = {},
                    onPlanModeChange = {},
                    onFocusChanged = { focusChanges += it },
                )
            }
        }

        compose.onNodeWithTag(SY_COMPOSER_FIELD_TAG).performClick().assertIsFocused()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(
            context.getString(R.string.local_composer_more_actions),
        ).performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(1, openCount)
            assertTrue(focusChanges.contains(true))
            assertEquals(false, focusChanges.last())
        }
        compose.onNodeWithTag(SY_COMPOSER_FIELD_TAG).assertTextContains(draft)
        compose.onNodeWithTag(SY_COMPOSER_FIELD_TAG).assertIsNotFocused()
    }

    @Test
    fun workModeFocusedPlusOpensSamePicker() {
        var openCount = 0
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false,
                        configured = true,
                        sessionId = "work-attachment-focused",
                        usageMode = LocalUsageMode.WORK,
                    ),
                    activeModelProfile = null,
                    input = "",
                    attachments = emptyList(),
                    onInputChange = {},
                    onRemoveAttachment = {},
                    onClearAttachments = {},
                    onOpenAttachmentPicker = { openCount++ },
                    onShowReplySuggestions = {},
                    onGenerateReplySuggestions = { false },
                    onSend = { _, _ -> LocalSendResult.Empty },
                    onStop = {},
                    onPlanModeChange = {},
                )
            }
        }

        compose.onNodeWithTag(SY_COMPOSER_FIELD_TAG).performClick().assertIsFocused()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(
            context.getString(R.string.local_composer_more_actions),
        ).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, openCount) }
    }

    @Test
    fun focusChangeIsForwardedToConversationSurface() {
        var focused = false
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false,
                        configured = true,
                        sessionId = "focus-forward",
                        usageMode = LocalUsageMode.CHAT,
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
                    onStop = {},
                    onPlanModeChange = {},
                    onFocusChanged = { focused = it },
                )
            }
        }

        compose.onNodeWithTag(SY_COMPOSER_FIELD_TAG).performClick().assertIsFocused()
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(focused)
        }
    }
    @Test
    fun unconfiguredChatSendKeepsDraftAndDoesNotNavigate() {
        verifyUnconfiguredSend(LocalUsageMode.CHAT)
    }

    @Test
    fun unconfiguredWorkSendKeepsDraftAndDoesNotNavigate() {
        verifyUnconfiguredSend(LocalUsageMode.WORK)
    }

    private fun verifyUnconfiguredSend(mode: LocalUsageMode) {
        val sent = mutableListOf<String>()
        val changedDrafts = mutableListOf<String>()
        var attachmentsCleared = 0
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false,
                        configured = false,
                        sessionId = "unconfigured-${mode.name}",
                        usageMode = mode,
                    ),
                    activeModelProfile = null,
                    input = "来了",
                    attachments = emptyList(),
                    onInputChange = { changedDrafts += it },
                    onRemoveAttachment = {},
                    onClearAttachments = { attachmentsCleared++ },
                    onOpenAttachmentPicker = {},
                    onShowReplySuggestions = {},
                    onGenerateReplySuggestions = { false },
                    onSend = { text, _ ->
                        sent += text
                        LocalSendResult.rejected(LocalSendRejectReason.UNCONFIGURED)
                    },
                    onStop = {},
                    onPlanModeChange = {},
                )
            }
        }

        compose.onNodeWithContentDescription(
            context.getString(R.string.chat_composer_send),
        ).performClick()
        compose.runOnIdle {
            assertEquals(listOf("来了"), sent)
            assertTrue(changedDrafts.isEmpty())
            assertEquals(0, attachmentsCleared)
        }
        compose.onNodeWithTag(SY_COMPOSER_FIELD_TAG).assertTextContains("来了")
    }

    @Test
    fun unconfiguredSendErrorOffersExplicitModelConnectionAction() {
        var connectCount = 0
        compose.setContent {
            DshTheme {
                LocalConversationErrorCard(
                    sendRejectMessage = context.getString(R.string.local_send_rejected_unconfigured),
                    stateError = null,
                    restoreRequest = null,
                    onRestoreRequest = {},
                    onSwitchModelSource = { connectCount++ },
                    showConnectAction = true,
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.local_welcome_connect_action)).performClick()
        compose.runOnIdle { assertEquals(1, connectCount) }
    }


    @Test
    fun chatGptPlanComposerDoesNotShowManageUsageButton() {
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false,
                        configured = true,
                        sessionId = "chatgpt-plan-composer",
                        usageMode = LocalUsageMode.CHAT,
                    ),
                    activeModelProfile = LocalModelProfile(
                        id = "plan",
                        model = "gpt-test",
                        baseUrl = "https://api.openai.com/v1",
                        authKind = LocalModelAuthKind.CHATGPT_PLAN,
                    ),
                    input = "",
                    attachments = emptyList(),
                    onInputChange = {},
                    onRemoveAttachment = {},
                    onClearAttachments = {},
                    onOpenAttachmentPicker = {},
                    onShowReplySuggestions = {},
                    onGenerateReplySuggestions = { false },
                    onSend = { _, _ -> LocalSendResult.Empty },
                    onStop = {},
                    onPlanModeChange = {},
                )
            }
        }
        compose.onNodeWithText("管理使用额度").assertDoesNotExist()
    }

    @Test
    fun chatGptPlanUsageErrorOffersModelSwitchWithoutManageUsage() {
        var switchCount = 0
        compose.setContent {
            DshTheme {
                LocalConversationErrorCard(
                    sendRejectMessage = null,
                    stateError = "CHATGPT_PLAN_LIMIT_REACHED",
                    restoreRequest = null,
                    onRestoreRequest = {},
                    onSwitchModelSource = { switchCount++ },
                )
            }
        }
        compose.onNodeWithText("管理使用额度").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chatgpt_switch_model_source)).performClick()
        compose.runOnIdle { assertEquals(1, switchCount) }
    }

    @Test
    fun focusRevealsReasoningActionAndPopupDoesNotReplaceDraft() {
        val draft = "待发送内容"
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false, configured = true,
                        sessionId = "reasoning-focus",
                        usageMode = LocalUsageMode.CHAT,
                    ),
                    activeModelProfile = null,
                    input = draft,
                    attachments = emptyList(),
                    onInputChange = {},
                    onRemoveAttachment = {},
                    onClearAttachments = {},
                    onOpenAttachmentPicker = {},
                    onShowReplySuggestions = {},
                    onGenerateReplySuggestions = { false },
                    onSend = { _, _ -> LocalSendResult.Empty },
                    onStop = {},
                    onPlanModeChange = {},
                )
            }
        }
        compose.onNodeWithTag(SY_COMPOSER_FIELD_TAG).performClick().assertIsFocused()
        compose.onNodeWithContentDescription(
            context.getString(
                R.string.local_composer_reasoning_action,
                context.getString(R.string.local_composer_reasoning_default),
            ),
        ).performClick()
        compose.onNodeWithText(
            context.getString(R.string.local_composer_reasoning_unsupported),
        ).assertIsDisplayed()
        compose.onNodeWithTag(SY_COMPOSER_FIELD_TAG).assertTextContains(draft)
    }


    @Test
    fun workComposerSwitchesOnePrimaryButtonAndPreservesQueuedDraft() {
        val running = mutableStateOf(false)
        var stopped = 0
        val submissions = mutableListOf<String>()
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false,
                        configured = true,
                        sessionId = "single-work-action",
                        usageMode = LocalUsageMode.WORK,
                        running = running.value,
                    ),
                    activeModelProfile = null,
                    input = "下一条工作指令",
                    attachments = emptyList(),
                    onInputChange = {},
                    onRemoveAttachment = {},
                    onClearAttachments = {},
                    onOpenAttachmentPicker = {},
                    onShowReplySuggestions = {},
                    onGenerateReplySuggestions = { false },
                    onSend = { text, _ ->
                        submissions += text
                        LocalSendResult.Empty
                    },
                    onStop = { stopped++ },
                    onPlanModeChange = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_send)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_stop)).assertDoesNotExist()

        compose.runOnIdle { running.value = true }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_send)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_stop)).assertExists()
        compose.onNodeWithTag("local-composer-plan-toggle").assertIsNotEnabled()
        compose.onNodeWithContentDescription(
            context.getString(
                R.string.local_composer_reasoning_action,
                context.getString(R.string.local_composer_reasoning_default),
            ),
        ).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.local_work_submit_queue)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_stop)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("下一条工作指令"), submissions)
            assertEquals(1, stopped)
        }
    }

    @Test
    fun runningChatCompactComposerOnlyShowsStop() {
        var stopped = 0
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false,
                        configured = true,
                        sessionId = "single-chat-action",
                        usageMode = LocalUsageMode.CHAT,
                        running = true,
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
                    onStop = { stopped++ },
                    onPlanModeChange = {},
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_send)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_composer_stop)).performClick()
        compose.runOnIdle { assertEquals(1, stopped) }
    }


    @Test
    fun chatComposerRestoresSendAfterStopWithoutExtraAction() {
        val running = mutableStateOf(false)
        var sends = 0
        var stops = 0
        val send = context.getString(R.string.chat_composer_send)
        val stop = context.getString(R.string.chat_composer_stop)
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false,
                        configured = true,
                        sessionId = "chat-action-full-cycle",
                        usageMode = LocalUsageMode.CHAT,
                        running = running.value,
                    ),
                    activeModelProfile = null,
                    input = "保留草稿",
                    attachments = emptyList(),
                    onInputChange = {},
                    onRemoveAttachment = {},
                    onClearAttachments = {},
                    onOpenAttachmentPicker = {},
                    onShowReplySuggestions = {},
                    onGenerateReplySuggestions = { false },
                    onSend = { _, _ -> sends++; LocalSendResult.Empty },
                    onStop = { stops++ },
                    onPlanModeChange = {},
                )
            }
        }

        compose.onAllNodesWithContentDescription(send).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(stop).assertCountEquals(0)
        compose.runOnIdle { running.value = true }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(send).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(stop).assertCountEquals(1)
        compose.onNodeWithContentDescription(stop).performClick()
        compose.runOnIdle {
            assertEquals(1, stops)
            assertEquals(0, sends)
            running.value = false
        }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(send).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(stop).assertCountEquals(0)
        compose.onNodeWithTag(SY_COMPOSER_FIELD_TAG).assertTextContains("保留草稿")
    }

}
