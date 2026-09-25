package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal data class LocalSessionControlProjection(
    val plan: List<String>,
    val todos: List<LocalTodoItem>,
    val goal: LocalGoal?,
    val planMode: Boolean,
    val chatBranches: LocalChatBranchState,
)

/**
 * Fold durable control-state events newer than the materialized session snapshot.
 *
 * The snapshot remains a restart accelerator. Once a state transition has a matching event, the
 * event log is authoritative and can repair a snapshot that was not written before process loss.
 */
internal fun projectionReplayCursor(
    snapshot: LocalHarnessSession,
    persistedSnapshotExists: Boolean,
    legacyBaselineSequence: Long?,
): Long = snapshot.controlProjectedThroughSequence
    ?: when {
        !persistedSnapshotExists -> -1L
        legacyBaselineSequence != null -> legacyBaselineSequence
        else -> error("旧会话缺少投影迁移基线")
    }

internal fun projectSessionControlTail(
    snapshot: LocalHarnessSession,
    events: List<LocalSessionEventLog.Event>,
    sequenceExclusive: Long,
): LocalSessionControlProjection {
    var plan = snapshot.plan
    var todos = snapshot.todos
    var goal = snapshot.goal
    var planMode = snapshot.planMode
    var chatBranches = snapshot.chatBranches

    events
        .asSequence()
        .filter { event -> event.sequence > sequenceExclusive }
        .sortedBy { event -> event.sequence }
        .forEach { event ->
            when (event.type) {
                "plan/state" -> decodePlanState(event.data)?.let { decoded -> plan = decoded }
                "todo/state" -> decodeTodoState(event.data)?.let { decoded -> todos = decoded }
                "goal/state" -> {
                    val description = (event.data["description"] as? JsonPrimitive)?.contentOrNull
                    val status = (event.data["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (!description.isNullOrBlank() && status in setOf("active", "paused", "completed", "blocked")) {
                        goal = LocalGoal(
                            description = description.take(2_000),
                            status = status,
                            note = (event.data["note"] as? JsonPrimitive)?.contentOrNull?.take(2_000),
                        )
                    }
                }
                "plan/mode" -> {
                    (event.data["active"] as? JsonPrimitive)?.booleanOrNull?.let { active ->
                        planMode = active
                    }
                }
                "chat/branch-state" -> {
                    decodeChatBranchStateEvent(event.data)?.let { decoded ->
                        chatBranches = decoded
                    }
                }
            }
        }

    return LocalSessionControlProjection(
        plan = plan,
        todos = todos,
        goal = goal,
        planMode = planMode,
        chatBranches = chatBranches,
    )
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
