package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits
import com.labteto.dshmobile.local.agent.LocalSubagentCapabilities
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryMode
import com.labteto.dshmobile.local.agent.LocalSubagentLaunchSpec
import com.labteto.dshmobile.local.tools.boolean
import com.labteto.dshmobile.local.tools.int
import com.labteto.dshmobile.local.tools.optionalString
import com.labteto.dshmobile.local.tools.string
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Work-owned model-facing subagent/workflow control.
 *
 * Every operation is bound to the originating Work run. Global Runtime state and the currently
 * visible Session are not valid fallbacks for agent-control tools.
 */
internal class LocalWorkAgentControlBuiltinRuntime(
    private val jobs: LocalJobManager,
    private val modelGateway: LocalModelGateway,
    private val subagents: LocalWorkSubagentRuntime,
    private val persistentJobs: LocalPersistentJobRecoveryCoordinator,
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

        return when (call.name) {
            "subagent", "spawn_subagent" -> {
                val task = args.string("task")
                val model = LocalWorkerModelRouter.resolve(args.optionalString("model"), snapshot)
                val maxSteps = LocalAgentRuntimeLimits.normalizeSubagentSteps(
                    args.int("max_steps", snapshot.subagentMaxSteps),
                )
                val virtualScreen = args.boolean("virtual_screen", false)
                if (args.boolean("run_in_background", false)) {
                    persistentJobs.startReadonlySubagent(
                        task = task,
                        model = model,
                        maxSteps = maxSteps,
                        virtualScreen = virtualScreen,
                        sessionId = run.sessionId,
                        boundState = snapshot,
                        historySnapshot = run.runHandle.modelHistory::snapshot,
                    )
                } else {
                    runner.run(
                        LocalSubagentLaunchSpec(
                            task = task,
                            modelOverride = model,
                            maxSteps = maxSteps,
                            capabilities = LocalSubagentCapabilities(
                                allowMutation = false,
                                continuable = false,
                                virtualScreen = virtualScreen,
                                historyMode = LocalSubagentHistoryMode.ISOLATED,
                            ),
                        ),
                    )
                }
            }
            "subagent_fork", "fork_subagent" -> runner.run(
                LocalSubagentLaunchSpec(
                    task = args.string("task"),
                    modelOverride = LocalWorkerModelRouter.resolve(null, snapshot),
                    maxSteps = snapshot.subagentMaxSteps,
                    parentCallId = call.id,
                    capabilities = LocalSubagentCapabilities(
                        allowMutation = allowMutation,
                        continuable = false,
                        virtualScreen = false,
                        historyMode = LocalSubagentHistoryMode.INHERIT_PARENT,
                    ),
                ),
            )
            "list_subagent_models" -> modelGateway.availableProfiles().joinToString("\n") {
                "${it.id} | ${it.model} | ${it.provider} | ${it.authKind} | ${it.baseUrl}"
            }
            "list_agents" -> jobs.listAgents(run.sessionId)
            "send_message" -> persistentJobs.sendMessage(
                agentId = args.string("agent_id"),
                message = args.string("message"),
                sessionId = run.sessionId,
            )
            "interrupt_agent" -> jobs.kill(args.string("agent_id"), run.sessionId)
            "workflow" -> subagents.runWorkflow(
                tasks = args["tasks"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty(),
                mode = args.optionalString("mode") ?: "parallel",
                requiredEvidence = args["required_evidence"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty(),
                modelOverride = args.optionalString("model"),
                binding = run,
                runner = runner,
            )
            else -> error("未覆盖的 Work 代理工具：${call.name}")
        }
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
        )
    }
}
