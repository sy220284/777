package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits
import com.labteto.dshmobile.local.agent.bindLocalAgentRole
import com.labteto.dshmobile.local.agent.renderLocalAgentRoleCatalog
import com.labteto.dshmobile.local.agent.resolveLocalAgentSkillBindings
import com.labteto.dshmobile.local.agent.renderDelegatedAgentSkillGuidance
import com.labteto.dshmobile.local.files.LocalWorkspace
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
    private val skillWorkspace: LocalWorkspace? = null,
) {
    private val control: LocalAgentControlPort =
        LocalWorkAgentControlRuntime(jobs, subagents, persistentJobs, teams)

    /** Root-slice admission restores DAGs through the same control boundary as workflow. */
    internal suspend fun resumePendingWorkflows(binding: LocalWorkRunBinding) {
        control.resumePendingWorkflows(binding)
    }

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
        control.executeTeam(run, call)?.let { return it }

        return when (call.name) {
            "list_agent_roles" -> renderLocalAgentRoleCatalog()
            "subagent", "spawn_subagent" -> {
                val task = args.string("task")
                val roleBinding = bindLocalAgentRole(
                    requested = args.optionalString("role"),
                    userInstructions = delegatedAgentInstructions(args),
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
                control.delegate(run, LocalAgentDispatchRequest(
                    kind = if (background) LocalAgentDispatchKind.READONLY_BACKGROUND
                        else LocalAgentDispatchKind.READONLY_FOREGROUND,
                    task = task,
                    instructions = instructions,
                    modelOverride = model,
                    maxSteps = maxSteps,
                    maxDepth = args.int("max_depth", 1),
                    parentToolCallId = call.id,
                    virtualScreen = virtualScreen,
                    historyMode = historyMode,
                    toolAllowlist = toolAllowlist,
                    outputSchema = outputSchema,
                ))
            }
            "subagent_fork", "fork_subagent" -> {
                val roleBinding = bindLocalAgentRole(
                    requested = args.optionalString("role"),
                    userInstructions = delegatedAgentInstructions(args),
                    parentMutationAllowed = allowMutation,
                )
                control.delegate(run, LocalAgentDispatchRequest(
                    kind = LocalAgentDispatchKind.FORK,
                    task = args.string("task"),
                    instructions = roleBinding.instructions,
                    modelOverride = LocalWorkerModelRouter.resolve(null, snapshot),
                    maxSteps = snapshot.subagentMaxSteps,
                    parentToolCallId = call.id,
                    allowMutation = roleBinding.allowMutation,
                    historyMode = LocalSubagentHistoryMode.INHERIT_PARENT,
                    toolAllowlist = args.toolAllowlist(),
                    outputSchema = args.outputSchema(),
                ))
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
            "list_agents" -> control.list(run.sessionId)
            "agent_tree" -> control.tree(run, args.int("max_nodes", 100))
            "send_message" -> control.send(
                run.sessionId,
                args.string("agent_id"),
                args.string("message"),
                LocalAgentMessageDeliveryMode.fromWire(args.optionalString("delivery_mode")),
            )
            "interrupt_agent" -> control.interrupt(run.sessionId, args.string("agent_id"))
            "agent_inspect" -> control.inspect(run.sessionId, args.string("agent_id"))
            "agent_watch" -> control.watch(
                run.sessionId, args.string("agent_id"),
                args.string("after_cursor"), args.int("wait_ms", 0),
            )
            "agent_resume" -> control.ensureChildLoaded(run.sessionId, args.string("agent_id"))
            "agent_close" -> control.close(run.sessionId, args.string("agent_id"))
            "workflow" -> control.workflow(run, LocalAgentWorkflowRequest(
                resumeGraphId = args.optionalString("resume_graph_id"),
                replanGraphId = args.optionalString("replan_graph_id"),
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
            ))
            else -> error("未覆盖的 Work 代理工具：${call.name}")
        }
    }

    /** Bound by the actual installed local skill inventory, never a model-authored grant. */
    private fun delegatedAgentInstructions(args: JsonObject): String {
        val original = args.optionalString("instructions").orEmpty()
        val skills = args["skill_ids"] ?: return original
        require(skills is JsonArray) { "AGENT_SKILL_IDS_INVALID" }
        val requested = skills.map { item ->
            require(item is JsonPrimitive && item.isString) { "AGENT_SKILL_IDS_INVALID" }
            item.content
        }
        if (requested.isEmpty()) return original
        val workspace = skillWorkspace ?: error("AGENT_SKILL_WORKSPACE_UNAVAILABLE")
        val bindings = resolveLocalAgentSkillBindings(workspace, requested)
        val skillInstructions = renderDelegatedAgentSkillGuidance(bindings)
        return if (original.isBlank()) skillInstructions else original + "\n\n" + skillInstructions
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
            "list_agent_roles",
            "list_agents",
            "agent_tree",
            "send_message",
            "interrupt_agent",
            "agent_inspect",
            "agent_watch",
            "agent_resume",
            "agent_close",
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
