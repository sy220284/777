package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.ToolResultRetention
import com.labteto.dshmobile.local.model.EPHEMERAL_TOOL_RESULT_PLACEHOLDER
import com.labteto.dshmobile.local.model.LOCAL_TOOL_RESULT_RETENTION_KEY
import com.labteto.dshmobile.local.model.durableModelHistorySnapshot
import com.labteto.dshmobile.local.model.localToolHistoryMessage
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class LocalEphemeralToolResultsTest {
    @Test
    fun durableSnapshotReplacesEphemeralPayloadButPreservesToolIdentity() {
        val payload = "data:image/png;base64," + "A".repeat(20_000)
        val message = localToolHistoryMessage(
            callId = "shot-1",
            content = payload,
            retention = ToolResultRetention.EPHEMERAL,
        )

        val durable = durableModelHistorySnapshot(listOf(message)).single()

        assertEquals("tool", durable["role"]?.jsonPrimitive?.content)
        assertEquals("shot-1", durable["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals(EPHEMERAL_TOOL_RESULT_PLACEHOLDER, durable["content"]?.jsonPrimitive?.content)
        assertFalse(durable.toString().contains("base64"))
        assertFalse(durable.containsKey(LOCAL_TOOL_RESULT_RETENTION_KEY))
    }

    @Test
    fun durableHistoryIsReturnedWithoutCopyWhenNoEphemeralResultExists() {
        val messages = listOf(
            localToolHistoryMessage("read-1", "普通结果", ToolResultRetention.DURABLE),
        )

        assertSame(messages, durableModelHistorySnapshot(messages))
    }
}
