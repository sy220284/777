package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.tools.JsonSchemaValidator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class LocalSubagentHistoryMode {
    ISOLATED,
    INHERIT_PARENT,
}

internal data class LocalSubagentCapabilities(
    val allowMutation: Boolean = false,
    val continuable: Boolean = false,
    val virtualScreen: Boolean = false,
    val historyMode: LocalSubagentHistoryMode = LocalSubagentHistoryMode.ISOLATED,
    val maxDepth: Int = 1,
    val toolAllowlist: Set<String>? = null,
    val outputSchema: JsonObject? = null,
    val teamManaged: Boolean = false,
    val initialOptionalTools: Set<String> = emptySet(),
)

internal data class LocalSubagentLaunchSpec(
    val task: String,
    val instructions: String = "",
    val modelOverride: String?,
    val maxSteps: Int,
    val parentCallId: String? = null,
    val backgroundJobId: String? = null,
    val capabilities: LocalSubagentCapabilities = LocalSubagentCapabilities(),
)

internal fun validateLocalSubagentLaunchSpec(
    spec: LocalSubagentLaunchSpec,
    structuredOutputSupported: Boolean = false,
): LocalSubagentLaunchSpec {
    require(spec.task.isNotBlank()) { "SUBAGENT_TASK_REQUIRED：子代理任务不能为空" }
    require(spec.instructions.length <= MAX_SUBAGENT_INSTRUCTIONS_CHARS) {
        "SUBAGENT_INSTRUCTIONS_TOO_LARGE：子代理长期指令超过大小上限"
    }
    require(spec.maxSteps in LocalAgentRuntimeLimits.SUBAGENT_MIN_STEPS..LocalAgentRuntimeLimits.MAX_CONFIGURED_STEPS) {
        "SUBAGENT_STEP_LIMIT_INVALID：子代理步数超出允许范围"
    }
    require(spec.capabilities.maxDepth == 1) {
        "SUBAGENT_DEPTH_NOT_SUPPORTED：当前子代理能力只允许 depth=1"
    }
    require(!spec.capabilities.continuable || !spec.capabilities.allowMutation || spec.capabilities.teamManaged) {
        "SUBAGENT_CONTINUATION_MUTATION_BLOCKED：可继续子代理必须保持只读，避免冷恢复重放未知副作用"
    }
    require(
        !spec.capabilities.continuable ||
            spec.capabilities.historyMode == LocalSubagentHistoryMode.ISOLATED
    ) {
        "SUBAGENT_CONTINUATION_HISTORY_BLOCKED：可继续子代理暂不允许继承父代理完整历史"
    }
    require(spec.backgroundJobId == null || spec.capabilities.continuable) {
        "SUBAGENT_BACKGROUND_REQUIRES_CONTINUATION：持久子代理必须显式 continuable"
    }
    spec.capabilities.outputSchema?.let { schema ->
        require(structuredOutputSupported) {
            "SUBAGENT_OUTPUT_SCHEMA_NOT_SUPPORTED：当前运行时尚未启用结构化子代理结果"
        }
        require(schema.toString().length <= MAX_SUBAGENT_OUTPUT_SCHEMA_CHARS) {
            "SUBAGENT_OUTPUT_SCHEMA_TOO_LARGE：结构化结果 schema 超过大小上限"
        }
        JsonSchemaValidator.validateSchema(
            schema = schema,
            requireObjectRoot = true,
        )?.let { problem ->
            throw IllegalArgumentException("SUBAGENT_OUTPUT_SCHEMA_INVALID：$problem")
        }
    }
    require(!spec.capabilities.teamManaged || spec.backgroundJobId != null) {
        "SUBAGENT_TEAM_REQUIRES_PERSISTENT_JOB：可写团队助手必须绑定持久任务身份"
    }
    require(spec.capabilities.initialOptionalTools.size <= 24 &&
        spec.capabilities.initialOptionalTools.none(String::isBlank)) {
        "SUBAGENT_TEAM_GRANTS_INVALID：扩展授权列表无效"
    }
    spec.capabilities.toolAllowlist?.let { allowlist ->
        require(allowlist.none(String::isBlank)) {
            "SUBAGENT_TOOL_FILTER_INVALID：工具白名单不能包含空名称"
        }
    }
    return spec
}


