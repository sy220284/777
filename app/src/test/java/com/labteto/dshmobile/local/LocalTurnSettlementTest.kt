package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolCall
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTurnSettlementTest {
    @Test
    fun pendingSettlementDistinguishesStartedFromNotStartedCalls() {
        val calls = listOf(
            AgentToolCall("call-a", "write", buildJsonObject { }),
            AgentToolCall("call-b", "bash", buildJsonObject { }),
            AgentToolCall("call-c", "read", buildJsonObject { }),
        )

        val pending = pendingToolSettlements(
            calls = calls,
            startedCallIds = setOf("call-a", "call-b"),
            completedCallIds = setOf("call-a"),
        )

        assertEquals(listOf("call-b", "call-c"), pending.map { it.call.id })
        assertTrue(pending.first().started)
        assertFalse(pending.last().started)
    }

    @Test
    fun completedCallsNeedNoSettlement() {
        val call = AgentToolCall("call-a", "read", buildJsonObject { })

        assertTrue(
            pendingToolSettlements(
                calls = listOf(call),
                startedCallIds = setOf(call.id),
                completedCallIds = setOf(call.id),
            ).isEmpty(),
        )
    }
}
