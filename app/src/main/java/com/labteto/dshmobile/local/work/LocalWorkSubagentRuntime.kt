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

    // This is admission serialization, not an execution owner. Jobs and root Work retain ownership.

    internal suspend fun runWorkflow(
        tasks: List<String>, mode: String, requiredEvidence: List<String>, modelOverride: String?,
        outputSchema: JsonObject?, toolAllowlist: Set<String>?, binding: LocalWorkRunBinding,
        dependencies: List<List<Int>> = emptyList(),
        allowMutation: Boolean = false, requiredFiles: List<String> = emptyList(), inputFiles: List<String> = emptyList(),
        nodeInputFiles: List<List<String>> = emptyList(), nodeOutputFiles: List<List<String>> = emptyList(),
        resumeGraphId: String? = null,
    ): String = binding.workflowAdmission.withLock {
        if (!resumeGraphId.isNullOrBlank()) {
            val open = openGraphs(binding).singleOrNull { it.graphId == resumeGraphId }
                ?: error("未找到唯一未结算图：$resumeGraphId")
            val plan = LocalWorkTaskGraphPlan(requireNotNull(open.plan) { "历史图缺少完整定义，不能恢复" })
            require(!plan.writable || allowMutation) { "恢复可写图需要本次显式写授权" }
            val execution = requireNotNull(open.graphExecutionId)
            binding.eventLog.append("taskgraph/resume-authorized", buildJsonObject {
                put("graph_execution_id", execution)
                put("source_message_id", binding.eventLog.latestMatching(setOf("turn/start")) { true }
                    ?.data?.get("source_message_id") ?: JsonNull)
                put("user_revision", workflowUserRevision(binding.eventLog))
                put("allow_mutation", allowMutation)
            })
            return@withLock executePlan(binding, open.graphId, execution, open.goalId, plan)
        }
        val route = persistentJobs.workflowRoute(LocalWorkerModelRouter.resolve(modelOverride, binding.aggregateSnapshot()))
        val model = route.first
        val prepared = LocalWorkflowCoordinator({ "" }, { it }, {}).prepare(tasks, mode, requiredEvidence, dependencies)
        require(nodeInputFiles.isEmpty() || nodeInputFiles.size == tasks.size) { "node_input_files 必须和 tasks 等长" }
        require(nodeOutputFiles.isEmpty() || nodeOutputFiles.size == tasks.size) { "node_output_files 必须和 tasks 等长" }
        val goal = binding.workState.snapshot().goal
        requireCurrentWorkflowGoal(goal, goal)
        val plan = LocalWorkTaskGraphPlan(buildJsonObject {
            put("version", 1)
            put("user_revision", workflowUserRevision(binding.eventLog))
            put("source_message_id", binding.eventLog.latestMatching(setOf("turn/start")) { true }
                ?.data?.get("source_message_id") ?: JsonNull)
            put("model", model)
            put("route", route.second)
            put("max_steps", binding.aggregateSnapshot().subagentMaxSteps)
            put("allow_mutation", allowMutation)
            put("pipeline", prepared.pipeline)
            put("goal_fingerprint", goal?.let(::localWorkGoalFingerprint).orEmpty())
            put("evidence", buildJsonArray { requiredEvidence.forEach { add(JsonPrimitive(it)) } })
            put("files", buildJsonArray { (inputFiles + requiredFiles).distinct().forEach { add(JsonPrimitive(it)) } })
            put("input_files", buildJsonArray { inputFiles.forEach { add(JsonPrimitive(it)) } })
            put("node_input_files", buildJsonArray { nodeInputFiles.forEach { row -> add(buildJsonArray { row.forEach { add(JsonPrimitive(it)) } }) } })
            put("node_output_files", buildJsonArray { nodeOutputFiles.forEach { row -> add(buildJsonArray { row.forEach { add(JsonPrimitive(it)) } }) } })
            put("required_files", buildJsonArray { requiredFiles.forEach { add(JsonPrimitive(it)) } })
            outputSchema?.let { put("output_schema", it) }
            toolAllowlist?.let { put("allowed_tools", buildJsonArray { it.sorted().forEach { add(JsonPrimitive(it)) } }) }
            put("nodes", buildJsonArray {
                prepared.nodes.forEach { node -> add(buildJsonObject {
                    put("node_id", node.id)
                    put("task", node.task)
                    put("depends_on", buildJsonArray { node.dependsOn.forEach { add(JsonPrimitive(it)) } })
                }) }
            })
        })
        plan.nodes.indices.forEach { index -> plan.inputPaths(index).forEach { path ->
            require(localWorkFileSha256(workspaceRoot, path) != null) { "输入文件不存在或不属于安全工作区：$path" }
        } }
        val existing = openGraphs(binding).singleOrNull { it.plan == plan.value }
        val graphId = existing?.graphId ?: java.util.UUID.randomUUID().toString()
        val execution = existing?.graphExecutionId ?: java.util.UUID.randomUUID().toString()
        if (existing == null) binding.eventLog.append("taskgraph/start", buildJsonObject {
            put("graph_id", graphId); put("graph_execution_id", execution)
            put("session_id", binding.sessionId); put("goal_id", goal?.id.orEmpty())
            put("goal_fingerprint", goal?.let(::localWorkGoalFingerprint).orEmpty())
            put("plan", plan.value); put("plan_version", plan.version)
            put("nodes", plan.value.getValue("nodes"))
        })
        executePlan(binding, graphId, execution, goal?.id.orEmpty(), plan)
    }

    private val openGraphProjections = java.util.WeakHashMap<
        com.labteto.dshmobile.local.session.LocalSessionEventLog, LocalWorkOpenGraphProjection>()

    private fun openGraphs(binding: LocalWorkRunBinding): List<LocalWorkTaskGraphRecoveryLedger.OpenGraph> {
        val log = binding.eventLog
        val projection = synchronized(openGraphProjections) {
            openGraphProjections.getOrPut(log) { LocalWorkOpenGraphProjection() }
        }
        return synchronized(projection) { projection.unresolved(log) }
    }

    /** Called inside the admitted root run, before its next model request, including Goal-less research. */
    internal suspend fun resumePendingWorkflows(binding: LocalWorkRunBinding) = binding.workflowAdmission.withLock {
        openGraphs(binding).forEach { open ->
            val value = open.plan ?: return@forEach // Legacy progress is not an executable definition.
            val execution = open.graphExecutionId ?: return@forEach
            val plan = LocalWorkTaskGraphPlan(value)
            val auth = binding.eventLog.latestMatching(setOf("taskgraph/resume-authorized")) {
                it["graph_execution_id"]?.jsonPrimitive?.contentOrNull == execution
            }?.data ?: value
            val currentSource = binding.eventLog.latestMatching(setOf("turn/start")) { true }?.data?.get("source_message_id")
            val applicable = auth["source_message_id"] == currentSource &&
                auth["user_revision"]?.jsonPrimitive?.longOrNull == workflowUserRevision(binding.eventLog)
            val revision = workflowUserRevision(binding.eventLog)
            if (!applicable && binding.eventLog.latestMatching(setOf("taskgraph/paused")) {
                    it["graph_execution_id"]?.jsonPrimitive?.contentOrNull == execution &&
                        it["user_revision"]?.jsonPrimitive?.longOrNull == revision
                } != null) return@forEach
            val result = if (applicable) executePlan(binding, open.graphId, execution, open.goalId, plan) else {
                persistentJobs.cancelWorkflowJobs(binding.sessionId, execution, preserveSafeSlice = true)
                binding.eventLog.append("taskgraph/paused", buildJsonObject {
                    put("graph_id", open.graphId); put("graph_execution_id", execution)
                    put("user_revision", revision)
                })
                "旧请求任务图已暂停：${open.graphId}。先处理本次用户输入；如用户仍需原任务，可调用 workflow(resume_graph_id=该编号) 显式继续，写图还需 allow_mutation=true；未知写副作用仍不得重放。"
            }
            binding.runHandle.modelHistory.append(buildJsonObject {
                put("role", "user")
                put("content", "持久工作流恢复结果（${open.graphId}）：\n$result")
            })
        }
    }

    private suspend fun executePlan(binding: LocalWorkRunBinding, graphId: String, execution: String,
        goalId: String, plan: LocalWorkTaskGraphPlan): String {
        val log = binding.eventLog
        val goalFingerprint = plan.value.getValue("goal_fingerprint").jsonPrimitive.content
        val authorization = log.latestMatching(setOf("taskgraph/resume-authorized")) {
            it["graph_execution_id"]?.jsonPrimitive?.contentOrNull == execution
        }?.data ?: plan.value
        val source = authorization["source_message_id"]
        fun checkOwner() {
            if (workflowAuthorizationSuperseded(log, authorization)) {
                throw LocalWorkflowSuperseded()
            }
            val goal = binding.workState.snapshot().goal
            check(if (goalFingerprint.isBlank()) goal == null else goal?.status == "active" &&
                localWorkGoalFingerprint(goal) == goalFingerprint) { "任务图目标版本已失效" }
        }
        fun settle(outcome: String, detail: String = "") = log.append("taskgraph/end", buildJsonObject {
            put("graph_id", graphId); put("graph_execution_id", execution); put("goal_id", goalId)
            put("goal_fingerprint", goalFingerprint); put("outcome", outcome); put("detail", detail)
        })
        val jobsByNode = java.util.concurrent.ConcurrentHashMap<String, String>()
        fun dependencyVersions(ids: List<String>): JsonObject = buildJsonObject {
            ids.forEach { id ->
                val accepted = log.latestMatching(setOf("taskgraph/node-accepted")) {
                    it["graph_execution_id"]?.jsonPrimitive?.contentOrNull == execution &&
                        it["node_id"]?.jsonPrimitive?.contentOrNull == id
                } ?: error("依赖缺少持久验收版本：$id")
                put(id, accepted.data.getValue("result_sequence"))
            }
        }
        fun checkDependencies(ids: List<String>, outputs: Map<String, String>) {
            val pending = java.util.ArrayDeque(ids)
            val checked = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (!checked.add(id)) continue
                val node = plan.nodes.single { it.id == id }
                val accepted = log.latestMatching(setOf("taskgraph/node-accepted")) {
                    it["graph_execution_id"]?.jsonPrimitive?.contentOrNull == execution &&
                        it["node_id"]?.jsonPrimitive?.contentOrNull == id
                } ?: error("依赖缺少持久验收：$id")
                check(accepted.data["node_version"]?.jsonPrimitive?.contentOrNull == plan.nodeVersion(node)) {
                    "依赖节点定义已变化：$id"
                }
                outputs[id]?.let { output ->
                    check(accepted.data["output_version"]?.jsonPrimitive?.contentOrNull == workflowVersion(output)) {
                        "依赖输出与验收版本不符：$id"
                    }
                }
                check(accepted.data["files"] == plan.fileVersions(workspaceRoot, plan.nodes.indexOf(node))) { "依赖文件版本已变化：$id" }
                check(accepted.data["dependency_versions"] == dependencyVersions(node.dependsOn)) { "依赖结果版本已变化：$id" }
                pending.addAll(node.dependsOn)
            }
        }
        val workflow = LocalWorkflowCoordinator(
            execute = { error("节点必须通过持久 Jobs 执行") }, pruneOutput = { pruneOutput(binding, it) },
            retrySafe = false,
            maxParallelism = binding.aggregateSnapshot().kernel.resources.maxAgents.coerceIn(1, 8),
            executeNode = { ctx, prompt ->
                checkOwner()
                checkDependencies(ctx.node.dependsOn, ctx.dependencyOutputs)
                val files = plan.fileVersions(workspaceRoot, ctx.index)
                check(plan.inputPaths(ctx.index).all { files[it]?.jsonPrimitive?.content != "missing" }) {
                    "节点输入文件缺失，不能继续：${ctx.node.id}"
                }
                val inputs = buildJsonObject { ctx.dependencyOutputs.forEach { (id, output) -> put(id, workflowVersion(output)) } }
                val nodeVersion = plan.nodeVersion(ctx.node)
                val identityFiles = if (plan.writable) JsonObject(files.filterKeys { it in plan.inputPaths(ctx.index) }) else files
                // Authorization may move to a new USER turn; the original node Job does not.
                val proposedJobId = workflowNodeJobId(execution, nodeVersion, identityFiles, inputs,
                    dependencyVersions(ctx.node.dependsOn))
                val fence = buildJsonObject {
                    put("graph_id", graphId); put("graph_execution_id", execution); put("goal_id", goalId)
                    put("goal_fingerprint", goalFingerprint); put("node_id", ctx.node.id)
                    put("source_message_id", source ?: JsonNull)
                    put("user_revision", authorization.getValue("user_revision"))
                    put("node_version", nodeVersion); put("files", files); put("dependencies", inputs)
                    put("dependency_versions", dependencyVersions(ctx.node.dependsOn))
                    put("output_paths", buildJsonArray { plan.outputPaths(ctx.index).forEach { add(JsonPrimitive(it)) } })
                }
                val admissions = mutableListOf<JsonObject>()
                log.forEachAfter(-1L) { event ->
                    if (event.type == "taskgraph/job-admitted" &&
                        event.data["graph_execution_id"]?.jsonPrimitive?.contentOrNull == execution &&
                        event.data["node_id"]?.jsonPrimitive?.contentOrNull == ctx.node.id
                    ) admissions += event.data
                }
                val jobId = workflowAdmittedJobId(plan, ctx.index, admissions, fence, proposedJobId)
                jobsByNode[ctx.node.id] = jobId
                persistentJobs.awaitWorkflowNode(jobId, prompt, plan.model, plan.route,
                    plan.value.getValue("max_steps").jsonPrimitive.int, plan.schema, plan.tools,
                    plan.writable, binding, fence)
            },
            acceptNode = { ctx, _ ->
                checkDependencies(ctx.node.dependsOn, ctx.dependencyOutputs)
                val jobId = jobsByNode.getValue(ctx.node.id)
                val actual = log.latestMatching(setOf("taskgraph/job-result")) {
                    it["job_id"]?.jsonPrimitive?.contentOrNull == jobId
                } ?: error("节点缺少持久结果证据")
                val before = actual.data["files"]?.jsonObject ?: error("节点缺少输入版本")
                val now = plan.fileVersions(workspaceRoot, ctx.index)
                val completedOutputs = actual.data["output_files"] as? JsonObject ?: error("缺少真实交付版本")
                check(plan.outputPaths(ctx.index).all { completedOutputs[it] == now[it] }) { "节点完成后的交付文件已变化，不能沿用旧结果" }
                val changedInputs = plan.inputPaths(ctx.index).any { before[it] != now[it] }
                check(!changedInputs) { "节点执行期间输入文件已变化，结果验收失效" }
                checkOwner()
                val missing = plan.outputPaths(ctx.index).filter { localWorkFileSha256(workspaceRoot, it) == null }
                TaskGraphAcceptance(missing.isEmpty(), "缺少真实文件证据：${missing.joinToString()}")
            },
            onResult = { result, dependencyOutputs ->
                checkOwner()
                val jobId = jobsByNode.getValue(result.node.id)
                val evidence = log.latestMatching(setOf("taskgraph/job-result")) {
                    it["job_id"]?.jsonPrimitive?.contentOrNull == jobId
                } ?: error("节点缺少实际持久结果")
                log.append("taskgraph/node-accepted", buildJsonObject {
                    put("graph_id", graphId); put("graph_execution_id", execution); put("goal_id", goalId)
                    put("goal_fingerprint", goalFingerprint); put("node_id", result.node.id)
                    put("node_version", plan.nodeVersion(result.node)); put("job_id", jobId)
                    put("result_sequence", evidence.sequence); put("output_version", workflowVersion(result.output.orEmpty()))
                    put("files", plan.fileVersions(workspaceRoot, result.index))
                    put("dependency_versions", dependencyVersions(result.node.dependsOn))
                    put("dependencies", buildJsonObject { dependencyOutputs.forEach { (id, output) -> put(id, workflowVersion(output)) } })
                    put("acceptance", if (plan.schema != null) "structured_output_and_declared_evidence" else "narrow_text_and_declared_files")
                })
            },
            onTerminal = { result ->
                checkOwner()
                log.append("taskgraph/node-terminal", buildJsonObject {
                    put("graph_id", graphId); put("graph_execution_id", execution); put("goal_id", goalId)
                    put("node_id", result.node.id); put("node_version", plan.nodeVersion(result.node))
                    put("outcome", result.outcome.name.lowercase()); put("error", result.error.orEmpty())
                    put("reused", result.attempts == 0 && result.succeeded)
                    jobsByNode[result.node.id]?.let { put("job_id", it) }
                })
            },
            onProgress = { progress ->
                checkOwner()
                log.append("taskgraph/progress", buildJsonObject {
                    put("graph_id", graphId); put("graph_execution_id", execution); put("goal_id", goalId)
                    put("node_id", progress.nodeId); put("stage", progress.stage)
                    put("completed", progress.completed); put("total", progress.total); put("detail", progress.detail)
                })
                binding.workState.update { current -> current.copy(workflowProgress = LocalWorkflowProgress(
                    binding.sessionId, progress.stage, progress.task, progress.completed, progress.total,
                    progress.detail.takeIf { progress.stage == "受阻" }, progress.stage == "受阻")) }
            },
        )
        try {
            checkOwner()
            require(plan.value["version"]?.jsonPrimitive?.intOrNull == 1) { "任务图持久定义版本不受支持" }
            val result = workflow.runDetailed(plan.prepared, plan.recoveredResults(log, execution, workspaceRoot))
            checkOwner()
            settle(result.executionOutcome)
            return result.summary
        } catch (waiting: LocalWorkflowSuspended) {
            return waiting.message.orEmpty() // Keep graph open; the next admitted root slice resumes it.
        } catch (superseded: LocalWorkflowSuperseded) {
            persistentJobs.cancelWorkflowJobs(binding.sessionId, execution, preserveSafeSlice = true)
            log.append("taskgraph/paused", buildJsonObject {
                put("graph_id", graphId); put("graph_execution_id", execution)
                put("user_revision", workflowUserRevision(log))
            })
            return "旧请求任务图已暂停：$graphId。新用户输入优先；可在新请求中显式恢复。"
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val userChanged = workflowAuthorizationSuperseded(log, authorization)
                persistentJobs.cancelWorkflowJobs(binding.sessionId, execution, preserveSafeSlice = userChanged)
                try {
                    if (userChanged) log.append("taskgraph/paused", buildJsonObject {
                        put("graph_id", graphId); put("graph_execution_id", execution)
                        put("user_revision", workflowUserRevision(log))
                    }) else settle("cancelled")
                } catch (failure: Exception) { cancelled.addSuppressed(failure) }
            }
            throw cancelled
        } catch (failure: Exception) {
            if (workflowAuthorizationSuperseded(log, authorization)) {
                persistentJobs.cancelWorkflowJobs(binding.sessionId, execution, preserveSafeSlice = true)
                log.append("taskgraph/paused", buildJsonObject {
                    put("graph_id", graphId); put("graph_execution_id", execution)
                    put("user_revision", workflowUserRevision(log))
                })
                return "旧请求任务图已暂停：$graphId。新用户输入优先；可在新请求中显式恢复。"
            }
            persistentJobs.cancelWorkflowJobs(binding.sessionId, execution)
            settle("failed", failure.message.orEmpty())
            return "任务图未完成：${failure.message.orEmpty()}"
        }
    }
}
