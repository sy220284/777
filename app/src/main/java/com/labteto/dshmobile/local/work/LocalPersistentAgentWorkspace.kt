package com.labteto.dshmobile.local.work

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Missing is legacy shared execution; a present invalid identity must never become shared. */
internal fun decodePersistentAgentWorkspaceId(payload: JsonObject): String? {
    if ("workspace_id" !in payload) return null
    val id = (payload["workspace_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    require(id != null && Regex("[0-9a-f]{32}").matches(id)) { "PERSISTENT_AGENT_WORKSPACE_INVALID" }
    return id
}

internal fun decodeAgentWorkspaceRepository(payload: JsonObject, key: String = "workspace_repository"): String? {
    if (key !in payload) return null
    return (payload[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
        ?: error("AGENT_WORKSPACE_REPOSITORY_INVALID")
}
