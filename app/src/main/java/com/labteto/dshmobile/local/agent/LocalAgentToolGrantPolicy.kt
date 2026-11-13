package com.labteto.dshmobile.local.agent

/**
 * E09: a child's optional tool namespace has a fixed admission envelope.
 * - A Team member inherits only the explicit Lead-approved optional set.
 * - An explicit allowlist constrains BOTH persisted discoveries and new activation.
 * - Ordinary delegates without an allowlist retain existing scoped discovery.
 *
 * Job checkpoint replay never upgrades a child by replaying stale MCP discoveries.
 */
internal fun admitLocalAgentScopedToolGrants(
    capabilities: LocalSubagentCapabilities,
    recoveredOptionalTools: Set<String>,
): Set<String> {
    require(recoveredOptionalTools.none(String::isBlank)) { "AGENT_TOOL_GRANT_INVALID" }
    val bounded = capabilities.toolAllowlist
    if (bounded != null) {
        require(recoveredOptionalTools.all { it in bounded }) {
            "AGENT_TOOL_GRANT_RECOVERY_ESCALATION"
        }
    }
    if (capabilities.teamManaged) {
        require(recoveredOptionalTools.all { it in capabilities.initialOptionalTools }) {
            "AGENT_TEAM_GRANT_RECOVERY_ESCALATION"
        }
    }
    return (recoveredOptionalTools + capabilities.toolAllowlist.orEmpty() +
        capabilities.initialOptionalTools).also { admitted ->
        require(!capabilities.teamManaged || admitted.all { it in capabilities.initialOptionalTools }) {
            "AGENT_TEAM_GRANT_OUTSIDE_LEAD_ADMISSION"
        }
    }
}
