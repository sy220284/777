package com.labteto.dshmobile.local.work


import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Read-only evidence over existing execution facts and the goal's explicit acceptance contract. */
internal data class LocalWorkDeliveryEvidence(
    val needsArtifact: Boolean,
    val needsTest: Boolean,
    val successfulToolCallId: String? = null,
    val artifactCallId: String? = null,
    val testCallId: String? = null,
    val unmetRequirements: List<String>? = null,
    val runtimeFacts: LocalWorkGoalFacts? = null,
    val outstandingReviews: List<String> = emptyList(),
) {
    val missing: List<String> get() = (unmetRequirements ?: buildList {
        if (needsArtifact && artifactCallId == null) add("缺少本次目标的真实文件版本或可核验提交证据")
        if (needsTest && testCallId == null) add("缺少本次目标的实际测试/构建成功结果")
    }) + outstandingReviews
}

private fun JsonObject.value(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull


/**
 * Latest graph execution for this Goal is a Work-owned delivery precondition.
 * Graph success is not Goal success. An incomplete, cancelled, or blocked graph
 * cannot be laundered into a successful Goal by a model completion message.
 *
 * The latest graph may represent an explicit corrective rerun. Older sessions
 * with a generic 'settled' outcome retain progress-event compatibility.
 */
internal fun localWorkGraphDeliveryMissing(log: LocalSessionEventLog, goal: LocalGoal): List<String> {
    if (goal.id.isBlank()) return emptyList()
    val graphStart = log.latestMatching(setOf("taskgraph/start")) { data ->
        data.value("goal_id") == goal.id
    }?.takeIf { goal.startedAtSequence < 0L || it.sequence > goal.startedAtSequence }
        ?: return emptyList()
    val graphId = graphStart.data.value("graph_id")?.takeIf(String::isNotBlank)
        ?: return listOf("工作流启动记录缺少可信任务图身份")
    val graphExecutionId = graphStart.data.value("graph_execution_id")
    val graphFingerprint = graphStart.data.value("goal_fingerprint")
    val end = log.latestMatching(setOf("taskgraph/end")) { data ->
        data.value("graph_id") == graphId && data.value("goal_id") == goal.id &&
            data.value("graph_execution_id") == graphExecutionId &&
            (graphFingerprint == null || data.value("goal_fingerprint") == graphFingerprint)
    }?.takeIf { it.sequence > graphStart.sequence }
        ?: return listOf("当前目标的工作流已启动但尚未形成可核验的终态")
    val outcome = end.data.value("outcome")
    return when (outcome) {
        "nodes_completed" -> emptyList()
        // Earlier graphs were written with the untyped "settled" outcome.
        // Their durable node-progress events still reveal blocked descendants.
        "settled" -> {
            val blocked = log.latestMatching(setOf("taskgraph/progress")) { data ->
                data.value("graph_id") == graphId &&
                    data.value("graph_execution_id") == graphExecutionId &&
                    data.value("stage") == "受阻"
            }?.takeIf { it.sequence > graphStart.sequence }
            if (blocked != null) listOf("工作流包含受阻节点，历史结算缺少明确验收结论")
            else emptyList()
        }
        "partial", "blocked" -> listOf("工作流存在未完成或依赖受阻的子任务")
        "failed" -> listOf("工作流执行失败，需要核对工具执行结果")
        "cancelled" -> listOf("工作流已取消，不能视为完成")
        else -> listOf("工作流结算状态无法核实：${outcome ?: "missing"}")
    }
}

internal fun localWorkDeliveryEvidence(
    log: LocalSessionEventLog,
    goal: LocalGoal,
    workspaceRoot: File? = null,
    executingToolId: String? = null,
): LocalWorkDeliveryEvidence {
    val needsArtifact = goal.artifactPaths.isNotEmpty()
    val needsTest = goal.verificationCommands.isNotEmpty()
    val outstandingReviews = localWorkOutstandingRequiredReviews(log, goal)
    val facts = if (goal.id.isBlank()) null else
        LocalWorkRecoveryProjections.forLog(log).goalAssessment(log, goal)
    val graphMissing = buildList {
        addAll(localWorkGraphDeliveryMissing(log, goal))
        if (facts?.hasUnsettledGraphs == true) add("目标仍有关联工作流未结算")
        if (facts?.hasUnresolvedTools(executingToolId) == true) add("目标仍有关联工具副作用未核实")
    }
    if (goal.id.isBlank() && goal.status != "completed") return LocalWorkDeliveryEvidence(needsArtifact, needsTest, runtimeFacts = facts,
        outstandingReviews = outstandingReviews, unmetRequirements = listOf("旧目标尚未明确验收要求；请依据原任务重新声明成果与验收，不适用时显式为空，已完成进度保留"))
    if (!needsArtifact && !needsTest) return LocalWorkDeliveryEvidence(false, false, runtimeFacts = facts,
        outstandingReviews = outstandingReviews, unmetRequirements = graphMissing)
    val start = goal.startedAtSequence.takeIf { it >= 0L } ?: log.latestMatching(setOf("goal/state")) {
        it.value("id") == goal.id && goal.id.isNotBlank()
    }?.sequence ?: -1L
    if (start < 0) return LocalWorkDeliveryEvidence(needsArtifact, needsTest, runtimeFacts = facts,
        outstandingReviews = outstandingReviews, unmetRequirements = buildList {
            if (needsArtifact) add("缺少本次目标的真实文件版本或可核验提交证据")
            if (needsTest) add("缺少本次目标的实际测试/构建成功结果")
            addAll(graphMissing)
        })

    fun callFor(result: JsonObject, before: Long): LocalSessionEventLog.Event? {
        val id = result.value("id")?.takeIf(String::isNotBlank) ?: return null
        val executionId = result.value("execution_id")
        val admission = executionId?.let { identity ->
            log.latestMatching(setOf("tool/execution-started"), beforeSequenceExclusive = before) {
                it.value("execution_id") == identity && it.value("id") == id &&
                    (it["attributes"] as? JsonObject)?.value("goal_id") == goal.id
            }?.takeIf { it.sequence > start }
        }
        // A child worktree is a different file version. Its verifier cannot settle
        // the shared Goal, even when that Goal declares no artifact paths.
        if (!(admission?.data?.get("attributes") as? JsonObject)?.value("workspace_id").isNullOrBlank()) return null
        // Successful evidence must come from the real coordinator admission, not a model id.
        // Unstarted failures may invalidate a previous verifier, but can never prove success.
        if (admission == null && result.value("is_error") != "true") return null
        return log.latestMatching(setOf("tool/call"), beforeSequenceExclusive = admission?.sequence ?: before) {
            it.value("id") == id && it.value("name") == result.value("name") &&
                it.value("goal_id") == goal.id
        }?.takeIf { it.sequence > start }
    }
    fun version(data: JsonObject, path: String): String? =
        (data["file_versions"] as? JsonObject)?.value(path)
    val now = goal.artifactPaths.associateWith { path -> workspaceRoot?.let { localWorkFileSha256(it, path) } }
    val missing = graphMissing.toMutableList()
    var artifactId: String? = null
    for (path in goal.artifactPaths) {
        val recorded = log.latestMatching(setOf("tool/result")) { data ->
            data.value("is_error") == "false" && version(data, path) != null &&
                callFor(data, Long.MAX_VALUE) != null
        }?.takeIf { result ->
            result.sequence > start && callFor(result.data, result.sequence) != null
        }
        when {
            now[path] == null -> missing += "成果文件不存在或无法读取：$path；先确认成果已保存"
            recorded == null -> missing += "成果尚无当前目标下的真实工具版本证据：$path；先执行写入或读取验证"
            version(recorded.data, path) != now[path] ->
                missing += "成果版本已变化：$path；重新核验最新内容后再结算"
            else -> artifactId = recorded.data.value("id")
        }
    }
    fun commandMatches(args: JsonObject, declared: String): Boolean = when (val command = args["command"]) {
        is JsonPrimitive -> command.contentOrNull?.trim() == declared.trim()
        is kotlinx.serialization.json.JsonArray -> try {
            kotlinx.serialization.json.Json.parseToJsonElement(declared) == command
        } catch (_: Exception) { false }
        else -> false
    }
    var verificationId: String? = null
    for (command in goal.verificationCommands) {
        // Match the actual invocation inside the predicate. A newer cat/echo result cannot
        // hide a real verifier; the newest invocation of this verifier (including failure) wins.
        val result = log.latestMatching(setOf("tool/result")) { data ->
            val call = callFor(data, Long.MAX_VALUE) ?: return@latestMatching false
            val args = call.data["arguments"] as? JsonObject
            args?.let { commandMatches(it, command) } == true
        }?.takeIf { it.sequence > start && callFor(it.data, it.sequence) != null }
        val exitCode = (result?.data?.get("process_exit_code") as? JsonPrimitive)?.intOrNull
        val issue = when {
            result == null -> "验收命令尚无当前目标下的真实执行记录：$command；请单独执行原命令"
            result.data.value("is_error") != "false" ->
                "验收命令最新执行失败：$command；退出码：${exitCode?.toString() ?: "未返回"}"
            exitCode == null -> "验收命令没有可核验的退出码：$command；请检查实际执行结果"
            exitCode != 0 -> "验收命令最新执行退出码为 $exitCode：$command"
            goal.artifactPaths.any { now[it] == null } ->
                "验收后成果文件不存在或无法读取：$command；检查声明的成果路径"
            goal.artifactPaths.any { version(result.data, it) != now[it] } ->
                "验收后成果版本已变化：$command；重新执行原命令验证当前文件"
            else -> null
        }
        if (issue != null) missing += issue
        else verificationId = result?.data?.value("id")
    }
    return LocalWorkDeliveryEvidence(needsArtifact, needsTest, runtimeFacts = facts,
        outstandingReviews = outstandingReviews,
        artifactCallId = artifactId.takeIf { missing.none { it.startsWith("成果") } },
        testCallId = verificationId.takeIf { missing.none { it.startsWith("验收") } },
        unmetRequirements = missing)
}
