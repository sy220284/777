package com.labteto.dshmobile.local.work

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

/** Compare actual child inputs with the graph's admitted versions, before inference or integration. */
internal fun requireWorkflowWorkspaceInputs(
    scope: com.labteto.dshmobile.local.files.LocalWorkspaceExecutionContext, fence: JsonObject,
) {
    val paths = fence["input_paths"] as? JsonArray ?: error("独立节点缺少输入路径清单")
    val expected = fence["files"] as? JsonObject ?: error("独立节点缺少输入版本")
    paths.forEach { value ->
        val path = value.jsonPrimitive.content
        val actual = localWorkFileSha256(scope.root, scope.localPath(path))
        require(actual != null && actual == expected[path]?.jsonPrimitive?.contentOrNull) {
            "独立节点输入与准入版本不符：$path"
        }
    }
}
