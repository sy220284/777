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
) {
    val missing: List<String> get() = unmetRequirements ?: buildList {
        if (needsArtifact && artifactCallId == null) add("缺少本次目标的真实文件版本或可核验提交证据")
        if (needsTest && testCallId == null) add("缺少本次目标的实际测试/构建成功结果")
    }
}

private fun JsonObject.value(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun localWorkDeliveryEvidence(
    log: LocalSessionEventLog,
    goal: LocalGoal,
    workspaceRoot: File? = null,
): LocalWorkDeliveryEvidence {
    val needsArtifact = goal.artifactPaths.isNotEmpty()
    val needsTest = goal.verificationCommands.isNotEmpty()
    if (goal.id.isBlank() && goal.status != "completed") return LocalWorkDeliveryEvidence(needsArtifact, needsTest,
        unmetRequirements = listOf("旧目标尚未明确验收要求；请依据原任务重新声明成果与验收，不适用时显式为空，已完成进度保留"))
    if (!needsArtifact && !needsTest) return LocalWorkDeliveryEvidence(false, false)
    val start = goal.startedAtSequence.takeIf { it >= 0L } ?: log.latestMatching(setOf("goal/state")) {
        it.value("id") == goal.id && goal.id.isNotBlank()
    }?.sequence ?: -1L
    if (start < 0) return LocalWorkDeliveryEvidence(needsArtifact, needsTest)

    fun callFor(result: JsonObject, before: Long): LocalSessionEventLog.Event? {
        val id = result.value("id")?.takeIf(String::isNotBlank) ?: return null
        val executionId = result.value("execution_id")
        val admission = executionId?.let { identity ->
            log.latestMatching(setOf("tool/execution-started"), beforeSequenceExclusive = before) {
                it.value("execution_id") == identity && it.value("id") == id &&
                    (it["attributes"] as? JsonObject)?.value("goal_id") == goal.id
            }?.takeIf { it.sequence > start }
        }
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
    val missing = mutableListOf<String>()
    var artifactId: String? = null
    for (path in goal.artifactPaths) {
        val result = log.latestMatching(setOf("tool/result")) { data ->
            data.value("is_error") == "false" && version(data, path) != null &&
                callFor(data, Long.MAX_VALUE) != null
        }?.takeIf { result ->
            result.sequence > start && callFor(result.data, result.sequence) != null &&
                now[path] != null && version(result.data, path) == now[path]
        }
        if (result == null) missing += "成果尚未核验或版本已变化：$path"
        else artifactId = result.data.value("id")
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
        val success = result != null && result.data.value("is_error") == "false" &&
            (result.data["process_exit_code"] as? JsonPrimitive)?.intOrNull == 0 &&
            goal.artifactPaths.all { path -> now[path] != null && version(result.data, path) == now[path] }
        if (!success) missing += "验收命令未成功执行、最新执行失败或成果版本已变化：$command"
        else verificationId = result?.data?.value("id")
    }
    return LocalWorkDeliveryEvidence(needsArtifact, needsTest,
        artifactCallId = artifactId.takeIf { missing.none { it.startsWith("成果") } },
        testCallId = verificationId.takeIf { missing.none { it.startsWith("验收") } },
        unmetRequirements = missing)
}
