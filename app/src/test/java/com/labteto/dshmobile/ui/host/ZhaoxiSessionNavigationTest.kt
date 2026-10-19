package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.uicontract.*
import org.junit.Assert.*
import org.junit.Test

class ZhaoxiSessionNavigationTest {
    private val identity = UiSessionIdentity("old", "active", 1, null, null)
    private val snapshot = UiSessionsSnapshot(identity, LocalUsageMode.CHAT, false, emptyList(), null)
    @Test fun queuedOpenWaitsForTheRequestedSessionAndLoadingToFinish() {
        val command = SessionUiCommand.Open("target")
        assertNull(activatedConversationScene(command, snapshot))
        val target = snapshot.copy(identity = identity.copy(sessionId = "target"))
        assertNull(activatedConversationScene(command, target.copy(loading = true)))
        assertEquals("conversation.chat", activatedConversationScene(command, target))
    }
    @Test fun createCannotOpenTheOldSessionOrTheWrongMode() {
        val command = SessionUiCommand.Create(identity, LocalConversationMode.INDEPENDENT, LocalUsageMode.WORK)
        assertNull(activatedConversationScene(command, snapshot))
        val target = snapshot.copy(identity = identity.copy(sessionId = "new"))
        assertNull(activatedConversationScene(command, target))
        assertEquals("conversation.work", activatedConversationScene(command, target.copy(usageMode = LocalUsageMode.WORK)))
    }
    @Test fun drawerSelectionDoesNotTriggerConversationNavigation() {
        val selection = SessionUiCommand.SelectMode(LocalUsageMode.WORK, openConversation = false)
        assertNull(activatedConversationScene(selection, snapshot.copy(usageMode = LocalUsageMode.WORK)))
        assertEquals("conversation.work",
            activatedConversationScene(SessionUiCommand.SelectMode(LocalUsageMode.WORK),
                snapshot.copy(usageMode = LocalUsageMode.WORK)))
    }

    @Test fun selectedAttachmentTransferRequiresExactOwnerIssuedWorkDestination() {
        val attachment = com.labteto.dshmobile.local.attachment.LocalImportedAttachment(
            "report.pdf", ".dsh/imported/report.pdf", "application/pdf", 1024L)
        val request = SessionUiCommand.Create(identity, LocalConversationMode.CONTINUATION,
            LocalUsageMode.WORK, "用户确认公开摘要", handoffAttachments = listOf(attachment))
        val newWork = snapshot.copy(
            identity = identity.copy(sessionId = "owner-issued-work"),
            usageMode = LocalUsageMode.WORK)
        assertTrue(matchesAcceptedWorkHandoff(request, newWork, "用户确认公开摘要"))
        assertFalse(matchesAcceptedWorkHandoff(request, snapshot, "用户确认公开摘要"))
        assertFalse(matchesAcceptedWorkHandoff(request, newWork, null))
        assertFalse(matchesAcceptedWorkHandoff(request, newWork, "另一条聊天的摘要"))
        assertFalse(matchesAcceptedWorkHandoff(request, newWork.copy(loading = true), "用户确认公开摘要"))
        assertFalse(matchesAcceptedWorkHandoff(request, newWork.copy(usageMode = LocalUsageMode.CHAT),
            "用户确认公开摘要"))
        assertFalse(matchesAcceptedWorkHandoff(request.copy(handoffAttachments = emptyList()),
            newWork, "用户确认公开摘要"))
    }

    @Test fun rejectedOrAbsentIntentDoesNotHijackStartupOrSettingsNavigation() {
        assertNull(activatedConversationScene(null, snapshot))
        assertNull(activatedConversationScene(SessionUiCommand.SelectMode(LocalUsageMode.WORK), snapshot))
        assertEquals("conversation.work", activatedConversationScene(SessionUiCommand.SelectMode(LocalUsageMode.WORK), snapshot.copy(usageMode = LocalUsageMode.WORK)))
    }
}
