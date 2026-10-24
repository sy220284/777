package com.labteto.dshmobile.local.agent

import kotlinx.serialization.json.JsonObject

/** Canonical authority-to-capabilities compiler shared by foreground, fork, durable and Team. */
internal enum class LocalAgentDelegationKind {
    FOREGROUND_READONLY, FORK, PERSISTENT, TEAM_MEMBER,
}

/**
 * Roles and historical prompts are not authorization. Existing tool enforcement and the
 * single Session/Job owners remain the source of truth for actual execution rights.
 */
internal fun buildLocalDelegationCapabilities(
    kind: LocalAgentDelegationKind,
    allowMutation: Boolean = false,
    historyMode: LocalSubagentHistoryMode = LocalSubagentHistoryMode.ISOLATED,
    virtualScreen: Boolean = false,
    toolAllowlist: Set<String>? = null,
    outputSchema: JsonObject? = null,
    initialOptionalTools: Set<String> = emptySet(),
): LocalSubagentCapabilities {
    require(kind != LocalAgentDelegationKind.FOREGROUND_READONLY || !allowMutation) {
        "READONLY_DELEGATION_CANNOT_MUTATE"
    }
    require(kind != LocalAgentDelegationKind.FORK || !virtualScreen) {
        "FORK_CANNOT_BORROW_VIRTUAL_SCREEN"
    }
    require(kind !in setOf(LocalAgentDelegationKind.PERSISTENT, LocalAgentDelegationKind.TEAM_MEMBER) ||
        historyMode == LocalSubagentHistoryMode.ISOLATED) {
        "PERSISTENT_DELEGATION_REQUIRES_ISOLATED_HISTORY"
    }
    require(kind == LocalAgentDelegationKind.TEAM_MEMBER || initialOptionalTools.isEmpty()) {
        "ONLY_TEAM_MAY_RECEIVE_INITIAL_OPTIONAL_GRANTS"
    }
    return LocalSubagentCapabilities(
        allowMutation = allowMutation,
        continuable = kind == LocalAgentDelegationKind.PERSISTENT ||
            kind == LocalAgentDelegationKind.TEAM_MEMBER,
        virtualScreen = virtualScreen,
        historyMode = if (kind == LocalAgentDelegationKind.FORK)
            LocalSubagentHistoryMode.INHERIT_PARENT else historyMode,
        maxDepth = 1,
        toolAllowlist = toolAllowlist,
        outputSchema = outputSchema,
        teamManaged = kind == LocalAgentDelegationKind.TEAM_MEMBER,
        initialOptionalTools = initialOptionalTools,
    )
}

internal fun buildLocalDelegatedLaunchSpec(
    kind: LocalAgentDelegationKind,
    task: String,
    instructions: String,
    modelOverride: String?,
    maxSteps: Int,
    parentCallId: String?,
    allowMutation: Boolean = false,
    historyMode: LocalSubagentHistoryMode = LocalSubagentHistoryMode.ISOLATED,
    virtualScreen: Boolean = false,
    toolAllowlist: Set<String>? = null,
    outputSchema: JsonObject? = null,
    parentLink: LocalAgentParentLink? = null,
): LocalSubagentLaunchSpec {
    require(kind == LocalAgentDelegationKind.FOREGROUND_READONLY ||
        kind == LocalAgentDelegationKind.FORK) {
        "DURABLE_AGENTS_MUST_USE_PERSISTENT_JOB_ADMISSION"
    }
    return LocalSubagentLaunchSpec(
        task = task,
        instructions = instructions,
        modelOverride = modelOverride,
        maxSteps = maxSteps,
        parentCallId = parentCallId,
        parentLink = parentLink,
        capabilities = buildLocalDelegationCapabilities(
            kind = kind,
            allowMutation = allowMutation,
            historyMode = historyMode,
            virtualScreen = virtualScreen,
            toolAllowlist = toolAllowlist,
            outputSchema = outputSchema,
        ),
    )
}
