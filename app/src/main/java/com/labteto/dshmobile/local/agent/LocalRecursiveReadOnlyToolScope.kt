package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.harness.agent.AgentToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
        return JsonArray(source.mapNotNull { raw ->
            val schema = raw as? JsonObject ?: return@mapNotNull null
            val function = schema["function"] as? JsonObject ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            if (!permits(name, capabilities)) return@mapNotNull null
            if (name != "web_fetch") return@mapNotNull schema
            // The recursive tree cannot detach fetches into unrelated background jobs.
            val params = function["parameters"] as? JsonObject ?: return@mapNotNull schema
            val properties = params["properties"] as? JsonObject ?: return@mapNotNull schema
            val stripped = JsonObject(properties - "run_in_background")
            val narrowParams = JsonObject(params + ("properties" to stripped))
            val narrowFunction = JsonObject(function + ("parameters" to narrowParams))
            JsonObject(schema + ("function" to narrowFunction))
        })
    }

    fun rejection(call: AgentToolCall, capabilities: LocalSubagentCapabilities): AgentToolResult? {
        rejection(call.name, capabilities)?.let { return it }
        if (capabilities.maxDepth > 1 && call.name == "web_fetch" &&
            "run_in_background" in call.arguments) {
            val value = call.arguments["run_in_background"] as? JsonPrimitive
            if (value?.booleanOrNull != false) {
                return AgentToolResult(
                    content = "NESTED_READONLY_BACKGROUND_BLOCKED：递归代理禁止创建后台网络任务",
                    isError = true,
                    errorCode = "NESTED_READONLY_BACKGROUND_BLOCKED",
                )
            }
        }
        return null
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
