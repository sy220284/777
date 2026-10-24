package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A model-visible read-only scope must also be enforced at the call boundary.
 * "NETWORK" on a registered tool is not proof that it cannot issue POST/PUT/DELETE.
 * Recursive descendants cannot use shell, generic HTTP, writes, device controls or
 * capability discovery until a narrower audited capability grants them explicitly.
 */
internal object LocalRecursiveReadOnlyToolScope {
    val allowedNames: Set<String> = setOf(
        "read", "tool_output_read", "file_inspect", "list_files", "glob", "grep",
        "web_search", "web_fetch", "subagent",
    )

    fun permits(name: String, capabilities: LocalSubagentCapabilities): Boolean {
        if (capabilities.maxDepth <= 1) return true
        return name in allowedNames && (name != "subagent" ||
            capabilities.depth < capabilities.maxDepth)
    }

    fun visibleSchemas(source: JsonArray, capabilities: LocalSubagentCapabilities): JsonArray {
        if (capabilities.maxDepth <= 1) return source
        return JsonArray(source.filter { raw ->
            val function = (raw as? JsonObject)?.get("function") as? JsonObject
            val name = function?.get("name")?.jsonPrimitive?.contentOrNull
            name != null && permits(name, capabilities)
        })
    }

    fun rejection(name: String, capabilities: LocalSubagentCapabilities): AgentToolResult? {
        if (permits(name, capabilities)) return null
        return AgentToolResult(
            content = "NESTED_READONLY_TOOL_BLOCKED：多级只读代理未获此工具授权：$name",
            isError = true,
            errorCode = "NESTED_READONLY_TOOL_BLOCKED",
            retryable = false,
        )
    }
}
