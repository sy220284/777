package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalGoal
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalTodoItem
import com.labteto.dshmobile.local.optionalString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
    private val state: MutableStateFlow<LocalHarnessState>,
    private val eventLog: LocalSessionEventLog,
    private val persist: () -> Unit,
) {
    fun updatePlan(
        args: JsonObject,
    ): String {
        val items = args["items"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: args.optionalString("plan")?.lines()?.filter { it.isNotBlank() }
            ?: emptyList()
        val normalized = items.take(20)
        state.update { it.copy(work = it.work.copy(plan = normalized)) }
        eventLog.append("plan/state", buildJsonObject {
            put("items", JsonArray(normalized.map { item -> JsonPrimitive(item) }))
        })
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
        state.update { it.copy(work = it.work.copy(todos = items)) }
        eventLog.append("todo/state", buildJsonObject {
            put("items", JsonArray(items.map { item ->
                buildJsonObject {
                    put("content", item.content)
                    put("status", item.status)
                }
            }))
        })
        persist()
        return if (items.isEmpty()) "任务清单已清空" else "任务清单已更新，共 ${items.size} 项"
    }

    fun createGoal(
        description: String,
    ): String {
        val goal = LocalGoal(description.trim().take(2_000))
        state.update { it.copy(work = it.work.copy(goal = goal)) }
        eventLog.append("goal/state", buildJsonObject {
            put("description", goal.description)
            put("status", goal.status)
            goal.note?.let { put("note", it) }
        })
        persist()
        return "目标已创建：${goal.description}"
    }

    fun getGoal(): String {
        val goal = state.value.work.goal ?: return "当前会话没有目标"
        return "目标：[${goal.status}] ${goal.description}${goal.note?.let { "\n说明：$it" }.orEmpty()}"
    }

    fun updateGoal(
        status: String,
        note: String?,
    ): String {
        require(status in setOf("active", "paused", "completed", "blocked")) { "目标状态无效" }
        val updated = resolveWorkGoalUpdate(state.value.work, status, note, eventLog)
        state.update { it.copy(work = it.work.copy(goal = updated)) }
        eventLog.append("goal/state", buildJsonObject {
            put("description", updated.description)
            put("status", updated.status)
            updated.note?.let { put("note", it) }
        })
        persist()
        return "目标状态已更新为 $status"
    }
}
