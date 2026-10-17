package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkProgressCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val logs = mutableListOf<LocalSessionEventLog>()

    @After fun closeLogs() = logs.forEach(LocalSessionEventLog::close)

    private fun eventLog(file: File) = LocalSessionEventLog(file, Json).also(logs::add)

    @Test
    fun pausedAndReactivatedGoalCannotReviveItsOldDurableContinuation() {
        val state = MutableStateFlow(LocalWorkRunState(sessionId = "revision"))
        val log = eventLog(File(temporary.root, "revision.jsonl"))
        val commands = LocalWorkProgressCoordinator(localWorkRunStatePort(state), log) { }
        commands.createGoal("完成任务")
        val original = requireNotNull(state.value.work.goal)
        val queued = LocalWorkResumeCommand("run", log.latestSequence(), WORK_GOAL_PROGRESS_CONTINUATION,
            1, "source", goalId = original.id, goalRevision = original.revision).toQueueInput()
        val replayed = requireNotNull(com.labteto.dshmobile.local.agent.decodeLocalAgentInboxPending(
            com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent("queued", listOf(queued)))).single()
        assertEquals(original.revision, replayed.continuation?.goalRevision)
        assertTrue(isCurrentWorkGoalContinuation(replayed.continuation, state.value.work))
        commands.updateGoal("paused", null)
        commands.updateGoal("active", null)
        assertEquals(original.revision + 2L, state.value.work.goal?.revision)
        org.junit.Assert.assertFalse(isCurrentWorkGoalContinuation(replayed.continuation, state.value.work))
        val before = state.value.work.goal
        commands.updateGoal("active", null)
        assertEquals(before, state.value.work.goal)
    }

    @Test
    fun backgroundProgressStaysInCapturedSessionAndPersistsAfterItsEvent() {
        val visible = MutableStateFlow(LocalWorkRunState(sessionId = "visible"))
        val run = MutableStateFlow(LocalWorkRunState(sessionId = "background"))
        val log = eventLog(File(temporary.root, "background.jsonl"))
        var writes = 0
        val coordinator = LocalWorkProgressCoordinator(localWorkRunStatePort(run), log) {
            assertEquals("background", run.value.sessionId)
            assertEquals("goal/state", log.snapshot().last().type)
            writes++
        }

        coordinator.createGoal("  完成任务  ")
        visible.value = visible.value.copy(sessionId = "another-visible")
        coordinator.updateGoal("blocked", "等待依赖")

        assertNull(visible.value.work.goal)
        assertEquals("完成任务", run.value.work.goal?.description)
        assertEquals("blocked", run.value.work.goal?.status)
        assertEquals(2, writes)
        assertEquals("blocked", log.snapshot().last().data["status"]?.toString()?.trim('"'))
    }

    @Test
    fun wholeListReplacementRejectsOversizedOrMalformedPayloadWithoutLosingProgress() {
        val state = MutableStateFlow(LocalWorkRunState(sessionId = "test"))
        val log = eventLog(File(temporary.root, "progress.jsonl"))
        var writes = 0
        val coordinator = LocalWorkProgressCoordinator(localWorkRunStatePort(state), log) { writes++ }
        coordinator.updatePlan(buildJsonObject {
            put("items", JsonArray(listOf(JsonPrimitive("继续验证"))))
        })
        coordinator.updateTodos(buildJsonObject {
            put("items", JsonArray(listOf(buildJsonObject {
                put("content", "设备回归")
                put("status", "pending")
            })))
        })
        val before = state.value.work
        val beforeEvents = log.snapshot().size

        val invalidWrites = listOf<() -> Unit>(
            { coordinator.updatePlan(buildJsonObject {}) },
            { coordinator.updatePlan(buildJsonObject { put("items", JsonPrimitive("not-array")) }) },
            { coordinator.updatePlan(buildJsonObject { put("plan", 42) }) },
            { coordinator.updatePlan(buildJsonObject { put("plan", false) }) },
            { coordinator.updatePlan(buildJsonObject { put("plan", JsonNull) }) },
            { coordinator.updatePlan(buildJsonObject { put("plan", buildJsonObject {}) }) },
            { coordinator.updatePlan(buildJsonObject { put("plan", JsonArray(emptyList())) }) },
            { coordinator.updatePlan(buildJsonObject {
                put("items", JsonArray(listOf(JsonPrimitive("合法步骤"), JsonPrimitive(" \n\t"))))
            }) },
            { coordinator.updatePlan(buildJsonObject {
                put("items", JsonArray(listOf(JsonPrimitive("合法步骤"), JsonPrimitive(42))))
            }) },
            { coordinator.updatePlan(buildJsonObject {
                put("items", JsonArray(List(MAX_WORK_PLAN_ITEMS + 1) { JsonPrimitive("步骤 $it") }))
            }) },
            { coordinator.updateTodos(buildJsonObject {}) },
            { coordinator.updateTodos(buildJsonObject { put("items", JsonPrimitive("not-array")) }) },
            { coordinator.updateTodos(buildJsonObject {
                put("items", JsonArray(listOf(buildJsonObject {
                    put("content", "正常")
                    put("status", "pending")
                }, buildJsonObject {
                    put("content", "不支持")
                    put("status", "invalid")
                })))
            }) },
            { coordinator.updateTodos(buildJsonObject {
                put("items", JsonArray(listOf(buildJsonObject {
                    put("content", "x".repeat(MAX_WORK_TODO_CHARS + 1))
                    put("status", "pending")
                })))
            }) },
            { coordinator.updateTodos(buildJsonObject {
                put("items", JsonArray(List(MAX_WORK_TODO_ITEMS + 1) {
                    buildJsonObject { put("content", "任务 $it"); put("status", "pending") }
                }))
            }) },
        )
        invalidWrites.forEach { invalid ->
            val result = runCatching(invalid)
            assertTrue("无效的全量替换必须拒绝而不能部分提交", result.isFailure)
            assertEquals(before, state.value.work)
            assertEquals(beforeEvents, log.snapshot().size)
            assertEquals(2, writes)
        }
        // No missing-payload shortcut: an empty list is an explicit user/model choice.
        coordinator.updatePlan(buildJsonObject { put("plan", "一\n\n二") })
        assertEquals(listOf("一", "二"), state.value.work.plan)
        coordinator.updateTodos(buildJsonObject { put("items", JsonArray(emptyList())) })
        assertTrue(state.value.work.todos.isEmpty())
        assertEquals(listOf("plan/state", "todo/state", "plan/state", "todo/state"), log.snapshot().map { it.type })
        assertEquals(4, writes)
    }

    @Test
    fun bothPlanInputsPreserveContentAndOnlyExplicitEmptyInputClears() {
        val state = MutableStateFlow(LocalWorkRunState(sessionId = "plan-inputs"))
        val log = eventLog(File(temporary.root, "plan-inputs.jsonl"))
        val coordinator = LocalWorkProgressCoordinator(localWorkRunStatePort(state), log) {}
        val steps = listOf("  验证第一步  ", "x".repeat(2000))
        coordinator.updatePlan(buildJsonObject {
            put("items", JsonArray(steps.map(::JsonPrimitive)))
        })
        assertEquals(steps, state.value.work.plan)
        assertEquals(steps, projectWorkSessionControls(LocalWorkState(), log.snapshot()).plan)
        coordinator.updatePlan(buildJsonObject { put("items", JsonArray(emptyList())) })
        assertTrue(state.value.work.plan.isEmpty())
        coordinator.updatePlan(buildJsonObject { put("plan", "重新验证\n\n保留下一步") })
        assertEquals(listOf("重新验证", "保留下一步"), state.value.work.plan)
        coordinator.updatePlan(buildJsonObject { put("plan", "") })
        assertTrue(state.value.work.plan.isEmpty())
        assertTrue(projectWorkSessionControls(LocalWorkState(), log.snapshot()).plan.isEmpty())
    }

    @Test
    fun fullSizedValidReplacementIsRecoverableWithoutSilentClipping() {
        val state = MutableStateFlow(LocalWorkRunState(sessionId = "valid-bounds"))
        val log = eventLog(File(temporary.root, "valid-bounds.jsonl"))
        val coordinator = LocalWorkProgressCoordinator(localWorkRunStatePort(state), log) {}
        val plan = (1..MAX_WORK_PLAN_ITEMS).map { "第 $it 步" }
        val tasks = (1..MAX_WORK_TODO_ITEMS).map { "待办 $it" }
        coordinator.updatePlan(buildJsonObject {
            put("items", JsonArray(plan.map(::JsonPrimitive)))
        })
        coordinator.updateTodos(buildJsonObject {
            put("items", JsonArray(tasks.map { content ->
                buildJsonObject { put("content", content); put("status", "pending") }
            }))
        })
        assertEquals(plan, state.value.work.plan)
        assertEquals(tasks, state.value.work.todos.map(LocalTodoItem::content))
        assertEquals(tasks, projectWorkSessionControls(LocalWorkState(), log.snapshot()).todos.map(LocalTodoItem::content))
    }

    @Test
    fun invalidOrMissingGoalDoesNotWriteAnEventOrPersist() {
        val state = MutableStateFlow(LocalWorkRunState(sessionId = "test"))
        val log = eventLog(File(temporary.root, "goal.jsonl"))
        var writes = 0
        val coordinator = LocalWorkProgressCoordinator(localWorkRunStatePort(state), log) { writes++ }
        assertTrue(runCatching { coordinator.updateGoal("active", null) }.isFailure)
        assertTrue(runCatching { coordinator.updateGoal("invalid", null) }.isFailure)
        assertNull(state.value.work.goal)
        assertTrue(log.snapshot().isEmpty())
        assertEquals(0, writes)

        coordinator.createGoal("g".repeat(3000))
        coordinator.updateGoal("completed", "n".repeat(3000))
        assertEquals(2000, state.value.work.goal?.description?.length)
        assertEquals(2000, state.value.work.goal?.note?.length)
        coordinator.updateGoal("paused", null)
        assertNull(state.value.work.goal?.note)
    }

    @Test
    fun goalCompletionRequiresRuntimeTodosToBeClosed() {
        val state = MutableStateFlow(
            LocalWorkRunState(
                sessionId = "goal-transition",
                work = LocalWorkState(
                    goal = LocalGoal("收口共享底座", id = "goal-transition"),
                    todos = listOf(
                        LocalTodoItem("补回归", "pending"),
                        LocalTodoItem("已完成项", "completed"),
                    ),
                ),
            ),
        )
        val log = eventLog(File(temporary.root, "goal-transition.jsonl"))
        var writes = 0
        val coordinator = LocalWorkProgressCoordinator(localWorkRunStatePort(state), log) { writes++ }

        val rejected = runCatching { coordinator.updateGoal("completed", null) }

        assertTrue(rejected.isFailure)
        assertEquals("active", state.value.work.goal?.status)
        assertEquals(0, writes)
        assertEquals("goal/transition-rejected", log.snapshot().last().type)

        state.value = state.value.copy(
            work = state.value.work.copy(
                todos = state.value.work.todos.map { it.copy(status = "completed") },
            ),
        )
        coordinator.updateGoal("completed", "验证通过")

        assertEquals("completed", state.value.work.goal?.status)
        assertEquals(1, writes)
        assertEquals("goal/state", log.snapshot().last().type)
    }

    @Test
    fun legacyGoalCannotCompleteUntilAcceptanceIsDeclaredAndProgressIsRetained() {
        val todos = listOf(LocalTodoItem("已完成项", "completed"))
        val state = MutableStateFlow(LocalWorkRunState(sessionId = "legacy-goal",
            work = LocalWorkState(goal = LocalGoal("核对进度"), todos = todos)))
        val log = eventLog(File(temporary.root, "legacy-goal.jsonl"))
        val coordinator = LocalWorkProgressCoordinator(localWorkRunStatePort(state), log) { }
        assertTrue(runCatching { coordinator.updateGoal("completed", null) }.isFailure)
        assertEquals("active", state.value.work.goal?.status)
        coordinator.createGoal("核对进度", emptyList(), emptyList())
        assertEquals(todos, state.value.work.todos)
        coordinator.updateGoal("completed", null)
        assertEquals("completed", state.value.work.goal?.status)
        assertEquals(todos, state.value.work.todos)
    }

    @Test
    fun eventWriteFailureLeavesAllWorkProgressAndPersistenceUnchanged() {
        val initial = LocalWorkRunState(
            sessionId = "failure",
            work = LocalWorkState(
                plan = listOf("原计划"),
                todos = listOf(LocalTodoItem("原任务", "completed")),
                goal = LocalGoal("原目标"),
            ),
        )
        val state = MutableStateFlow(initial)
        val parent = temporary.newFolder("not-a-directory")
        val log = eventLog(File(parent, "goal.jsonl"))
        com.labteto.dshmobile.local.blockEventLogWritesAfterOpen(File(parent, "goal.jsonl"))
        var persisted = false
        val coordinator = LocalWorkProgressCoordinator(localWorkRunStatePort(state), log) { persisted = true }
        val mutations = listOf<() -> Unit>(
            { coordinator.updatePlan(buildJsonObject { put("plan", "新计划") }) },
            { coordinator.updateTodos(buildJsonObject {}) },
            { coordinator.createGoal("新目标") },
            { coordinator.updateGoal("blocked", "依赖失败") },
        )
        mutations.forEach { mutation ->
            assertTrue(runCatching(mutation).isFailure)
            assertEquals(initial, state.value)
            assertEquals(false, persisted)
        }
    }
}
