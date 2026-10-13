package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.tools.optionalString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Plan, todo and goal mutations for one captured session; never resolves the visible session. */
internal class LocalWorkProgressCoordinator(
    private val state: LocalWorkStatePort,
    private val eventLog: LocalSessionEventLog,
    private val workspaceRoot: java.io.File? = null,
    private val persist: () -> Unit,
) {
    fun updatePlan(
        args: JsonObject,
    ): String {
        val items = args["items"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: args.optionalString("plan")?.lines()?.filter { it.isNotBlank() }
            ?: emptyList()
        val normalized = items.take(20)
        eventLog.append("plan/state", buildJsonObject {
            put("items", JsonArray(normalized.map { item -> JsonPrimitive(item) }))
        })
        state.update { it.copy(plan = normalized) }
        persist()
        return if (normalized.isEmpty()) "计划已清空" else "计划已更新，共 ${normalized.size} 项"
    }

    fun updateTodos(
        args: JsonObject,
    ): String {
        val allowed = setOf("pending", "in_progress", "completed")
        val items = args["items"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val content = item["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val status = item["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (content.isEmpty() || status !in allowed) null else LocalTodoItem(content.take(500), status)
        }.take(50)
        eventLog.append("todo/state", buildJsonObject {
            put("items", JsonArray(items.map { item ->
                buildJsonObject {
                    put("content", item.content)
                    put("status", item.status)
                }
            }))
        })
        state.update { it.copy(todos = items) }
        persist()
        return if (items.isEmpty()) "任务清单已清空" else "任务清单已更新，共 ${items.size} 项"
    }

    fun createGoal(
        description: String,
        artifactPaths: List<String> = emptyList(),
        verificationCommands: List<String> = emptyList(),
    ): String {
        require(description.isNotBlank()) { "目标不能为空" }
        val goal = LocalGoal(description.trim().take(2_000), id = java.util.UUID.randomUUID().toString(),
            artifactPaths = artifactPaths.distinct().onEach { path ->
                require(path.isNotBlank() && !path.startsWith("/") && !path.contains('\\') &&
                    !path.contains(':') && path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                    "验收成果必须是工作区相对文件路径"
                }
            }, verificationCommands = verificationCommands.map(String::trim).distinct().onEach {
                require(it.isNotBlank()) { "验收命令不能为空" }
            })
        val event = eventLog.append("goal/state", workGoalEvent(goal))
        state.update { it.copy(goal = goal.copy(startedAtSequence = event.sequence)) }
        persist()
        return "目标已创建：${goal.description}\n成果：${goal.artifactPaths.joinToString()}\n验收命令：${goal.verificationCommands.joinToString()}"
    }

    fun getGoal(): String {
        val goal = state.snapshot().goal ?: return "当前会话没有目标"
        return "目标：[${goal.status}] ${goal.description}${goal.note?.let { "\n说明：$it" }.orEmpty()}\n成果：${goal.artifactPaths.joinToString()}\n验收命令：${goal.verificationCommands.joinToString()}"
    }

    fun updateGoal(
        status: String,
        note: String?,
    ): String {
        require(status in setOf("active", "paused", "completed", "blocked")) { "目标状态无效" }
        val updated = resolveWorkGoalUpdate(state.snapshot(), status, note, eventLog, workspaceRoot)
        val delivery = if (status == "completed") localWorkDeliveryEvidence(eventLog, updated, workspaceRoot) else null
        eventLog.append("goal/state", buildJsonObject {
            workGoalEvent(updated).forEach { (key, value) -> put(key, value) }
            put("description", updated.description)
            put("status", updated.status)
            updated.note?.let { put("note", it) }
            delivery?.successfulToolCallId?.let { put("executed_tool_call_id", it) }
            delivery?.artifactCallId?.let { put("artifact_tool_call_id", it) }
            delivery?.testCallId?.let { put("verification_tool_call_id", it) }
        })
        state.update { it.copy(goal = updated) }
        persist()
        return "目标状态已更新为 $status"
    }
}

internal fun workGoalEvent(goal: LocalGoal): JsonObject = buildJsonObject {
    put("description", goal.description); put("status", goal.status)
    goal.note?.let { put("note", it) }
    put("id", goal.id); put("startedAtSequence", goal.startedAtSequence)
    put("artifactPaths", JsonArray(goal.artifactPaths.map(::JsonPrimitive)))
    put("verificationCommands", JsonArray(goal.verificationCommands.map(::JsonPrimitive)))
}
