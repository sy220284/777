package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
    fun oversizedPlanAndTodosRetainExistingBoundsAndWholeListReplacement() {
        val state = MutableStateFlow(LocalWorkRunState(sessionId = "test"))
        val log = eventLog(File(temporary.root, "progress.jsonl"))
        val coordinator = LocalWorkProgressCoordinator(localWorkRunStatePort(state), log) {}
        coordinator.updatePlan(buildJsonObject {
            put("items", JsonArray(List(1000) { JsonPrimitive("步骤 $it") }))
        })
        coordinator.updateTodos(buildJsonObject {
            put("items", JsonArray(List(1000) {
                buildJsonObject {
                    put("content", "x".repeat(1000))
                    put("status", if (it % 2 == 0) "pending" else "invalid")
                }
            }))
        })
        assertEquals(20, state.value.work.plan.size)
        assertEquals(50, state.value.work.todos.size)
        assertTrue(state.value.work.todos.all { it.content.length == 500 && it.status == "pending" })

        coordinator.updatePlan(buildJsonObject { put("plan", "一\n\n二") })
        assertEquals(listOf("一", "二"), state.value.work.plan)
        coordinator.updateTodos(buildJsonObject {})
        assertTrue(state.value.work.todos.isEmpty())
        assertEquals(listOf("plan/state", "todo/state", "plan/state", "todo/state"), log.snapshot().map { it.type })
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
                    goal = LocalGoal("收口共享底座"),
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
        assertTrue(parent.delete())
        assertTrue(parent.createNewFile())
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
