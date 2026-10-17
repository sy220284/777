package com.labteto.dshmobile.uipack.v6

import com.labteto.dshmobile.uicontract.*
import org.junit.Assert.*
import org.junit.Test

class V6PresentationTest {
    @Test fun settingsGroupingKeepsEveryExistingDestinationExactlyOnce() {
        val ids = v6SettingsGroups.flatMap { it.entries }.map { it.id }
        assertEquals(16, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(setOf("settings.models", "settings.account", "settings.minimax_quota",
            "settings.model_performance", "settings.pricing", "settings.appearance",
            "settings.notifications", "settings.permissions", "settings.chat", "settings.memory",
            "settings.memory_management", "settings.advanced", "settings.usage",
            "settings.session_storage", "settings.diagnostics", "settings.update"), ids.toSet())
    }

    @Test fun rejectedAndUnknownOutcomesNeverImplySuccessOrExposeEnumNames() {
        UiCommandRejection.entries.forEach { reason ->
            val text = describeV6ConversationReceipt(UiCommandReceipt.Rejected(reason))
            assertFalse(text.contains(reason.name))
            assertFalse(text.contains("已完成"))
        }
        assertTrue(describeV6ConversationReceipt(UiCommandReceipt.OutcomeUnknown).contains("未确认"))
        assertTrue(describeV6ConversationReceipt(UiCommandReceipt.Queued).contains("排队"))
        assertFalse(describeV6ConversationReceipt(UiCommandReceipt.Accepted).contains("执行完成了"))
        assertTrue(v6MessageEditLabel(UiMessageEditOutcome.COMMITTED_NOT_STARTED).contains("尚未启动"))
    }

    @Test fun onlyExplicitWebLinksAndOwnerMediatedPathsCanBeInteractive() {
        assertTrue(isV6ReadableFileLink("results/报告.md"))
        assertTrue(isV6ReadableFileLink("/workspace/results/a.txt"))
        assertEquals("/workspace/results/a b.txt", v6ReadableFilePath("file:///workspace/results/a%20b.txt"))
        assertEquals("results/10:20.md", v6ReadableFilePath("results/10:20.md#part"))
        assertNull(v6ReadableFilePath("file://remotehost/secret"))
        listOf("javascript:alert(1)", "data:text/html,test", "content://private/file",
            "intent:launch", "mailto:a@b.com", "#section", "").forEach {
            assertFalse(it, isV6ReadableFileLink(it))
        }
    }
}
