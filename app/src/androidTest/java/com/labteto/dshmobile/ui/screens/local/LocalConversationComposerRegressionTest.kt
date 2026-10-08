package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.ui.components.DS_COMPOSER_FIELD_TAG
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
                    onAutoApprove = {},
                    onDisableAutoApprove = {},
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
                    onAutoApprove = {},
                    onDisableAutoApprove = {},
                    onFocusChanged = { focusChanges += it },
                )
            }
        }

        compose.onNodeWithTag(DS_COMPOSER_FIELD_TAG).performClick().assertIsFocused()
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
        compose.onNodeWithTag(DS_COMPOSER_FIELD_TAG).assertTextContains(draft)
        compose.onNodeWithTag(DS_COMPOSER_FIELD_TAG).assertIsNotFocused()
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
                    onAutoApprove = {},
                    onDisableAutoApprove = {},
                )
            }
        }

        compose.onNodeWithTag(DS_COMPOSER_FIELD_TAG).performClick().assertIsFocused()
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
                    onAutoApprove = {},
                    onDisableAutoApprove = {},
                    onFocusChanged = { focused = it },
                )
            }
        }

        compose.onNodeWithTag(DS_COMPOSER_FIELD_TAG).performClick().assertIsFocused()
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
                    onAutoApprove = {},
                    onDisableAutoApprove = {},
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
        compose.onNodeWithTag(DS_COMPOSER_FIELD_TAG).assertTextContains("来了")
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
                    onAutoApprove = {},
                    onDisableAutoApprove = {},
                )
            }
        }
        compose.onNodeWithTag(DS_COMPOSER_FIELD_TAG).performClick().assertIsFocused()
        compose.onNodeWithContentDescription(
            context.getString(
                R.string.local_composer_reasoning_action,
                context.getString(R.string.local_composer_reasoning_default),
            ),
        ).performClick()
        compose.onNodeWithText(
            context.getString(R.string.local_composer_reasoning_unsupported),
        ).assertIsDisplayed()
        compose.onNodeWithTag(DS_COMPOSER_FIELD_TAG).assertTextContains(draft)
    }

    @Test
    fun chatWebActionExplainsToolBoundaryAndOffersWorkHandoff() {
        var workRequests = 0
        compose.setContent {
            DshTheme {
                LocalConversationComposer(
                    state = LocalConversationSurfaceState(
                        loading = false, configured = true,
                        sessionId = "web-work-handoff",
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
                    onRequestWorkWebSearch = { workRequests++ },
                    onPlanModeChange = {},
                    onAutoApprove = {},
                    onDisableAutoApprove = {},
                )
            }
        }
        compose.onNodeWithTag(DS_COMPOSER_FIELD_TAG).performClick()
        compose.onNodeWithContentDescription(
            context.getString(R.string.local_composer_web_work_only),
        ).performClick()
        compose.onNodeWithText(context.getString(R.string.local_composer_web_to_work))
            .performClick()
        compose.runOnIdle { assertEquals(1, workRequests) }
    }

}
