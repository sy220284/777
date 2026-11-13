package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog

/**
 * E04 / D05: one immutable snapshot of the latest effective USER instruction epoch.
 * A Team message, tool result or auto-continue cannot silently create a new grant.
 * The source of truth is still the existing Session EventLog; no separate store.
 */
internal data class LocalAgentRootDirectiveSnapshot(
    val sessionId: String,
    val userRevision: Long,
) {
    init {
        require(sessionId.isNotBlank()) { "AGENT_ROOT_SESSION_REQUIRED" }
        require(userRevision >= -1L) { "AGENT_ROOT_REVISION_INVALID" }
    }

    fun requireCurrent(sessionId: String, log: LocalSessionEventLog) {
        require(this.sessionId == sessionId) { "AGENT_ROOT_SESSION_MISMATCH" }
        if (workflowUserRevision(log) != userRevision) throw LocalWorkflowSuperseded()
    }
}

internal fun captureLocalAgentRootDirective(
    sessionId: String, log: LocalSessionEventLog,
): LocalAgentRootDirectiveSnapshot =
    LocalAgentRootDirectiveSnapshot(sessionId, workflowUserRevision(log))
