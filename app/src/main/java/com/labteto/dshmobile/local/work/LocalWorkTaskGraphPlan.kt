package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.workflow.TaskGraphNode
import com.labteto.dshmobile.harness.workflow.TaskGraphNodeResult
import com.labteto.dshmobile.harness.workflow.TaskGraphOutcome
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.boolean

internal class LocalWorkflowSuspended : IllegalStateException("节点检查点已保存，等待下一根任务执行片")
internal class LocalWorkflowSuperseded : IllegalStateException("新用户请求已接管，旧任务图等待显式恢复")

internal fun workflowVersion(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** EventLog owns the immutable definition and full results. No second store or scheduler. */
internal data class LocalWorkTaskGraphPlan(val value: JsonObject) {
    val version: String get() = workflowVersion(value.toString())
    val nodes: List<TaskGraphNode> get() = value.getValue("nodes").jsonArray.map { raw ->
        val node = raw.jsonObject
        TaskGraphNode(node.getValue("node_id").jsonPrimitive.content,
            node.getValue("task").jsonPrimitive.content,
            node.getValue("depends_on").jsonArray.map { it.jsonPrimitive.content })
    }
    val prepared: LocalPreparedWorkflow get() = LocalPreparedWorkflow(nodes,
        value.getValue("evidence").jsonArray.map { it.jsonPrimitive.content },
        value.getValue("pipeline").jsonPrimitive.boolean)
    val model: String get() = value.getValue("model").jsonPrimitive.content
    val route: String get() = value.getValue("route").jsonPrimitive.content
    val writable: Boolean get() = value.getValue("allow_mutation").jsonPrimitive.boolean
    val schema: JsonObject? get() = value["output_schema"] as? JsonObject
    val tools: Set<String>? get() = (value["allowed_tools"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
    fun inputPaths(index: Int): List<String> = value.getValue("input_files").jsonArray.map { it.jsonPrimitive.content } +
        (value["node_input_files"] as? JsonArray)?.getOrNull(index)?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
    fun outputPaths(index: Int): List<String> = value.getValue("required_files").jsonArray.map { it.jsonPrimitive.content } +
        (value["node_output_files"] as? JsonArray)?.getOrNull(index)?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
    fun fileVersions(root: File, index: Int): JsonObject = buildJsonObject {
        (inputPaths(index) + outputPaths(index)).distinct().forEach { put(it, localWorkFileSha256(root, it) ?: "missing") }
    }
    fun nodeVersion(node: TaskGraphNode): String = workflowVersion("$version:${node.id}")

    // FEATURE_TREE: {"id":"work.taskgraph.durable-acceptance","path":"第一层｜产品功能与界面 / 工作与智能体功能 / 子智能体 / 持久任务图与节点验收（未合并）","entry":"recoveredResults","owner":"WORK","test":"app/src/test/java/com/labteto/dshmobile/local/work/LocalWorkTaskGraphPlanTest.kt#coldRestoreReusesCompletedNodeAndContinuesRemainingNode"}
    fun recoveredResults(log: LocalSessionEventLog, executionId: String, root: File): Map<String, TaskGraphNodeResult> {
        val (facts, resultEvents) = LocalWorkRecoveryProjections.forLog(log).acceptedFor(log, executionId)
        val immutableNodes = nodes
        val currentFiles = immutableNodes.indices.associateWith { fileVersions(root, it) }
        val results = linkedMapOf<String, TaskGraphNodeResult>()
        // Fixed point validates dependency versions, including forward-declared DAG edges.
        for (pass in immutableNodes.indices) {
            val before = results.size
            immutableNodes.forEachIndexed { index, node ->
                if (node.id in results || node.dependsOn.any { it !in results }) return@forEachIndexed
                val fact = facts[node.id] ?: return@forEachIndexed
                if (fact["node_version"]?.jsonPrimitive?.contentOrNull != nodeVersion(node) ||
                    fact["files"] != currentFiles[index]) return@forEachIndexed
                val deps = buildJsonObject { node.dependsOn.forEach { put(it, workflowVersion(results.getValue(it).output.orEmpty())) } }
                if (fact["dependencies"] != deps) return@forEachIndexed
                val depVersions = buildJsonObject { node.dependsOn.forEach {
                    put(it, facts.getValue(it).getValue("result_sequence"))
                } }
                if (fact["dependency_versions"] != depVersions) return@forEachIndexed
                val resultRef = fact["result_sequence"]?.jsonPrimitive?.longOrNull ?: return@forEachIndexed
                val actual = resultEvents[resultRef] ?: return@forEachIndexed
                if (actual["job_id"] != fact["job_id"]) return@forEachIndexed
                if (actual["node_version"]?.jsonPrimitive?.contentOrNull != nodeVersion(node) ||
                    actual["goal_fingerprint"] != value["goal_fingerprint"] ||
                    actual["tool_execution_ids"] !is JsonArray) return@forEachIndexed
                val before = actual["files"] as? JsonObject ?: return@forEachIndexed
                if (inputPaths(index).any { before[it] != currentFiles.getValue(index)[it] }) return@forEachIndexed
                val delivered = actual["output_files"] as? JsonObject ?: return@forEachIndexed
                if (outputPaths(index).any { path ->
                        delivered[path] != currentFiles.getValue(index)[path] ||
                            currentFiles.getValue(index)[path]?.jsonPrimitive?.content == "missing"
                    }) return@forEachIndexed
                val output = actual["output"]?.jsonPrimitive?.contentOrNull
                val text = output ?: return@forEachIndexed
                if (text.isBlank() || workflowVersion(text) != fact["output_version"]?.jsonPrimitive?.contentOrNull) return@forEachIndexed
                results[node.id] = TaskGraphNodeResult(node, index, TaskGraphOutcome.COMPLETED, text, attempts = 0)
            }
            if (results.size == before) break
        }
        return results
    }
}

/** Only USER inputs supersede authorization; Agent messages and automatic continuations do not. */
internal fun workflowUserRevision(log: LocalSessionEventLog): Long = maxOf(
    log.latestMatching(setOf("user/message")) { true }?.sequence ?: -1L,
    log.latestMatching(setOf(LOCAL_AGENT_INBOX_EVENT_TYPE)) { data ->
        data["action"]?.jsonPrimitive?.contentOrNull in setOf("queued", "edited", "removed") &&
            (data["affected"] as? JsonArray).orEmpty().any { item ->
                (item as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull.let { it == null || it == "USER" }
            }
    }?.sequence ?: -1L,
)

internal fun workflowAuthorizationSuperseded(log: LocalSessionEventLog, authorization: JsonObject): Boolean {
    val source = authorization["source_message_id"]
    return authorization["user_revision"]?.jsonPrimitive?.longOrNull != workflowUserRevision(log) ||
        source == null || source is kotlinx.serialization.json.JsonNull ||
        log.latestMatching(setOf("turn/start")) { true }?.data?.get("source_message_id") != source
}

/** Stable execution identity excludes the replaceable USER authorization epoch. */
internal fun workflowNodeJobId(
    executionId: String, nodeVersion: String, identityFiles: JsonObject,
    dependencies: JsonObject, dependencyVersions: JsonObject,
): String = "job-graph-" + workflowVersion(
    "$executionId:$nodeVersion:$identityFiles:$dependencies:$dependencyVersions",
).take(32)

/** Match read-only versions to their original Jobs; writable nodes must retain the latest admission. */
internal fun workflowAdmittedJobId(
    plan: LocalWorkTaskGraphPlan, index: Int, admissions: List<JsonObject>, fence: JsonObject, proposedId: String,
): String {
    val currentFiles = fence["files"] as? JsonObject ?: error("节点缺少文件身份")
    val paths = if (plan.writable) plan.inputPaths(index) else plan.inputPaths(index) + plan.outputPaths(index)
    val candidates = if (plan.writable) admissions.takeLast(1) else admissions
    val matching = candidates.asReversed().firstOrNull { admission ->
        val oldFiles = admission["files"] as? JsonObject
        oldFiles != null && paths.all { oldFiles[it] == currentFiles[it] } &&
            admission["graph_execution_id"] == fence["graph_execution_id"] &&
            admission["node_id"] == fence["node_id"] &&
            admission["node_version"] == fence["node_version"] &&
            admission["goal_fingerprint"] == fence["goal_fingerprint"] &&
            admission["dependencies"] == fence["dependencies"] &&
            (admission["dependency_versions"] == null || admission["dependency_versions"] == fence["dependency_versions"]) &&
            admission["output_paths"] == fence["output_paths"]
    }
    check(!plan.writable || admissions.isEmpty() || matching != null) {
        "可写节点原 Job 身份或输入证据已变化，禁止重放"
    }
    return matching?.get("job_id")?.jsonPrimitive?.contentOrNull
        ?: if (matching != null) error("原节点 Job ID 缺失") else proposedId
}

internal fun workflowWritableResumeNeedsVerification(allowMutation: Boolean, status: String): Boolean =
    allowMutation && status != "waiting_slice"

internal fun workflowCanCreateMissingJob(allowMutation: Boolean, admitted: Boolean, knownRejected: Boolean): Boolean =
    !allowMutation || !admitted || knownRejected

/** Reuse the canonical settlement reducer; a model's final text cannot settle an unknown tool effect. */
private fun workflowToolAttempts(log: LocalSessionEventLog, jobId: String): List<com.labteto.dshmobile.harness.session.ToolAttemptLedger.Attempt> =
    LocalWorkRecoveryProjections.forLog(log).toolAttempts(log, jobId)

internal fun workflowToolSettlementEvidence(log: LocalSessionEventLog, jobId: String): List<String> {
    val attempts = workflowToolAttempts(log, jobId)
    check(attempts.none { it.unresolvedExternalEffect }) { "节点仍有未结算或未知工具副作用，不能验收或自动重放" }
    return attempts.filter { it.started && it.settled }.mapNotNull { it.executionId }
}

/** A write receipt only permits continuation when its tool result is inside a newer persisted history checkpoint. */
internal fun workflowToolContinuationSafe(log: LocalSessionEventLog, jobId: String): Boolean {
    val attempts = workflowToolAttempts(log, jobId)
    if (attempts.any { it.unresolvedExternalEffect }) return false
    val writes = attempts.filter { it.started && !it.verifiedReadOnly }
    if (writes.isEmpty()) return true
    val projection = LocalWorkRecoveryProjections.forLog(log)
    val checkpoint = projection.historyCheckpoint(log, jobId) ?: return false
    if (decodeLocalSubagentHistoryCheckpoint(checkpoint.data) == null) return false
    return writes.all { attempt ->
        val executionId = attempt.executionId
        attempt.settled && executionId != null &&
            projection.toolResult(log, jobId, executionId, attempt.id)
                ?.sequence?.let { it < checkpoint.sequence } == true
    }
}

/** Ephemeral carrier for the original graph fence through approval and resource waits. */
internal class LocalWorkflowExecutionFence(val requireCurrent: () -> Unit) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<LocalWorkflowExecutionFence>
}
