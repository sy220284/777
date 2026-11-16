package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.LocalRecursiveReadOnlyToolScope
import com.labteto.dshmobile.local.runtime.SUBAGENT_EXCLUDED_TOOLS
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.local.model.LocalToolCall
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val DIRECT_TOOL_CALL = "tool_call"
internal const val DIRECT_TOOL_SEARCH = "tool_search"
private val JSON_NUMBER_LITERAL = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")

/** Shared immutable member-tool contract. Team feature and tool scope consume one source. */
internal val DIRECT_TEAM_MEMBER_TOOL_NAMES = setOf(
    "team_send_message", "team_respond_shutdown", "team_ack_message",
    "team_messages", "team_task_list", "team_task_get", "team_task_update",
)

/** Resolves the single model-facing call to an exact registered tool invocation. */
internal fun LocalToolCall.directToolTarget(): LocalToolCall? {
    if (name != DIRECT_TOOL_CALL) return this
    val target = (arguments["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        ?: return null
    val args = arguments["arguments"] as? JsonObject ?: return null
    if (target == DIRECT_TOOL_CALL || target == DIRECT_TOOL_SEARCH) return null
    return copy(name = target, arguments = args, rawArguments = args.toString())
}

/**
 * A generic tool_call gateway cannot expose all registered tools' field types in its static
 * schema. Interpret only unambiguous JSON-number strings against the selected tool's live
 * schema before normal registry validation. All other values retain their original types.
 *
 * This is a gateway boundary operation, never a relaxation of the target tool's schema.
 */
internal fun LocalToolCall.withSchemaTypedNumbers(toolSchema: JsonObject): LocalToolCall {
    val parameters = ((toolSchema["function"] as? JsonObject)
        ?.get("parameters") as? JsonObject) ?: return this

    fun normalize(value: JsonElement, schema: JsonObject): JsonElement {
        return when ((schema["type"] as? JsonPrimitive)?.contentOrNull) {
            "integer", "number" -> {
                val primitive = value as? JsonPrimitive
                if (primitive == null || !primitive.isString ||
                    !JSON_NUMBER_LITERAL.matches(primitive.content)) {
                    value
                } else {
                    val parsed = runCatching { Json.parseToJsonElement(primitive.content) }
                        .getOrNull() as? JsonPrimitive
                    if (parsed != null && !parsed.isString &&
                        parsed.content.toBigDecimalOrNull() != null) parsed else value
                }
            }
            "object" -> {
                val obj = value as? JsonObject ?: return value
                val properties = schema["properties"] as? JsonObject
                val additional = schema["additionalProperties"] as? JsonObject
                var changed = false
                val mapped = obj.mapValues { (key, child) ->
                    val fieldSchema = (properties?.get(key) as? JsonObject) ?: additional
                    val normalized = if (fieldSchema != null) normalize(child, fieldSchema) else child
                    if (normalized !== child) changed = true
                    normalized
                }
                if (changed) JsonObject(mapped) else value
            }
            "array" -> {
                val array = value as? JsonArray ?: return value
                val itemSchema = schema["items"] as? JsonObject ?: return value
                var changed = false
                val mapped = array.map { child ->
                    val normalized = normalize(child, itemSchema)
                    if (normalized !== child) changed = true
                    normalized
                }
                if (changed) JsonArray(mapped) else value
            }
            else -> value
        }
    }

    val normalized = normalize(arguments, parameters) as JsonObject
    return if (normalized === arguments) this
    else copy(arguments = normalized, rawArguments = normalized.toString())
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
        if (name in DIRECT_TEAM_MEMBER_TOOL_NAMES && !capabilities.teamManaged) return false
        // The gateway must preserve every per-agent exclusion of the previous actual schema.
        // Team members only own their explicitly defined member commands; nested delegation
        // is enabled solely when the runner has acquired the delegation admission.
        if (name in SUBAGENT_EXCLUDED_TOOLS) {
            val memberTool = capabilities.teamManaged && name in DIRECT_TEAM_MEMBER_TOOL_NAMES
            val nestedTool = name == "subagent" && canDelegateNested
            if (!memberTool && !nestedTool) return false
        }
        if (capabilities.toolAllowlist != null && name !in capabilities.toolAllowlist) return false
        if (capabilities.teamManaged && tool.exposure == ToolExposure.OPTIONAL &&
            name !in DIRECT_TEAM_MEMBER_TOOL_NAMES &&
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
