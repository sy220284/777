package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits
import com.labteto.dshmobile.local.agent.LocalAgentDelegationKind
import com.labteto.dshmobile.local.agent.bindLocalAgentRole
import com.labteto.dshmobile.local.agent.buildLocalDelegatedLaunchSpec
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import com.labteto.dshmobile.local.tools.boolean
import com.labteto.dshmobile.local.tools.int
import com.labteto.dshmobile.local.tools.optionalString
import com.labteto.dshmobile.local.tools.string
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Work-owned Agent control plane. All model-facing control commands enter through here.
 *
 * Every operation is bound to the originating Work run. Global Runtime state and the currently
 * visible Session are not valid fallbacks for agent-control tools.
 */
internal class LocalAgentControlPlane(
    private val jobs: LocalJobManager,
    private val modelGateway: LocalModelGateway,
    private val subagents: LocalWorkSubagentRuntime,
    private val persistentJobs: LocalPersistentJobRecoveryCoordinator,
    private val teams: LocalAgentTeamRuntime,
) {
    internal suspend fun execute(
        call: LocalToolCall,
        allowMutation: Boolean,
        binding: LocalWorkRunBinding?,
    ): String? {
        if (call.name !in TOOL_NAMES) return null
        val run = binding
            ?: return "Work 代理工具缺少活动运行上下文，已拒绝旧的无绑定执行路径"
        val args = call.arguments
        val snapshot = run.aggregateSnapshot()
        val runner = subagents.runner(run)

        teams.execute(call, run)?.let { return it }

        return when (call.name) {
            "subagent", "spawn_subagent" -> {
                val task = args.string("task")
                val roleBinding = bindLocalAgentRole(
                    requested = args.optionalString("role"),
                    userInstructions = args.optionalString("instructions").orEmpty(),
                    parentMutationAllowed = false,
                )
                val instructions = roleBinding.instructions
                val model = LocalWorkerModelRouter.resolve(args.optionalString("model"), snapshot)
                val maxSteps = LocalAgentRuntimeLimits.normalizeSubagentSteps(
                    args.int("max_steps", snapshot.subagentMaxSteps),
                )
                val virtualScreen = args.boolean("virtual_screen", false)
                val background = args.boolean("run_in_background", false)
                val historyMode = resolveReadonlySubagentHistoryMode(
                    args.optionalString("context_mode"), background, virtualScreen,
                )
                val outputSchema = args.outputSchema()
                val toolAllowlist = args.toolAllowlist()
                if (background) {
                    persistentJobs.startReadonlySubagent(
                        task = task,
                        model = model,
                        maxSteps = maxSteps,
                        instructions = instructions,
                        virtualScreen = virtualScreen,
                        outputSchema = outputSchema,
                        toolAllowlist = toolAllowlist,
                        sessionId = run.sessionId,
                        boundState = snapshot,
                        historySnapshot = run.runHandle.modelHistory::snapshot,
                    )
                } else {
                    runner.run(
                        buildLocalDelegatedLaunchSpec(
                            kind = LocalAgentDelegationKind.FOREGROUND_READONLY,
                            task = task,
                            instructions = instructions,
                            modelOverride = model,
                            maxSteps = maxSteps,
                            parentCallId = if (historyMode == LocalSubagentHistoryMode.INHERIT_PARENT) call.id else null,
                            virtualScreen = virtualScreen,
                            historyMode = historyMode,
                            toolAllowlist = toolAllowlist,
                            outputSchema = outputSchema,
                        ),
                    )
                }
            }
            "subagent_fork", "fork_subagent" -> {
                val roleBinding = bindLocalAgentRole(
                    requested = args.optionalString("role"),
                    userInstructions = args.optionalString("instructions").orEmpty(),
                    parentMutationAllowed = allowMutation,
                )
                runner.run(
                    buildLocalDelegatedLaunchSpec(
                        kind = LocalAgentDelegationKind.FORK,
                        task = args.string("task"),
                        instructions = roleBinding.instructions,
                        modelOverride = LocalWorkerModelRouter.resolve(null, snapshot),
                        maxSteps = snapshot.subagentMaxSteps,
                        parentCallId = call.id,
                        allowMutation = roleBinding.allowMutation,
                        toolAllowlist = args.toolAllowlist(),
                        outputSchema = args.outputSchema(),
                    ),
                )
            }
            "list_subagent_models" -> {
                val saved = modelGateway.configuredProfiles()
                val readyIds = modelGateway.availableProfiles().mapTo(mutableSetOf()) { it.id }
                val catalog = renderAgentModelCatalog(
                    profiles = saved,
                    credentialReadyIds = readyIds,
                    activeProfileId = snapshot.modelState.modelSelection.activeProfile?.id,
                    workerProfileId = snapshot.modelState.modelSelection.workerProfile?.id,
                )
                if (!args.boolean("include_provider_models", false)) {
                    catalog
                } else {
                    val profileId = args.optionalString("profile_id")?.takeIf(String::isNotBlank)
                        ?: error("查询官方模型目录必须指定本机已保存的 profile_id")
                    require(profileId in readyIds) { "所选模型没有就绪的本机凭据，请在设置中重新连接" }
                    val providerModels = runCatching { modelGateway.discoverProviderModelIds(profileId) }
                        .fold(
                            onSuccess = { ids ->
                                if (ids.isEmpty()) "服务商返回的模型列表为空"
                                else "服务商返回 ${ids.size} 个模型（仅目录可见，不能据此认定调用权限）：\n" +
                                    ids.joinToString("\n")
                            },
                            onFailure = { error ->
                                if (error is CancellationException) throw error
                                "官方模型目录查询未成功（${when (error) {
                                    is IllegalArgumentException -> "配置或接口不受支持"
                                    else -> "服务商拒绝、响应异常或网络故障"
                                }}）。可在应用中核对该档案权限与官方接口。 "
                            },
                        )
                    "$catalog\n\n【指定配置的官方模型目录】\n$providerModels"
                }
            }
            "list_agents" -> jobs.listAgents(run.sessionId)
            "send_message" -> persistentJobs.sendMessage(
                agentId = args.string("agent_id"),
                message = args.string("message"),
                sessionId = run.sessionId,
            )
            "interrupt_agent" -> jobs.kill(args.string("agent_id"), run.sessionId)
            "workflow" -> subagents.runWorkflow(
                resumeGraphId = args.optionalString("resume_graph_id"),
                allowMutation = args.boolean("allow_mutation", false).also { requested ->
                    require(!requested || allowMutation) { "当前执行范围不允许可写任务图" }
                },
                nodeInputFiles = args["node_input_files"]?.jsonArray?.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }.orEmpty(),
                nodeOutputFiles = args["node_output_files"]?.jsonArray?.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }.orEmpty(),
                inputFiles = args["input_files"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                requiredFiles = args["required_files"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                tasks = args["tasks"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty(),
                mode = args.optionalString("mode") ?: "parallel",
                dependencies = args["dependencies"]?.let { matrix ->
                    require(matrix is JsonArray) { "dependencies 必须为二维整数数组" }
                    matrix.map { row ->
                        require(row is JsonArray) { "dependencies 每项必须为整数数组" }
                        row.map { cell ->
                            val rawIndex = (cell as? JsonPrimitive)?.contentOrNull
                            val index = rawIndex?.toIntOrNull()
                            require(index != null && index >= 0 && rawIndex == index.toString()) {
                                "dependencies 只接受零基非负整数索引"
                            }
                            index
                        }
                    }
                } ?: emptyList(),
                requiredEvidence = args["required_evidence"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty(),
                modelOverride = args.optionalString("model"),
                outputSchema = args.outputSchema(),
                toolAllowlist = args.toolAllowlist(),
                binding = run,
            )
            else -> error("未覆盖的 Work 代理工具：${call.name}")
        }
    }

    private fun JsonObject.outputSchema(): JsonObject? {
        val raw = this["output_schema"] ?: return null
        return raw as? JsonObject ?: error("output_schema 必须是 JSON object")
    }

    private fun JsonObject.toolAllowlist(): Set<String>? {
        val raw = this["allowed_tools"] ?: return null
        val array = raw as? JsonArray ?: error("allowed_tools 必须是字符串数组")
        return array.map { item ->
            (item as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: error("allowed_tools 包含非法工具名")
        }.toSet()
    }

    internal companion object {
        val TOOL_NAMES: Set<String> = setOf(
            "subagent",
            "spawn_subagent",
            "subagent_fork",
            "fork_subagent",
            "list_subagent_models",
            "list_agents",
            "send_message",
            "interrupt_agent",
            "workflow",
        ) + LocalAgentTeamContract.TOOL_NAMES
    }
}

/** Read-only dispatch can share conversation context without inheriting write permission. */
internal fun resolveReadonlySubagentHistoryMode(
    requested: String?,
    background: Boolean,
    virtualScreen: Boolean,
): LocalSubagentHistoryMode {
    val mode = when (requested?.trim()?.lowercase().orEmpty()) {
        "", "isolated" -> LocalSubagentHistoryMode.ISOLATED
        "inherit" -> LocalSubagentHistoryMode.INHERIT_PARENT
        else -> throw IllegalArgumentException("context_mode 只能为 isolated 或 inherit")
    }
    require(mode != LocalSubagentHistoryMode.INHERIT_PARENT || !background) {
        "继承完整上下文仅支持前台只读子代理；后台恢复无法安全继承父会话历史"
    }
    require(mode != LocalSubagentHistoryMode.INHERIT_PARENT || !virtualScreen) {
        "继承完整上下文暂不支持同时分配独立虚拟屏"
    }
    return mode
}
