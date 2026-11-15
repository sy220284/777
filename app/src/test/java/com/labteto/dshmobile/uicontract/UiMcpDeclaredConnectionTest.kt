package com.labteto.dshmobile.uicontract

import org.junit.Assert.assertEquals
import org.junit.Test

class UiMcpDeclaredConnectionTest {
    private fun server(
        target: String = "https://mcp.example.com/mcp",
        tools: List<String> = listOf("read"),
        transport: String = "http",
    ) = UiMcpServerSnapshot("design", transport, target, tools)

    @Test fun absentAndReadyAreDifferentFacts() {
        assertEquals(UiMcpDeclaredConnection.ABSENT,
            uiMcpDeclaredConnection(emptyList(), "design", "https://mcp.example.com/mcp"))
        assertEquals(UiMcpDeclaredConnection.TOOLS_REGISTERED,
            uiMcpDeclaredConnection(listOf(server()), "design", "https://mcp.example.com/mcp"))
    }

    @Test fun anEmptyToolRegistryDoesNotMakePluginRunnable() {
        assertEquals(UiMcpDeclaredConnection.REGISTERED_UNREADY,
            uiMcpDeclaredConnection(listOf(server(tools = emptyList())),
                "design", "https://mcp.example.com/mcp"))
        assertEquals(UiMcpDeclaredConnection.REGISTERED_UNREADY,
            uiMcpDeclaredConnection(listOf(server().copy(registration = UiToolRegistration.FAILED)),
                "design", "https://mcp.example.com/mcp"))
    }

    @Test fun collidingServerIdCannotImpersonateAnotherPluginEndpoint() {
        assertEquals(UiMcpDeclaredConnection.TARGET_CONFLICT,
            uiMcpDeclaredConnection(listOf(server("https://unrelated.example/mcp")),
                "design", "https://mcp.example.com/mcp"))
        assertEquals(UiMcpDeclaredConnection.TARGET_CONFLICT,
            uiMcpDeclaredConnection(listOf(server(transport = "stdio")),
                "design", "https://mcp.example.com/mcp"))
    }

    @Test fun displayTargetCannotProveTenantQueryIdentity() {
        assertEquals(UiMcpDeclaredConnection.REGISTERED_UNREADY,
            uiMcpDeclaredConnection(listOf(server()), "design",
                "https://mcp.example.com/mcp?tenant=company-a"))
    }
}
