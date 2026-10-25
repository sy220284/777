package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LocalAgentParentLink
import com.labteto.dshmobile.local.agent.decodeLocalAgentParentLink
import com.labteto.dshmobile.local.jobs.LocalJobRegistration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Codex-style identity inspection never starts a child or changes its durable state. */
internal enum class LocalAgentRuntimeResidency { ACTIVE_JOB, UNLOADED, TERMINAL }

internal data class LocalAgentResidencyView(
    val registration: LocalJobRegistration,
    val residency: LocalAgentRuntimeResidency,
    val recordedParent: LocalAgentParentLink?,
)

/**
 * One durable Job registration is sufficient to identify a known unloaded Agent.
 * Run checkpoint history can enrich the view but is never allowed to manufacture
 * a live child. The parent relation is read from the original admission payload.
 */
internal fun projectLocalAgentResidency(registration: LocalJobRegistration): LocalAgentResidencyView {
    val snapshot = registration.snapshot
    require(registration.info.id == snapshot.id &&
        registration.info.ownerSessionId == snapshot.ownerId &&
        registration.info.isAgent) { "AGENT_RESIDENCY_IDENTITY_MISMATCH" }
    val residency = when (snapshot.status) {
        "running" -> LocalAgentRuntimeResidency.ACTIVE_JOB
        "interrupted", "dormant", "waiting_slice" -> LocalAgentRuntimeResidency.UNLOADED
        else -> LocalAgentRuntimeResidency.TERMINAL
    }
    val payload = snapshot.resumePayload?.takeIf(String::isNotBlank)
    val parent = if (payload != null && snapshot.resumeKind == "subagent_readonly") {
        val data = Json.parseToJsonElement(payload).jsonObject
        val encoded = data["parent_link"]
        if (encoded == null) null else decodeLocalAgentParentLink(
            encoded as? JsonObject ?: error("AGENT_PARENT_LINK_INVALID"),
        )
    } else null
    require(parent == null || parent.sessionId == snapshot.ownerId) {
        "AGENT_PARENT_SESSION_MISMATCH"
    }
    return LocalAgentResidencyView(registration, residency, parent)
}
