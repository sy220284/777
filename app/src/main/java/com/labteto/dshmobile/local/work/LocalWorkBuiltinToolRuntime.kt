package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.tools.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Work-owned execution for task-state built-ins.
 *
 * The active Work binding is the only mutable owner for plan/todo/goal/question state during a run.
 * Shared plugin routing may invoke this adapter, but the Shared Tool layer must not interpret Work semantics.
 */
internal class LocalWorkBuiltinToolRuntime(
    private val persist: (LocalWorkRunBinding) -> Unit,
    private val workspaceRoot: java.io.File? = null,
    private val updateContextMetrics: (LocalWorkRunBinding) -> Unit,
) {
    internal suspend fun execute(
        call: LocalToolCall,
        binding: LocalWorkRunBinding?,
    ): String? {
        if (call.name !in TOOL_NAMES) return null
        val run = binding
            ?: return "Work 工具缺少活动运行上下文，已拒绝旧的无绑定执行路径"
        val args = call.arguments
        val progress = LocalWorkProgressCoordinator(
            state = run.workState,
            eventLog = run.eventLog,
            persist = { persist(run) },
            workspaceRoot = workspaceRoot,
        )
        return when (call.name) {
            "update_plan" -> progress.updatePlan(args)
            "exit_plan_mode" -> exitWorkPlanMode(
                call = call,
                plan = args.string("plan"),
                state = run.workState,
                interactions = run.interactions,
                aggregateSnapshot = run::aggregateSnapshot,
                history = run.runHandle.modelHistory,
                eventLog = run.eventLog,
                persist = { persist(run) },
                updateContextMetrics = { updateContextMetrics(run) },
            )
            "todo_write" -> progress.updateTodos(args)
            "create_goal" -> {
                val (artifactPaths, verificationCommands) = parseWorkGoalAcceptanceArguments(args)
                progress.createGoal(args.string("description"), artifactPaths, verificationCommands)
            }
            "get_goal" -> progress.getGoal()
            "update_goal" -> progress.updateGoal(
                status = args.string("status"),
                note = args["note"]?.jsonPrimitive?.contentOrNull,
            )
            "ask_user_question" -> run.interactions.awaitQuestion(
                LocalQuestion(
                    callId = call.id,
                    question = args.string("question").take(2_000),
                    options = args["options"]
                        ?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        .orEmpty()
                        .take(6),
                ),
            )
            else -> error("未覆盖的 Work builtin：${call.name}")
        }
    }

    internal companion object {
        val TOOL_NAMES: Set<String> = setOf(
            "update_plan",
            "exit_plan_mode",
            "todo_write",
            "create_goal",
            "get_goal",
            "update_goal",
            "ask_user_question",
        )
    }
}

/**
 * The model tool contract requires both acceptance arrays, even when explicitly empty.
 * Absence is NOT equivalent to "the user requested no artifact or verification": otherwise
 * update_goal can accept a false completion claim without its intended evidence.
 */
internal fun parseWorkGoalAcceptanceArguments(args: JsonObject): Pair<List<String>, List<String>> {
    fun requiredStringArray(key: String): List<String> {
        val array = args[key] as? JsonArray
            ?: error("create_goal.$key 必须显式提供字符串数组；无需此类验收时请传 []，目标未创建")
        return array.mapIndexed { index, element ->
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }
                ?: error("create_goal.$key 第 ${index + 1} 项必须为字符串；目标未创建")
            value.content
        }
    }
    return requiredStringArray("artifact_paths") to requiredStringArray("verification_commands")
}
