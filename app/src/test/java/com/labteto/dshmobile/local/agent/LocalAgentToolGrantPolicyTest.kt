package com.labteto.dshmobile.local.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentToolGrantPolicyTest {
    @Test fun teamRecoveryRejectsUnapprovedMcpOrPluginExpansion() {
        val caps = LocalSubagentCapabilities(
            continuable = true, teamManaged = true,
            initialOptionalTools = setOf("skill", "web_fetch", "github_status"),
        )
        assertEquals(
            caps.initialOptionalTools,
            admitLocalAgentScopedToolGrants(caps, setOf("skill")),
        )
        assertTrue(runCatching {
            admitLocalAgentScopedToolGrants(caps, setOf("skill", "mcp_admin"))
        }.exceptionOrNull()?.message.orEmpty().contains("AGENT_TEAM_GRANT_RECOVERY_ESCALATION"))
    }

    @Test fun explicitSubagentAllowlistRestrictsRecoveredDiscoveredTools() {
        val caps = LocalSubagentCapabilities(
            toolAllowlist = setOf("read", "web_search"),
        )
        assertEquals(setOf("read"),
            admitLocalAgentScopedToolGrants(caps, setOf("read")))
        assertTrue(runCatching {
            admitLocalAgentScopedToolGrants(caps, setOf("mcp_unexpected"))
        }.exceptionOrNull()?.message.orEmpty().contains("AGENT_TOOL_GRANT_RECOVERY_ESCALATION"))
    }


    @Test fun roleAllowlistDoesNotInflateOptionalGrantsForTeamMembers() {
        val caps = LocalSubagentCapabilities(
            continuable = true,
            teamManaged = true,
            toolAllowlist = setOf("read", "github_status"),
            initialOptionalTools = setOf("github_status"),
        )
        assertEquals(
            setOf("github_status"),
            admitLocalAgentScopedToolGrants(caps, emptySet()),
        )
        requireTeamExtensionMatchesRole(setOf("github_status"), caps.toolAllowlist)
        assertTrue(runCatching {
            requireTeamExtensionMatchesRole(setOf("unapproved_extension"), caps.toolAllowlist)
        }.exceptionOrNull()?.message.orEmpty().contains("TEAM_EXTENSION_NOT_ALLOWED_BY_ROLE"))
        requireTeamExtensionMatchesRole(setOf("mcp_new"), null)
    }

    @Test fun unconstrainedStandaloneAgentRetainsItsOwnRecoveredCapabilities() {
        val caps = LocalSubagentCapabilities(continuable = true)
        assertEquals(setOf("skill", "web_fetch"),
            admitLocalAgentScopedToolGrants(caps, setOf("skill", "web_fetch")))
        assertTrue(runCatching {
            admitLocalAgentScopedToolGrants(caps, setOf(""))
        }.exceptionOrNull()?.message.orEmpty().contains("AGENT_TOOL_GRANT_INVALID"))
    }
}
