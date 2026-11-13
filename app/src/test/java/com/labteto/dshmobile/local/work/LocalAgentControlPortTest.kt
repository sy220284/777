package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentControlPortTest {
    private fun request(kind: LocalAgentDispatchKind) = LocalAgentDispatchRequest(
        kind = kind, task = "review", instructions = "", modelOverride = null,
        maxSteps = 20, parentToolCallId = "tool-123",
        historyMode = if (kind == LocalAgentDispatchKind.FORK)
            LocalSubagentHistoryMode.INHERIT_PARENT else LocalSubagentHistoryMode.ISOLATED,
    )

    @Test fun typedCommandDistinguishesThreeExecutionLifecycles() {
        assertEquals(LocalAgentDispatchKind.READONLY_BACKGROUND,
            request(LocalAgentDispatchKind.READONLY_BACKGROUND).kind)
        assertEquals(LocalAgentDispatchKind.FORK, request(LocalAgentDispatchKind.FORK).kind)
        assertEquals(LocalAgentDispatchKind.READONLY_FOREGROUND,
            request(LocalAgentDispatchKind.READONLY_FOREGROUND).kind)
    }

    @Test fun portRejectsWritableReadonlyAndInvalidForkContexts() {
        assertTrue(runCatching {
            request(LocalAgentDispatchKind.READONLY_BACKGROUND).copy(allowMutation = true)
        }.isFailure)
        assertTrue(runCatching {
            request(LocalAgentDispatchKind.FORK).copy(historyMode = LocalSubagentHistoryMode.ISOLATED)
        }.isFailure)
        assertTrue(runCatching {
            request(LocalAgentDispatchKind.READONLY_BACKGROUND)
                .copy(historyMode = LocalSubagentHistoryMode.INHERIT_PARENT)
        }.isFailure)
    }
}
