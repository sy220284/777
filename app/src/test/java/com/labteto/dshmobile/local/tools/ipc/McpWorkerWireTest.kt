package com.labteto.dshmobile.local.tools.ipc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fast contract checks for the independently hosted MCP Binder envelope. */
class McpWorkerWireTest {
    @Test fun everyWireMethodAndPayloadHasAHardBudget() {
        assertTrue(McpWorkerWire.isDedicatedProcess(100, 101))
        assertFalse(McpWorkerWire.isDedicatedProcess(100, 100))
        assertFalse(McpWorkerWire.isDedicatedProcess(100, 0))
        assertTrue(McpWorkerWire.isAllowedMethod("tools/call"))
        assertTrue(McpWorkerWire.isAllowedMethod("tools/list"))
        assertFalse(McpWorkerWire.isAllowedMethod(""))
        assertFalse(McpWorkerWire.isAllowedMethod("tools/call\nunauthorized"))
        assertFalse(McpWorkerWire.isAllowedMethod("m".repeat(97)))
        assertTrue(McpWorkerWire.validRequestSize("a".repeat(48 * 1024)))
        assertFalse(McpWorkerWire.validRequestSize("中".repeat(20_000)))
        assertTrue(McpWorkerWire.validResponseSize("{}"))
        assertTrue(McpWorkerWire.validResponseSize("x".repeat(48 * 1024 + 1)))
        assertFalse(McpWorkerWire.validResponseSize("x".repeat(8 * 1024 * 1024 + 1)))
    }

    @Test fun protocolNegotiationFailureUsesSafeCategoryWithoutLeakingDetails() {
        val failure = IllegalArgumentException(
            "MCP stdio 服务端协商到不支持的版本：2024-11-05；请求版本：2026-07-28；secret=do-not-leak")
        assertTrue(mcpWorkerSafeFailureCode(failure) == "MCP_PROTOCOL_NEGOTIATION_FAILED")
        assertTrue(mcpWorkerSafeFailureCode(IllegalArgumentException("unrelated")) == "IllegalArgumentException")
    }

    @Test fun transportHasNoAutomaticCallReplayAndUsesOnlyPrivateWorkerProcess() {
        val type = McpPluginWorkerTransport::class.java
        assertTrue(com.labteto.dshmobile.interop.mcp.McpTransport::class.java.isAssignableFrom(type))
        assertTrue(com.labteto.dshmobile.interop.mcp.McpTerminationSource::class.java.isAssignableFrom(type))
        assertTrue(McpWorkerWire.MAX_PENDING_CALLS in 1..32)
        assertTrue(McpWorkerWire.CONNECT_TIMEOUT_MS < McpWorkerWire.REQUEST_TIMEOUT_MS)
    }
}
