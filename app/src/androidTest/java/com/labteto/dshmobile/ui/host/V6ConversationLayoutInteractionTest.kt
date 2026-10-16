package com.labteto.dshmobile.ui.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.uicontract.*
import com.labteto.dshmobile.uihost.UiSceneNavigationRejection
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.labteto.dshmobile.uipack.v6.V6ConversationPart
import com.labteto.dshmobile.uipack.v6.V6ConversationPorts
import com.labteto.dshmobile.uipack.v6.V6ConversationScene
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class V6ConversationLayoutInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val identity = UiSessionIdentity("work", "active", 1L, null, null)
    private fun ports(withAttachments: Boolean): V6ConversationPorts {
        val chat = object : ChatUiPort {
            override val snapshots = MutableStateFlow(ChatSceneSnapshot(identity, UiSceneStatus.EMPTY,
                UiExecutionPhase.IDLE, emptyList(), 0, UiActionAvailability(true), null))
            override fun snapshot() = snapshots.value
            override fun submit(command: ChatUiCommand) = UiCommandReceipt.Accepted
        }
        val work = object : WorkConversationUiPort {
            override val snapshots = MutableStateFlow(WorkConversationSceneSnapshot(identity, UiSceneStatus.CONTENT,
                UiExecutionPhase.IDLE, listOf(UiMessageSnapshot("m", UiMessageRole.ASSISTANT, "可继续阅读的工作内容", 1L)),
                0, UiActionAvailability(true), null, null))
            override fun snapshot() = snapshots.value
            override fun submit(command: WorkConversationUiCommand) = UiCommandReceipt.Accepted
        }
        val drafts = object : ConversationDraftUiPort {
            override val drafts = MutableStateFlow(mapOf(identity.sessionId to (1..50).joinToString("\n") { "第${it}行草稿" }))
            override val attachments = MutableStateFlow(mapOf(identity.sessionId to if (withAttachments)
                (1..20).map { LocalImportedAttachment("附件${it}.txt", "files/$it.txt", "text/plain", 16L) }
                else emptyList()))
            override fun updateDraft(sessionId: String, text: String) { drafts.value = mapOf(sessionId to text) }
            override fun addAttachment(sessionId: String, attachment: LocalImportedAttachment) = Unit
            override fun removeAttachment(sessionId: String, relativePath: String) {
                attachments.value = mapOf(sessionId to attachments.value[sessionId].orEmpty().filterNot { it.relativePath == relativePath })
            }
            override fun clearAttachments(sessionId: String) { attachments.value = emptyMap() }
        }
        val reading = object : ConversationReadingUiPort {
            override fun anchor(sessionId: String): UiReadingAnchor? = null
            override fun rememberAnchor(sessionId: String, anchor: UiReadingAnchor) = Unit
        }
        return V6ConversationPorts(chat, work, drafts, reading, workMode = { true })
    }
    private fun show(part: V6ConversationPart, withAttachments: Boolean = false) {
        val ports = ports(withAttachments)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().requiredWidth(320.dp)) {
                        V6ConversationScene(ports, workMode = true, onBack = {}, part = part,
                            onNavigate = { UiSceneNavigationResult.Rejected(UiSceneNavigationRejection.PACK_MISSING_SCENE) })
                    }
                }
            }
        }
    }
    @Test fun fiftyLineDraftAndTwentyAttachmentsKeepSendAndReadingVisible() {
        show(V6ConversationPart.FULL, withAttachments = true)
        compose.onNodeWithText("发送").assertIsDisplayed()
        compose.onNodeWithText("可继续阅读的工作内容").assertIsDisplayed()
        compose.onNodeWithText("附件（20）").performClick()
        compose.onNodeWithText("附件20.txt").performScrollTo().assertIsDisplayed()
    }
    @Test fun deniedNavigationShowsFeedbackInsideOpenSheet() {
        show(V6ConversationPart.HEADER)
        compose.onNodeWithText("更多").performClick()
        compose.onNodeWithText("新建会话").performClick()
        compose.onNodeWithText("暂时无法打开“新建会话”，请稍后再试").assertIsDisplayed()
    }
}