private const val MAX_SUBAGENT_OUTPUT_SCHEMA_CHARS = 32_768
private const val MAX_SUBAGENT_INSTRUCTIONS_CHARS = 16_384

internal fun validateLocalSubagentToolAllowlist(
    source: JsonArray,
    capabilities: LocalSubagentCapabilities,
) {
    val allowlist = capabilities.toolAllowlist ?: return
    val available = source.mapNotNull { element ->
        val function = (element as? JsonObject)?.get("function") as? JsonObject
        function?.get("name")?.jsonPrimitive?.contentOrNull
    }.toSet()
    val unavailable = allowlist - available
    require(unavailable.isEmpty()) {
        "SUBAGENT_TOOL_FILTER_UNAVAILABLE：工具白名单包含当前子代理不可用能力：" +
            unavailable.sorted().joinToString(",")
    }
}

internal fun filterLocalSubagentSchemas(
    source: JsonArray,
    capabilities: LocalSubagentCapabilities,
): JsonArray {
    val allowlist = capabilities.toolAllowlist ?: return source
    return JsonArray(
        source.filter { element ->
            val function = (element as? JsonObject)?.get("function") as? JsonObject
            val name = function?.get("name")?.jsonPrimitive?.contentOrNull
            name != null && name in allowlist
        },
    )
}


internal fun encodeLocalSubagentCapabilities(
    capabilities: LocalSubagentCapabilities,
): JsonObject = buildJsonObject {
    put("allow_mutation", capabilities.allowMutation)
    put("continuable", capabilities.continuable)
    put("virtual_screen", capabilities.virtualScreen)
    put("history_mode", capabilities.historyMode.name.lowercase())
    put("max_depth", capabilities.maxDepth)
    capabilities.toolAllowlist?.let { allowlist ->
        put("tool_allowlist", JsonArray(allowlist.sorted().map(::JsonPrimitive)))
    }
    capabilities.outputSchema?.let { put("output_schema", it) }
    if (capabilities.teamManaged) put("team_managed", true)
    if (capabilities.initialOptionalTools.isNotEmpty()) {
        put("initial_optional_tools", JsonArray(capabilities.initialOptionalTools.sorted().map(::JsonPrimitive)))
    }
}

internal fun decodeLocalSubagentCapabilities(
    payload: JsonObject,
    version: Int,
): LocalSubagentCapabilities {
    if (version <= 1) {
        return LocalSubagentCapabilities(
            allowMutation = false,
            continuable = true,
            virtualScreen = payload["virtual_screen"]?.jsonPrimitive?.booleanOrNull ?: false,
            historyMode = LocalSubagentHistoryMode.ISOLATED,
            maxDepth = 1,
        )
    }
    val data = payload["capabilities"] as? JsonObject
        ?: error("持久子代理 V2 缺少 capabilities")
    val historyMode = when (
        data["history_mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
    ) {
        "isolated" -> LocalSubagentHistoryMode.ISOLATED
        "inherit_parent" -> LocalSubagentHistoryMode.INHERIT_PARENT
        else -> error("持久子代理 capabilities.history_mode 无效")
    }
    val toolAllowlist = (data["tool_allowlist"] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.toSet()
    return LocalSubagentCapabilities(
        allowMutation = data["allow_mutation"]?.jsonPrimitive?.booleanOrNull ?: false,
        continuable = data["continuable"]?.jsonPrimitive?.booleanOrNull ?: false,
        virtualScreen = data["virtual_screen"]?.jsonPrimitive?.booleanOrNull ?: false,
        historyMode = historyMode,
        maxDepth = data["max_depth"]?.jsonPrimitive?.intOrNull ?: 1,
        toolAllowlist = toolAllowlist,
        outputSchema = data["output_schema"] as? JsonObject,
        teamManaged = data["team_managed"]?.jsonPrimitive?.booleanOrNull ?: false,
        initialOptionalTools = (data["initial_optional_tools"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty(),
    )
}
