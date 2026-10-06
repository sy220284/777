package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatReplySuggestion
import com.labteto.dshmobile.local.chat.LOCAL_CHAT_DOMAIN_STATE_EVENT_TYPE
import com.labteto.dshmobile.local.chat.LocalChatBranchNode
import com.labteto.dshmobile.local.chat.LocalChatBranchState
import com.labteto.dshmobile.local.chat.LocalChatDurableState
import com.labteto.dshmobile.local.chat.encodeChatBranchStateEvent
import com.labteto.dshmobile.local.chat.encodeChatDomainStateEvent
import com.labteto.dshmobile.local.chat.upsertChatBranchNode
import com.labteto.dshmobile.local.chat.projectChatSessionControls
import com.labteto.dshmobile.local.chat.chatBranches
import com.labteto.dshmobile.local.chat.replySuggestions
import com.labteto.dshmobile.local.chat.withChatSessionDomain
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.projectionReplayCursor
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalTodoItem
import com.labteto.dshmobile.local.work.projectWorkSessionControls
import com.labteto.dshmobile.local.work.goal
import com.labteto.dshmobile.local.work.todos
import com.labteto.dshmobile.local.work.withWorkSessionDomain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalFeatureSessionControlProjectionTest {
    @Test
    fun approvedPlanReplaysPlanAndModeAsOneFact() {
        val snapshot = LocalHarnessSession(id = "s1", plan = listOf("旧计划"), planMode = true)
        val projected = projectWorkSessionControls(snapshot, listOf(
            event(1L, "plan/approved", buildJsonObject {
                put("items", buildJsonArray { add(JsonPrimitive("批准计划")) })
                put("active", false)
            }),
        ))
        assertEquals(listOf("批准计划"), projected.plan)
        assertFalse(projected.planMode)
    }

    @Test
    fun replaysOnlyEventsNewerThanSnapshotCursor() {
        val snapshot = LocalHarnessSession(
            id = "s1",
            plan = listOf("快照计划"),
            planMode = true,
            controlProjectedThroughSequence = 5L,
        ).withWorkSessionDomain(
            todos = listOf(LocalTodoItem("快照任务", "pending")),
            goal = LocalGoal("快照目标"),
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

        val projected = projectWorkSessionControls(snapshot, events.filter { it.sequence > 5L })

        assertEquals(listOf("检查事实源", "回放增量"), projected.plan)
        assertEquals(listOf(LocalTodoItem("补回归测试", "in_progress")), projected.todos)
        assertEquals(LocalGoal("收口会话状态", "blocked", "等待完整投影"), projected.goal)
        assertFalse(projected.planMode)
    }

    @Test
    fun replaysLatestChatBranchState() {
        val user = LocalHarnessMessage("u1", "user", "原消息", createdAt = 1L)
        val oldState = LocalChatBranchState()
        val newState = upsertChatBranchNode(
            oldState,
            LocalChatBranchNode(message = user),
            select = true,
        )
        val snapshot = LocalHarnessSession(
            id = "branch",
            controlProjectedThroughSequence = 3L,
        ).withChatSessionDomain(chatBranches = oldState)
        val branchEvent = event(
            4L,
            "chat/branch-state",
            encodeChatBranchStateEvent(newState),
        )

        val projected = projectChatSessionControls(
            snapshot = snapshot,
            events = listOf(branchEvent).filter { it.sequence > 3L },
        )

        assertEquals(newState, projected.chatBranches)
    }

    @Test
    fun chatDomainEventRecoversWhenMaterializedSnapshotLags() {
        val snapshot = LocalHarnessSession(
            id = "chat-domain",
            personaId = "old-persona",
            galleryId = "old-gallery",
            handoffSummary = "旧摘要",
            controlProjectedThroughSequence = 8L,
        ).withChatSessionDomain(
            replySuggestions = listOf(ChatReplySuggestion("旧", "旧建议")),
        )
        val durable = LocalChatDurableState(
            personaId = "new-persona",
            galleryId = "new-gallery",
            galleryStoryId = "story-2",
            gallerySaveSuppressedThrough = 42L,
            chatState = ChatCharacterState(mood = "稳定"),
            replySuggestions = listOf(ChatReplySuggestion("新", "新建议")),
            handoffSummary = null,
        )
        val projected = projectChatSessionControls(
            snapshot = snapshot,
            events = listOf(event(
                9L,
                LOCAL_CHAT_DOMAIN_STATE_EVENT_TYPE,
                encodeChatDomainStateEvent(durable, "test"),
            )).filter { it.sequence > 8L },
        )

        assertEquals("new-persona", projected.personaId)
        assertEquals("new-gallery", projected.galleryId)
        assertEquals("story-2", projected.galleryStoryId)
        assertEquals(42L, projected.gallerySaveSuppressedThrough)
        assertEquals(durable.chatState, projected.chatState)
        assertEquals(durable.replySuggestions, projected.replySuggestions)
        assertEquals(null, projected.handoffSummary)
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
            controlProjectedThroughSequence = 3L,
        ).withWorkSessionDomain(goal = LocalGoal("现有目标"))
        val malformed = event(4L, "plan/state", buildJsonObject {
            put("items", "错误类型")
        })

        val projected = projectWorkSessionControls(
            snapshot = snapshot,
            events = listOf(malformed).filter { it.sequence > 3L },
        )

        assertEquals(snapshot.plan, projected.plan)
        assertEquals(snapshot.goal, projected.goal)
    }

    @Test
    fun partiallyMalformedStateEventIsIgnoredInsteadOfPartiallyApplied() {
        val snapshot = LocalHarnessSession(
            id = "strict",
            plan = listOf("可信计划"),
            controlProjectedThroughSequence = 10L,
        ).withWorkSessionDomain(todos = listOf(LocalTodoItem("可信任务", "pending")))
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

        val projected = projectWorkSessionControls(snapshot, events.filter { it.sequence > 10L })

        assertEquals(snapshot.plan, projected.plan)
        assertEquals(snapshot.todos, projected.todos)
    }

    @Test
    fun explicitEmptyStateArraysStillClearPlanAndTodos() {
        val snapshot = LocalHarnessSession(
            id = "clear",
            plan = listOf("旧计划"),
            controlProjectedThroughSequence = 20L,
        ).withWorkSessionDomain(todos = listOf(LocalTodoItem("旧任务", "completed")))
        val events = listOf(
            event(21L, "plan/state", buildJsonObject { put("items", buildJsonArray { }) }),
            event(22L, "todo/state", buildJsonObject { put("items", buildJsonArray { }) }),
        )

        val projected = projectWorkSessionControls(snapshot, events.filter { it.sequence > 20L })

        assertEquals(emptyList<String>(), projected.plan)
        assertEquals(emptyList<LocalTodoItem>(), projected.todos)
    }

    @Test
    fun missingTailKeepsMaterializedSnapshot() {
        val snapshot = LocalHarnessSession(
            id = "s2",
            plan = listOf("保留计划"),
            planMode = false,
            controlProjectedThroughSequence = 12L,
        ).withWorkSessionDomain(
            todos = listOf(LocalTodoItem("保留任务", "completed")),
            goal = LocalGoal("保留目标", "completed"),
        )

        val projected = projectWorkSessionControls(snapshot, emptyList())

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
