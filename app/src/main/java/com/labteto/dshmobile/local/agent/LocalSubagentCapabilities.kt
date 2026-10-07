package com.labteto.dshmobile.local.agent

import kotlinx.serialization.json.JsonObject

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
)

internal data class LocalSubagentLaunchSpec(
    val task: String,
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
    require(spec.maxSteps in LocalAgentRuntimeLimits.SUBAGENT_MIN_STEPS..LocalAgentRuntimeLimits.MAX_CONFIGURED_STEPS) {
        "SUBAGENT_STEP_LIMIT_INVALID：子代理步数超出允许范围"
    }
    require(spec.capabilities.maxDepth == 1) {
        "SUBAGENT_DEPTH_NOT_SUPPORTED：当前子代理能力只允许 depth=1"
    }
    require(!spec.capabilities.continuable || !spec.capabilities.allowMutation) {
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
    require(spec.capabilities.outputSchema == null || structuredOutputSupported) {
        "SUBAGENT_OUTPUT_SCHEMA_NOT_SUPPORTED：当前运行时尚未启用结构化子代理结果"
    }
    spec.capabilities.toolAllowlist?.let { allowlist ->
        require(allowlist.none(String::isBlank)) {
            "SUBAGENT_TOOL_FILTER_INVALID：工具白名单不能包含空名称"
        }
    }
    return spec
}
