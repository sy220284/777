package com.labteto.dshmobile.ui.host

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.labteto.dshmobile.local.LocalConversationUiPortComposition
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.chat.LocalChatExecutionPort
import com.labteto.dshmobile.local.chat.LocalChatExecutionRequest
import com.labteto.dshmobile.local.chat.LocalChatExecutionResult
import com.labteto.dshmobile.local.chat.LocalChatUserEditResult
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.session.LocalUserMessageEditResult
import com.labteto.dshmobile.local.work.LocalWorkApprovalUiPort
import com.labteto.dshmobile.local.work.LocalWorkExecutionPort
import com.labteto.dshmobile.local.work.LocalWorkExecutionRequest
import com.labteto.dshmobile.local.work.LocalWorkExecutionResult
import com.labteto.dshmobile.ui.screens.local.LocalConversationReadingState
import com.labteto.dshmobile.ui.screens.local.LocalSessionDraftCache
import com.labteto.dshmobile.uipack.next.NextConversationUiPack
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * App composition root -> real foreground identity/admission -> Chat/Work Port ->
 * recorded Owner boundaries. No provider/network, tool execution or V6 page fallback.
 * This is NOT the S2 real model/approval/device end-to-end acceptance.
 */
class NextConversationOwnerPortIntegrationTest {
    @get:Rule val compose = createComposeRule()

    private class ChatOwner : LocalChatExecutionPort {
        val sends = mutableListOf<String>()
        override fun send(text: String, attachments: List<LocalImportedAttachment>): LocalSendResult {
            sends += text
            return LocalSendResult.Started
        }
        override fun editAndResendUserMessage(messageId: String, replacement: String): LocalChatUserEditResult =
            error("not part of this test")
        override fun selectMessageVariant(messageId: String, targetIndex: Int): Boolean = false
        override fun regenerateReply(messageId: String): Boolean = false
        override suspend fun execute(request: LocalChatExecutionRequest): LocalChatExecutionResult =
            error("model must not run in the UI boundary test")
        override fun cancel(sessionId: String): Boolean = false
        override suspend fun cancelAndJoin(sessionId: String): Boolean = false
    }

    private class WorkOwner : LocalWorkExecutionPort {
        val sends = mutableListOf<String>()
        override fun send(text: String, attachments: List<LocalImportedAttachment>): LocalSendResult {
            sends += text
            return LocalSendResult.Started
        }
        override fun editAndResendUserMessage(messageId: String, replacement: String): LocalUserMessageEditResult =
            error("not part of this test")
        override fun regenerateReply(messageId: String): Boolean = false
        override suspend fun prepareSession(text: String, preferredSessionId: String?): String =
            error("not part of this test")
        override suspend fun execute(request: LocalWorkExecutionRequest): LocalWorkExecutionResult =
            error("model must not run in the UI boundary test")
        override fun cancel(sessionId: String): Boolean = false
        override suspend fun cancelAndJoin(sessionId: String): Boolean = false
    }

    private val noApproval = object : LocalWorkApprovalUiPort {
        override fun pendingQuestion(): LocalQuestion? = null
        override fun answerQuestion(pendingId: String, answer: String?): Boolean = false
        override fun pendingApproval(): LocalApproval? = null
        override fun answerApproval(pendingId: String, approved: Boolean): Boolean = false
    }

    @Test fun sameNextPackUsesActualOwnerPortsAndNeverReusesOldModeIdentity() {
        val store = LocalRuntimeStateStore()
        val current = store.initialize(LocalHarnessState(
            sessionId = "integration-session",
            usageMode = LocalUsageMode.CHAT,
            loading = false,
            modelState = LocalModelState(configured = true),
        ))
        val chat = ChatOwner()
        val work = WorkOwner()
        val ports = LocalConversationUiPortComposition(store, chat, work, noApproval)
        val workMode = mutableStateOf(false)
        val reading = LocalConversationReadingState()
        val drafts = LocalSessionDraftCache()

        compose.setContent {
            NextConversationUiPack.Render(
                null, null, reading, drafts, ports.chat, ports.work,
                workMode = workMode.value, onClose = {},
            )
        }
        compose.onNode(hasSetTextAction()).performTextInput("real port chat")
        compose.onNodeWithText("发送").performClick()
        compose.runOnIdle {
            assertEquals(listOf("real port chat"), chat.sends)
            assertEquals(emptyList<String>(), work.sends)
            current.value = current.value.copy(usageMode = LocalUsageMode.WORK)
            workMode.value = true
        }
        compose.onNode(hasSetTextAction()).performTextInput("real port work")
        compose.onNodeWithText("发送").performClick()
        compose.runOnIdle {
            assertEquals(listOf("real port chat"), chat.sends)
            assertEquals(listOf("real port work"), work.sends)
        }
    }
}
