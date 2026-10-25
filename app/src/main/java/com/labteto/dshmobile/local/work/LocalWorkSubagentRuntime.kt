package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.AgentToolResult
import com.labteto.dshmobile.local.memory.LocalMemoryTools
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.tools.LocalToolCapabilityScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.labteto.dshmobile.harness.workflow.TaskGraphAcceptance
import kotlinx.coroutines.sync.withLock

/** Work-owned subagent and workflow composition bound to the originating Work run. */
internal class LocalWorkSubagentRuntime(
    private val persistentJobs: LocalPersistentJobRecoveryCoordinator,
    private val workspaceRoot: java.io.File,
    private val factory: LocalSubagentRunnerFactory,
    private val schemasProvider: (Boolean, Boolean, Set<String>) -> JsonArray,
    private val executeTool: suspend (
        binding: LocalWorkRunBinding,
        call: LocalToolCall,
        allowMutation: Boolean,
        memoryTools: LocalMemoryTools,
        enabledOptionalTools: MutableSet<String>,
    ) -> AgentToolResult,
    private val pruneOutput: (LocalWorkRunBinding, String) -> String,
) {
    internal fun runner(binding: LocalWorkRunBinding): LocalSubagentRunner =
        factory.createBound(
            sessionId = binding.sessionId,
            boundState = binding.aggregateSnapshot(),
            runKind = LocalAgentRunKind.SUBAGENT,
            schemasProvider = schemasProvider,
            executeTool = { call, allowMutation, memoryTools, enabledOptional ->
                withContext(LocalToolCapabilityScope(enabledOptional)) {
                    executeTool(binding, call, allowMutation, memoryTools, enabledOptional)
                }
            },
            historySnapshot = binding.runHandle.modelHistory::snapshot,
            modelAdmission = binding.executionControl.asModelAdmissionPort(),
        )

    private val taskGraphs = LocalAgentTaskGraphController(persistentJobs, workspaceRoot, pruneOutput)

    internal suspend fun runWorkflow(
        tasks: List<String>, mode: String, requiredEvidence: List<String>, modelOverride: String?,
        outputSchema: JsonObject?, toolAllowlist: Set<String>?, binding: LocalWorkRunBinding,
        dependencies: List<List<Int>> = emptyList(),
        allowMutation: Boolean = false, requiredFiles: List<String> = emptyList(), inputFiles: List<String> = emptyList(),
        nodeInputFiles: List<List<String>> = emptyList(), nodeOutputFiles: List<List<String>> = emptyList(),
        resumeGraphId: String? = null,
    ): String = taskGraphs.runWorkflow(
        tasks, mode, requiredEvidence, modelOverride, outputSchema, toolAllowlist, binding,
        dependencies, allowMutation, requiredFiles, inputFiles, nodeInputFiles, nodeOutputFiles, resumeGraphId,
    )

    internal suspend fun resumePendingWorkflows(binding: LocalWorkRunBinding) =
        taskGraphs.resumePendingWorkflows(binding)

}
