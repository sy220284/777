package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.uicontract.UiMcpServerSnapshot
import com.labteto.dshmobile.uicontract.UiToolConnection
import com.labteto.dshmobile.uicontract.UiToolRegistration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSkillRequirementStatusTest {
    @Test fun registeredServiceCanSatisfyDependencyByServerOrToolName() {
        val servers = listOf(UiMcpServerSnapshot("figma", "http",
            "https://mcp.figma.com/mcp", listOf("get-design", "get-file")))
        assertTrue(isRegisteredMcpSkillRequirement("figma", servers))
        assertTrue(isRegisteredMcpSkillRequirement("get-design", servers))
    }

    @Test fun failedOrConnectingServiceMustNotUnlockSkill() {
        val failed = UiMcpServerSnapshot("figma", "http",
            "https://mcp.figma.com/mcp", listOf("get-design"),
            registration = UiToolRegistration.FAILED,
            connection = UiToolConnection.FAILED)
        val connecting = UiMcpServerSnapshot("figma", "http",
            "https://mcp.figma.com/mcp", listOf("get-design"),
            registration = UiToolRegistration.PENDING,
            connection = UiToolConnection.CONNECTING)
        assertFalse(isRegisteredMcpSkillRequirement("figma", listOf(failed)))
        assertFalse(isRegisteredMcpSkillRequirement("get-design", listOf(connecting)))
    }

    @Test fun noUsableToolsDoesNotClaimConfigured() {
        val empty = UiMcpServerSnapshot("figma", "http",
            "https://mcp.figma.com/mcp", emptyList())
        assertFalse(isRegisteredMcpSkillRequirement("figma", listOf(empty)))
    }
}
