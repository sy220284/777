package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.state.RuntimeStateTransition
import com.labteto.dshmobile.harness.state.RuntimeStateTransitionPolicy
import com.labteto.dshmobile.harness.state.acceptRuntimeStateTransition
import com.labteto.dshmobile.harness.state.rejectRuntimeStateTransition
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun resolveWorkGoalTransition(
    current: LocalGoal,
    candidate: LocalGoal,
    todos: List<LocalTodoItem>,
): RuntimeStateTransition<LocalGoal> =
    RuntimeStateTransitionPolicy<LocalGoal> { runtimeOwned, proposed ->
        val openTodos = todos.count { it.status == "pending" || it.status == "in_progress" }
        if (proposed.status == "completed" && openTodos > 0) {
            rejectRuntimeStateTransition(
                runtimeOwned,
                "仍有 $openTodos 项任务未完成，先更新任务清单后再标记目标完成。",
            )
        } else {
            acceptRuntimeStateTransition(proposed)
        }
    }.resolve(current, candidate)


internal fun resolveWorkGoalUpdate(
    snapshot: LocalWorkState,
    status: String,
    note: String?,
    eventLog: LocalSessionEventLog,
): LocalGoal {
    val current = snapshot.goal ?: error("当前会话没有目标")
    val candidate = current.copy(status = status, note = note?.take(2_000))
    val transition = resolveWorkGoalTransition(current, candidate, snapshot.todos)
    if (!transition.accepted) {
        eventLog.append("goal/transition-rejected", buildJsonObject {
            put("from_status", current.status)
            put("to_status", status)
            put("open_todos", snapshot.todos.count {
                it.status == "pending" || it.status == "in_progress"
            })
            transition.reason?.let { put("reason", it) }
        })
        error(transition.reason ?: "目标状态变更被运行时拒绝")
    }
    return transition.value
}
