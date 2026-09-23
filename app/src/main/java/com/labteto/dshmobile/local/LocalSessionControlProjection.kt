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

    events
        .asSequence()
        .filter { event -> event.sequence > sequenceExclusive }
        .sortedBy { event -> event.sequence }
        .forEach { event ->
            when (event.type) {
                "plan/state" -> {
                    plan = (event.data["items"] as? JsonArray)
                        ?.mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull }
                        ?.take(20)
                        ?: plan
                }
                "todo/state" -> {
                    val allowed = setOf("pending", "in_progress", "completed")
                    todos = (event.data["items"] as? JsonArray)
                        ?.mapNotNull { element ->
                            val item = element as? JsonObject ?: return@mapNotNull null
                            val content = (item["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                            val status = (item["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            if (content.isEmpty() || status !in allowed) null
                            else LocalTodoItem(content.take(500), status)
                        }
                        ?.take(50)
                        ?: todos
                }
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
            }
        }

    return LocalSessionControlProjection(
        plan = plan,
        todos = todos,
        goal = goal,
        planMode = planMode,
    )
}
