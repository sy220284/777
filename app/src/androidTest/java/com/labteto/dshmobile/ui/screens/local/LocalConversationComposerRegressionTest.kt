package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertEquals
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
            context.getString(R.string.chat_composer_add_attachment),
        ).performClick()

        compose.runOnIdle {
            assertEquals(1, attachmentPickerOpenCount)
        }
    }
}
