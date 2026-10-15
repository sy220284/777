package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AutomationTokenUsageContextTest {
    @Test fun automationRequestsInheritStableRunIdentity() {
        val context = buildAutomationTokenUsageContext(
            snapshot = LocalHarnessState(sessionId = "chat-123"),
            action = TokenUsageAction.AUTOMATION_CHAT,
            taskLabel = "提醒与角色互动",
            runId = "automation-chat:chat-123:1000",
        )
        assertEquals("chat-123", context.sessionId)
        assertEquals("automation-chat:chat-123:1000", context.runId)
        assertEquals(context.runId, context.turnId)
        assertEquals(context.runId, context.taskRunId)
        assertEquals("automation", context.runKind)
    }

    @Test fun automationCannotWriteUnattributedUsage() {
        assertThrows(IllegalArgumentException::class.java) {
            buildAutomationTokenUsageContext(
                snapshot = LocalHarnessState(),
                action = TokenUsageAction.AUTOMATION_CHAT,
                taskLabel = "自动互动",
                runId = " ",
            )
        }
    }
