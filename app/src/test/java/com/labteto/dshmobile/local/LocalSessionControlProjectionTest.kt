package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalSessionControlProjectionTest {
    @Test
    fun replaysOnlyEventsNewerThanSnapshotCursor() {
        val snapshot = LocalHarnessSession(
            id = "s1",
            plan = listOf("快照计划"),
            todos = listOf(LocalTodoItem("快照任务", "pending")),
            goal = LocalGoal("快照目标"),
            planMode = true,
            controlProjectedThroughSequence = 5L,
        )
        val events = listOf(
            event(4L, "plan/state", buildJsonObject {
                put("items", buildJsonArray { add(JsonPrimitive("过期计划")) })
            }),
            event(6L, "plan/state", buildJsonObject {
                put("items", buildJsonArray {
                    add(JsonPrimitive("检查事实源"))
                    add(JsonPrimitive("回放增量"))
                })
            }),
            event(7L, "todo/state", buildJsonObject {
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("content", "补回归测试")
                        put("status", "in_progress")
                    })
                })
            }),
            event(8L, "goal/state", buildJsonObject {
                put("description", "收口会话状态")
                put("status", "blocked")
                put("note", "等待完整投影")
            }),
            event(9L, "plan/mode", buildJsonObject { put("active", false) }),
        )

        val projected = projectSessionControlTail(snapshot, events, sequenceExclusive = 5L)

        assertEquals(listOf("检查事实源", "回放增量"), projected.plan)
        assertEquals(listOf(LocalTodoItem("补回归测试", "in_progress")), projected.todos)
        assertEquals(LocalGoal("收口会话状态", "blocked", "等待完整投影"), projected.goal)
        assertFalse(projected.planMode)
    }

    @Test
    fun legacyPersistedSnapshotStartsFromCurrentTailInsteadOfReplayingIncompleteHistory() {
        val legacy = LocalHarnessSession(id = "legacy", planMode = false, controlProjectedThroughSequence = null)

        assertEquals(
            42L,
            projectionReplayCursor(
                snapshot = legacy,
                persistedSnapshotExists = true,
                legacyBaselineSequence = 42L,
            ),
        )
        assertEquals(
            -1L,
            projectionReplayCursor(
                snapshot = legacy,
                persistedSnapshotExists = false,
                legacyBaselineSequence = null,
            ),
        )
    }

    @Test
    fun malformedTailEventDoesNotDestroySnapshotProjection() {
        val snapshot = LocalHarnessSession(
            id = "safe",
            plan = listOf("现有计划"),
            goal = LocalGoal("现有目标"),
            controlProjectedThroughSequence = 3L,
        )
        val malformed = event(4L, "plan/state", buildJsonObject {
            put("items", "错误类型")
        })

        val projected = projectSessionControlTail(
            snapshot = snapshot,
            events = listOf(malformed),
            sequenceExclusive = 3L,
        )

        assertEquals(snapshot.plan, projected.plan)
        assertEquals(snapshot.goal, projected.goal)
    }

    @Test
    fun partiallyMalformedStateEventIsIgnoredInsteadOfPartiallyApplied() {
        val snapshot = LocalHarnessSession(
            id = "strict",
            plan = listOf("可信计划"),
            todos = listOf(LocalTodoItem("可信任务", "pending")),
            controlProjectedThroughSequence = 10L,
        )
        val events = listOf(
            event(11L, "plan/state", buildJsonObject {
                put("items", buildJsonArray {
                    add(JsonPrimitive("新计划"))
                    add(JsonPrimitive(123))
                })
            }),
            event(12L, "todo/state", buildJsonObject {
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("content", "新任务")
                        put("status", "in_progress")
                    })
                    add(buildJsonObject {
                        put("content", "")
                        put("status", "pending")
                    })
                })
            }),
        )

        val projected = projectSessionControlTail(snapshot, events, sequenceExclusive = 10L)

        assertEquals(snapshot.plan, projected.plan)
        assertEquals(snapshot.todos, projected.todos)
    }

    @Test
    fun explicitEmptyStateArraysStillClearPlanAndTodos() {
        val snapshot = LocalHarnessSession(
            id = "clear",
            plan = listOf("旧计划"),
            todos = listOf(LocalTodoItem("旧任务", "completed")),
            controlProjectedThroughSequence = 20L,
        )
        val events = listOf(
            event(21L, "plan/state", buildJsonObject { put("items", buildJsonArray { }) }),
            event(22L, "todo/state", buildJsonObject { put("items", buildJsonArray { }) }),
        )

        val projected = projectSessionControlTail(snapshot, events, sequenceExclusive = 20L)

        assertEquals(emptyList<String>(), projected.plan)
        assertEquals(emptyList<LocalTodoItem>(), projected.todos)
    }

    @Test
    fun missingTailKeepsMaterializedSnapshot() {
        val snapshot = LocalHarnessSession(
            id = "s2",
            plan = listOf("保留计划"),
            todos = listOf(LocalTodoItem("保留任务", "completed")),
            goal = LocalGoal("保留目标", "completed"),
            planMode = false,
            controlProjectedThroughSequence = 12L,
        )

        val projected = projectSessionControlTail(snapshot, emptyList(), sequenceExclusive = 12L)

        assertEquals(snapshot.plan, projected.plan)
        assertEquals(snapshot.todos, projected.todos)
        assertEquals(snapshot.goal, projected.goal)
        assertEquals(snapshot.planMode, projected.planMode)
    }

    private fun event(
        sequence: Long,
        type: String,
        data: kotlinx.serialization.json.JsonObject,
    ) = LocalSessionEventLog.Event(
        sequence = sequence,
        type = type,
        createdAt = 1L,
        data = data,
    )
}
