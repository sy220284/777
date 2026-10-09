package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Work alone interprets plan, todo and goal events, including atomic plan approval. */
internal fun projectWorkSessionControls(
    snapshot: LocalHarnessSession,
    events: List<LocalSessionEventLog.Event>,
): LocalWorkState = projectWorkSessionControls(
    LocalWorkState(
        plan = snapshot.plan,
        todos = snapshot.todos,
        goal = snapshot.goal,
        planMode = snapshot.planMode,
    ),
    events,
)

/** Replay controls from a clean point in the Work timeline before editing a historical instruction. */
internal fun projectWorkSessionControls(
    baseline: LocalWorkState,
    events: List<LocalSessionEventLog.Event>,
): LocalWorkState {
    var plan = baseline.plan
    var todos = baseline.todos
    var goal = baseline.goal
    var planMode = baseline.planMode
    events.forEach { event ->
        when (event.type) {
                "work/active-transcript" -> {
                    plan = decodePlanState(buildJsonObject {
                        put("items", event.data["plan"] ?: JsonArray(emptyList()))
                    }) ?: emptyList()
                    todos = decodeTodoState(buildJsonObject {
                        put("items", event.data["todos"] ?: JsonArray(emptyList()))
                    }) ?: emptyList()
                    val recordedGoal = event.data["goal"] as? JsonObject
                    goal = recordedGoal?.let { fields ->
                        val description = (fields["description"] as? JsonPrimitive)?.contentOrNull
                        val status = (fields["status"] as? JsonPrimitive)?.contentOrNull
                        if (description.isNullOrBlank() || status.isNullOrBlank()) null
                        else LocalGoal(description, status, (fields["note"] as? JsonPrimitive)?.contentOrNull)
                    }
                    planMode = (event.data["plan_mode"] as? JsonPrimitive)?.booleanOrNull ?: planMode
                }
                "plan/approved" -> decodePlanState(event.data)?.let {
                    plan = it
                    planMode = false
                }
                "plan/state" -> decodePlanState(event.data)?.let { plan = it }
                "todo/state" -> decodeTodoState(event.data)?.let { todos = it }
                "goal/state" -> {
                    val description = (event.data["description"] as? JsonPrimitive)?.contentOrNull
                    val status = (event.data["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (
                        !description.isNullOrBlank() &&
                        status in setOf("active", "paused", "completed", "blocked")
                    ) {
                        goal = LocalGoal(
                            description = description.take(2_000),
                            status = status,
                            note = (event.data["note"] as? JsonPrimitive)?.contentOrNull?.take(2_000),
                        )
                    }
                }
                "plan/mode" -> {
                    (event.data["active"] as? JsonPrimitive)?.booleanOrNull?.let { planMode = it }
                }

        }
    }
    return LocalWorkState(plan = plan, todos = todos, goal = goal, planMode = planMode)
}

private fun decodePlanState(data: JsonObject): List<String>? {
    val items = data["items"] as? JsonArray ?: return null
    val decoded = mutableListOf<String>()
    for (element in items) {
        val item = element as? JsonPrimitive ?: return null
        if (!item.isString) return null
        decoded += item.contentOrNull ?: return null
    }
    return decoded.take(20)
}

private fun decodeTodoState(data: JsonObject): List<LocalTodoItem>? {
    val items = data["items"] as? JsonArray ?: return null
    val allowed = setOf("pending", "in_progress", "completed")
    val decoded = mutableListOf<LocalTodoItem>()
    for (element in items) {
        val item = element as? JsonObject ?: return null
        val contentValue = item["content"] as? JsonPrimitive ?: return null
        val statusValue = item["status"] as? JsonPrimitive ?: return null
        if (!contentValue.isString || !statusValue.isString) return null
        val content = contentValue.contentOrNull?.trim().orEmpty()
        val status = statusValue.contentOrNull.orEmpty()
        if (content.isEmpty() || status !in allowed) return null
        decoded += LocalTodoItem(content.take(500), status)
    }
    return decoded.take(50)
}
