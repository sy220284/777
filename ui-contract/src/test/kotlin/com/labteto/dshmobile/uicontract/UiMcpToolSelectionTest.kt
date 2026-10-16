package com.labteto.dshmobile.uicontract

import org.junit.Assert.assertEquals
import org.junit.Test

class UiMcpToolSelectionTest {
    @Test fun pinnedConnectorSelectionIsExactAndNeverAnAuthorizationGrant() {
        val pins = setOf("mcp-one", "github")
        assertEquals(UiToolSelection.SELECTED, uiMcpToolSelection("mcp-one", pins))
        assertEquals(UiToolSelection.NOT_SELECTED, uiMcpToolSelection("mcp", pins))
        assertEquals(UiToolSelection.NOT_SELECTED, uiMcpToolSelection("other", pins))
        val displayed = UiMcpServerSnapshot(
            id = "mcp-one", transport = "http", target = "private", tools = listOf("mcp-one_tool"),
            selection = uiMcpToolSelection("mcp-one", pins),
        )
        assertEquals(UiToolAuthorization.REQUIRED_PER_CALL, displayed.authorization)
        assertEquals(UiToolConnection.HEALTH_UNCHECKED, displayed.connection)
    }

    @Test fun disconnectedServerMayRemainSelectedButHasNoToolGrant() {
        val state = UiMcpServerSnapshot(
            id = "gone", transport = "", target = "", tools = emptyList(),
            registration = UiToolRegistration.PENDING,
            selection = uiMcpToolSelection("gone", setOf("gone")),
        )
        assertEquals(UiToolSelection.SELECTED, state.selection)
        assertEquals(UiToolRegistration.PENDING, state.registration)
        assertEquals(UiToolAuthorization.REQUIRED_PER_CALL, state.authorization)
    }
}
