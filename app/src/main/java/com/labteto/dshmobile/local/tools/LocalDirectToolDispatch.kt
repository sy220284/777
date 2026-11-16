package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.LocalRecursiveReadOnlyToolScope
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.local.model.LocalToolCall
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val DIRECT_TOOL_CALL = "tool_call"
internal const val DIRECT_TOOL_SEARCH = "tool_search"

/** Resolves the single model-facing call to an exact registered tool invocation. */
internal fun LocalToolCall.directToolTarget(): LocalToolCall? {
    if (name != DIRECT_TOOL_CALL) return this
    val target = arguments["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: return null
    val args = arguments["arguments"] as? JsonObject ?: return null
    if (target == DIRECT_TOOL_CALL || target == DIRECT_TOOL_SEARCH) return null
    return copy(name = target, arguments = args, rawArguments = args.toString())
}

/** Model step validation remains on the public gateway, while scope guards see the real child. */
internal fun AgentToolCall.directToolTarget(): AgentToolCall? {
    if (name != DIRECT_TOOL_CALL) return this
    val target = arguments["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: return null
    val args = arguments["arguments"] as? JsonObject ?: return null
    if (target == DIRECT_TOOL_CALL || target == DIRECT_TOOL_SEARCH) return null
    return copy(name = target, arguments = args, rawArguments = args.toString())
}

/** Subagent permissions follow the real child tool, including nested Code Mode operations. */
internal class LocalSubagentExecutionScope(
    val capabilities: LocalSubagentCapabilities,
    val virtualScreenId: String?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalSubagentExecutionScope>

    fun permits(tool: HarnessTool, args: JsonObject): Boolean {
        val name = tool.name
        if (tool.exposure == ToolExposure.INTERNAL) return false
        if (name in setOf(DIRECT_TOOL_CALL, DIRECT_TOOL_SEARCH)) return true
        if (capabilities.toolAllowlist != null && name !in capabilities.toolAllowlist) return false
        if (capabilities.teamManaged && tool.exposure == ToolExposure.OPTIONAL &&
            name !in capabilities.initialOptionalTools) return false
        if (!LocalRecursiveReadOnlyToolScope.permits(name, capabilities)) return false
        if (capabilities.maxDepth > 1 && name == "web_fetch" &&
            args["run_in_background"]?.jsonPrimitive?.contentOrNull == "true") return false
        if (!capabilities.allowMutation && name.startsWith("android_")) {
            if (virtualScreenId == null || name !in
                com.labteto.dshmobile.local.runtime.SUBAGENT_VIRTUAL_SCREEN_TOOLS) return false
        }
        if (name in com.labteto.dshmobile.local.runtime.SUBAGENT_VIRTUAL_SCREEN_TOOLS &&
            (virtualScreenId == null ||
                args["id"]?.jsonPrimitive?.contentOrNull != virtualScreenId)) return false
        return true
    }
}
