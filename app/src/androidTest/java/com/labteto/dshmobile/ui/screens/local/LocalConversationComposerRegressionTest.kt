package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
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
                    onConfigure = {},
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
                    onConfigure = {},
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
}
