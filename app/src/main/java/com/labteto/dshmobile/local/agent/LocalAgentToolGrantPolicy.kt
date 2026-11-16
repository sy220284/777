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
    // A role allowlist restricts actual child calls. It does not activate tools
    // or enlarge the set of Lead-approved optional grants.
    return (recoveredOptionalTools + capabilities.initialOptionalTools).also { admitted ->
        require(!capabilities.teamManaged || admitted.all { it in capabilities.initialOptionalTools }) {
            "AGENT_TEAM_GRANT_OUTSIDE_LEAD_ADMISSION"
        }
    }
}

/** Validate explicit Lead grants without comparing to the fixed model gateway schema. */
internal fun requireTeamExtensionMatchesRole(
    grantedExtensions: Set<String>,
    roleAllowlist: Set<String>?,
) {
    val denied = roleAllowlist?.let { grantedExtensions - it }.orEmpty()
    require(denied.isEmpty()) {
        "TEAM_EXTENSION_NOT_ALLOWED_BY_ROLE：成员角色未授权这些扩展：" +
            denied.sorted().joinToString("、")
    }
}
