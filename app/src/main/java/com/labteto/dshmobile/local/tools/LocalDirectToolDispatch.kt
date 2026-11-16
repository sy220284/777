package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.LocalRecursiveReadOnlyToolScope
import com.labteto.dshmobile.local.runtime.SUBAGENT_EXCLUDED_TOOLS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.local.model.LocalToolCall
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val DIRECT_TOOL_CALL = "tool_call"
internal const val DIRECT_TOOL_SEARCH = "tool_search"

/** Resolves the single model-facing call to an exact registered tool invocation. */
internal fun LocalToolCall.directToolTarget(): LocalToolCall? {
    if (name != DIRECT_TOOL_CALL) return this
    val target = (arguments["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        ?: return null
    val args = arguments["arguments"] as? JsonObject ?: return null
    if (target == DIRECT_TOOL_CALL || target == DIRECT_TOOL_SEARCH) return null
    return copy(name = target, arguments = args, rawArguments = args.toString())
}

/** Model step validation remains on the public gateway, while scope guards see the real child. */
internal fun AgentToolCall.directToolTarget(): AgentToolCall? {
    if (name != DIRECT_TOOL_CALL) return this
    val target = (arguments["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        ?: return null
    val args = arguments["arguments"] as? JsonObject ?: return null
    if (target == DIRECT_TOOL_CALL || target == DIRECT_TOOL_SEARCH) return null
    return copy(name = target, arguments = args, rawArguments = args.toString())
}

/** Subagent permissions follow the real child tool, including nested Code Mode operations. */
internal class LocalSubagentExecutionScope(
    val capabilities: LocalSubagentCapabilities,
    val virtualScreenId: String?,
    val canDelegateNested: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalSubagentExecutionScope>

    fun canDiscover(tool: HarnessTool): Boolean {
        val name = tool.name
        if (tool.exposure == ToolExposure.INTERNAL) return false
        if (name in setOf(DIRECT_TOOL_CALL, DIRECT_TOOL_SEARCH)) return true
        // The gateway must preserve every per-agent exclusion of the previous actual schema.
        // Team members only own their explicitly defined member commands; nested delegation
        // is enabled solely when the runner has acquired the delegation admission.
        if (name in SUBAGENT_EXCLUDED_TOOLS) {
            val memberTool = capabilities.teamManaged && name in LocalAgentTeamContract.MEMBER_TOOL_NAMES
            val nestedTool = name == "subagent" && canDelegateNested
            if (!memberTool && !nestedTool) return false
        }
        if (capabilities.toolAllowlist != null && name !in capabilities.toolAllowlist) return false
        if (capabilities.teamManaged && tool.exposure == ToolExposure.OPTIONAL &&
            name !in capabilities.initialOptionalTools) return false
        return LocalRecursiveReadOnlyToolScope.permits(name, capabilities)
    }

    fun permits(tool: HarnessTool, args: JsonObject): Boolean {
        val name = tool.name
        if (!canDiscover(tool)) return false
        if (capabilities.maxDepth > 1 && name == "web_fetch" &&
            (args["run_in_background"] as? JsonPrimitive)?.contentOrNull == "true") return false
        if (!capabilities.allowMutation && name.startsWith("android_")) {
            if (virtualScreenId == null || name !in
                com.labteto.dshmobile.local.runtime.SUBAGENT_VIRTUAL_SCREEN_TOOLS) return false
        }
        if (name in com.labteto.dshmobile.local.runtime.SUBAGENT_VIRTUAL_SCREEN_TOOLS &&
            (virtualScreenId == null ||
                (args["id"] as? JsonPrimitive)?.contentOrNull != virtualScreenId)) return false
        return true
    }
}
