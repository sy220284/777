package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The persisted full-list replacement contract is shared with the replay projection. */
internal const val MAX_WORK_PLAN_ITEMS = 20
internal const val MAX_WORK_TODO_ITEMS = 50
internal const val MAX_WORK_TODO_CHARS = 500

/** Plan, todo and goal mutations for one captured session; never resolves the visible session. */
internal class LocalWorkProgressCoordinator(
    private val state: LocalWorkStatePort,
    private val eventLog: LocalSessionEventLog,
    private val workspaceRoot: java.io.File? = null,
    private val executingToolId: String? = null,
    private val persist: () -> Unit,
) {
    /** One Work owner transaction: log, projection and persist cannot interleave across ports. */
    private fun <T> commitWorkMutation(action: () -> T): T = eventLog.withWriterLease {
        synchronized(state) { action() }
    }

    fun updatePlan(
        args: JsonObject,
    ): String = commitWorkMutation {
        // A replacement must be intentional. Missing or malformed payloads cannot erase a
        // durable Work plan; explicit items=[] or plan="" still clears it.
        val items = when {
            args.containsKey("items") -> {
                val array = args["items"] as? JsonArray ?: error("update_plan.items 必须是字符串数组；未修改原计划")
                array.mapIndexed { index, element ->
                    val item = (element as? JsonPrimitive)?.takeIf { it.isString }
                        ?: error("计划第 ${index + 1} 项必须是字符串；未修改原计划")
                    require(item.content.isNotBlank()) {
                        "计划第 ${index + 1} 项不能为空白；未修改原计划"
                    }
                    item.content
                }
            }
            args.containsKey("plan") -> {
                val legacy = (args["plan"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("update_plan.plan 必须是字符串；未修改原计划")
                legacy.lines().filter(String::isNotBlank)
            }
            else -> error("update_plan 缺少 items 或 plan；清空计划请显式传入 items=[]")
        }
        require(items.size <= MAX_WORK_PLAN_ITEMS) {
            "计划包含 ${items.size} 项，超过当前 ${MAX_WORK_PLAN_ITEMS} 项的持久化上限；未修改原计划，请合并步骤后重试"
        }
        val normalized = items
        eventLog.append("plan/state", buildJsonObject {
            put("items", JsonArray(normalized.map { item -> JsonPrimitive(item) }))
        })
        state.update { it.copy(plan = normalized) }
        persist()
        if (normalized.isEmpty()) "计划已清空" else "计划已更新，共 ${normalized.size} 项"
    }

    fun updateTodos(
        args: JsonObject,
    ): String = commitWorkMutation {
        // todo_write replaces the *whole* list. Silently dropping one pending task can
        // make the authoritative goal-completion projection report false success.
        val raw = args["items"] as? JsonArray
            ?: error("todo_write 缺少有效 items 数组；清空任务请显式传入 items=[]")
        require(raw.size <= MAX_WORK_TODO_ITEMS) {
            "任务清单包含 ${raw.size} 项，超过当前 ${MAX_WORK_TODO_ITEMS} 项的持久化上限；未修改原任务，请合并条目后重试"
        }
        val allowed = setOf("pending", "in_progress", "completed")
        val items = raw.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: error("任务清单第 ${index + 1} 项不是对象；未修改原任务")
            val contentValue = (item["content"] as? JsonPrimitive)?.takeIf { it.isString }
            val statusValue = (item["status"] as? JsonPrimitive)?.takeIf { it.isString }
            require(contentValue != null && statusValue != null) {
                "任务清单第 ${index + 1} 项缺少有效 content/status 字符串；未修改原任务"
            }
            val content = contentValue.content.trim()
            val status = statusValue.content
            require(content.isNotEmpty() && content.length <= MAX_WORK_TODO_CHARS && status in allowed) {
                "任务清单第 ${index + 1} 项无效（任务内容须为 1–${MAX_WORK_TODO_CHARS} 字符；状态须为 pending/in_progress/completed）；未修改原任务"
            }
            LocalTodoItem(content, status)
        }
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
        if (items.isEmpty()) "任务清单已清空" else "任务清单已更新，共 ${items.size} 项"
    }

    fun createGoal(
        description: String,
        artifactPaths: List<String> = emptyList(),
        verificationCommands: List<String> = emptyList(),
    ): String = commitWorkMutation {
        require(description.isNotBlank()) { "目标不能为空" }
        val goal = LocalGoal(description.trim().take(2_000), id = java.util.UUID.randomUUID().toString(),
            revision = 1L,
            artifactPaths = artifactPaths.distinct().onEach { path ->
                require(path.isNotBlank() && !path.startsWith("/") && !path.contains('\\') &&
                    !path.contains(':') && path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                    "验收成果必须是工作区相对文件路径"
                }
            }, verificationCommands = verificationCommands.map(String::trim).distinct().onEach {
                require(it.isNotBlank()) { "验收命令不能为空" }
            })
        val folders = workspaceRoot?.let { ensureLocalWorkTaskFolders(it, goal.description, goal.id) }
        val event = eventLog.append("goal/state", workGoalEvent(goal))
        state.update { it.copy(goal = goal.copy(startedAtSequence = event.sequence)) }
        persist()
        buildString {
            append("目标已创建：${goal.description}\n成果：${goal.artifactPaths.joinToString()}\n验收命令：${goal.verificationCommands.joinToString()}")
            folders?.let {
                append("\n任务文件目录：${it.work}")
                append("\n交付成果目录：${it.deliverables}")
                append("\n新增工作资料统一保存在任务文件目录；最终成果保存在交付成果目录。")
                append("已明确声明的成果验收路径保持不变。")
            }
        }
    }

    fun getGoal(): String {
        val goal = state.snapshot().goal ?: return "当前会话没有目标"
        return "目标：[${goal.status}] ${goal.description}${goal.note?.let { "\n说明：$it" }.orEmpty()}\n成果：${goal.artifactPaths.joinToString()}\n验收命令：${goal.verificationCommands.joinToString()}"
    }

    fun updateGoal(
        status: String,
        note: String?,
    ): String = commitWorkMutation {
        require(status in setOf("active", "paused", "completed", "blocked")) { "目标状态无效" }
        val updated = resolveWorkGoalUpdate(state.snapshot(), status, note, eventLog, workspaceRoot, executingToolId)
        val delivery = if (status == "completed") localWorkDeliveryEvidence(eventLog, updated, workspaceRoot, executingToolId) else null
        eventLog.append("goal/state", buildJsonObject {
            workGoalEvent(updated).forEach { (key, value) -> put(key, value) }
            put("description", updated.description)
            put("status", updated.status)
            updated.note?.let { put("note", it) }
            executingToolId?.let { put("origin_execution_id", it) }
            delivery?.successfulToolCallId?.let { put("executed_tool_call_id", it) }
            delivery?.artifactCallId?.let { put("artifact_tool_call_id", it) }
            delivery?.testCallId?.let { put("verification_tool_call_id", it) }
        })
        state.update { it.copy(goal = updated) }
        persist()
        "目标状态已更新为 $status"
    }

    /** Uses the same Work command and verifier as explicit updates; never writes a second Goal. */
    fun completeVerifiedGoal(expected: LocalGoal): Boolean = commitWorkMutation {
        if (state.snapshot().goal != expected || expected.status != "active") return@commitWorkMutation false
        if (expected.artifactPaths.isEmpty() && expected.verificationCommands.isEmpty()) return@commitWorkMutation false
        updateGoal("completed", expected.note)
        true
    }
}

internal fun workGoalEvent(goal: LocalGoal): JsonObject = buildJsonObject {
    put("description", goal.description); put("status", goal.status)
    goal.note?.let { put("note", it) }
    put("id", goal.id); put("startedAtSequence", goal.startedAtSequence)
    put("revision", goal.revision)
    put("artifactPaths", JsonArray(goal.artifactPaths.map(::JsonPrimitive)))
    put("verificationCommands", JsonArray(goal.verificationCommands.map(::JsonPrimitive)))
}
