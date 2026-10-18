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
    @Test fun rejectedOrAbsentIntentDoesNotHijackStartupOrSettingsNavigation() {
        assertNull(activatedConversationScene(null, snapshot))
        assertNull(activatedConversationScene(SessionUiCommand.SelectMode(LocalUsageMode.WORK), snapshot))
        assertEquals("conversation.work", activatedConversationScene(SessionUiCommand.SelectMode(LocalUsageMode.WORK), snapshot.copy(usageMode = LocalUsageMode.WORK)))
    }
}
