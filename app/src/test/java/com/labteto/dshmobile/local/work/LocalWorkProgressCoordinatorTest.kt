package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalSessionEventLog
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
        val visible = MutableStateFlow(LocalHarnessState(sessionId = "visible"))
        val run = MutableStateFlow(LocalHarnessState(sessionId = "background"))
        val log = eventLog(File(temporary.root, "background.jsonl"))
        var writes = 0
        val coordinator = LocalWorkProgressCoordinator(run, log) {
            assertEquals("background", run.value.sessionId)
            assertEquals("goal/state", log.snapshot().last().type)
            writes++
        }

        coordinator.createGoal("  完成任务  ")
        visible.value = visible.value.copy(sessionId = "another-visible")
        coordinator.updateGoal("blocked", "等待依赖")

        assertNull(visible.value.goal)
        assertEquals("完成任务", run.value.goal?.description)
        assertEquals("blocked", run.value.goal?.status)
        assertEquals(2, writes)
        assertEquals("blocked", log.snapshot().last().data["status"]?.toString()?.trim('"'))
    }

    @Test
    fun oversizedPlanAndTodosRetainExistingBoundsAndWholeListReplacement() {
        val state = MutableStateFlow(LocalHarnessState())
        val log = eventLog(File(temporary.root, "progress.jsonl"))
        val coordinator = LocalWorkProgressCoordinator(state, log) {}
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
        assertEquals(20, state.value.plan.size)
        assertEquals(50, state.value.todos.size)
        assertTrue(state.value.todos.all { it.content.length == 500 && it.status == "pending" })

        coordinator.updatePlan(buildJsonObject { put("plan", "一\n\n二") })
        assertEquals(listOf("一", "二"), state.value.plan)
        coordinator.updateTodos(buildJsonObject {})
        assertTrue(state.value.todos.isEmpty())
        assertEquals(listOf("plan/state", "todo/state", "plan/state", "todo/state"), log.snapshot().map { it.type })
    }

    @Test
    fun invalidOrMissingGoalDoesNotWriteAnEventOrPersist() {
        val state = MutableStateFlow(LocalHarnessState())
        val log = eventLog(File(temporary.root, "goal.jsonl"))
        var writes = 0
        val coordinator = LocalWorkProgressCoordinator(state, log) { writes++ }
        assertTrue(runCatching { coordinator.updateGoal("active", null) }.isFailure)
        assertTrue(runCatching { coordinator.updateGoal("invalid", null) }.isFailure)
        assertNull(state.value.goal)
        assertTrue(log.snapshot().isEmpty())
        assertEquals(0, writes)

        coordinator.createGoal("g".repeat(3000))
        coordinator.updateGoal("completed", "n".repeat(3000))
        assertEquals(2000, state.value.goal?.description?.length)
        assertEquals(2000, state.value.goal?.note?.length)
        coordinator.updateGoal("paused", null)
        assertNull(state.value.goal?.note)
    }

    @Test
    fun eventWriteFailureStopsPersistence() {
        val state = MutableStateFlow(LocalHarnessState())
        val parent = temporary.newFolder("not-a-directory")
        val log = eventLog(File(parent, "goal.jsonl"))
        assertTrue(parent.delete())
        assertTrue(parent.createNewFile())
        var persisted = false
        val coordinator = LocalWorkProgressCoordinator(state, log) { persisted = true }
        assertTrue(runCatching { coordinator.createGoal("失败场景") }.isFailure)
        assertEquals(false, persisted)
    }
}
