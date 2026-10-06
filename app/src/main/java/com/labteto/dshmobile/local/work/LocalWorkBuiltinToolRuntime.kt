package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.tools.string
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Work-owned execution for task-state built-ins.
 *
 * The active Work binding is the only mutable owner for plan/todo/goal/question state during a run.
 * Shared plugin routing may invoke this adapter, but Engine must not interpret these Work tools.
 */
internal class LocalWorkBuiltinToolRuntime(
    private val persist: (LocalWorkRunBinding) -> Unit,
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
            "create_goal" -> progress.createGoal(args.string("description"))
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
