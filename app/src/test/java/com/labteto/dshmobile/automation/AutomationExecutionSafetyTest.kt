package com.labteto.dshmobile.automation

import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationExecutionSafetyTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun taskLeaseRejectsOverlappingExecutionUntilOwnerReleases() {
        val first = AutomationExecutionRegistry.tryAcquire("task-a")
        assertNotNull(first)
        assertNull(AutomationExecutionRegistry.tryAcquire("task-a"))

        first!!.close()

        val next = AutomationExecutionRegistry.tryAcquire("task-a")
        assertNotNull(next)
        next!!.close()
    }

    @Test
    fun staleGenerationCannotOverwriteEditedTask() {
        val directory = Files.createTempDirectory("automation-generation").toFile()
        try {
            val store = AutomationStore(directory.resolve("automations.json"), json)
            store.upsert(
                AutomationTask(
                    id = "task-a",
                    prompt = "旧任务",
                    createdAt = 1L,
                    nextRunAt = 2L,
                    scheduleGeneration = 7L,
                ),
            )

            val edited = store.updateIf(
                "task-a",
                predicate = { it.scheduleGeneration == 7L },
            ) {
                it.copy(prompt = "新任务", scheduleGeneration = 8L, status = "scheduled")
            }
            assertNotNull(edited)

            val staleCommit = store.updateIf(
                "task-a",
                predicate = { it.scheduleGeneration == 7L },
            ) {
                it.copy(status = "completed", lastResult = "旧 Worker 结果")
            }

            assertNull(staleCommit)
            assertEquals("新任务", store.get("task-a")!!.prompt)
            assertEquals(8L, store.get("task-a")!!.scheduleGeneration)
            assertEquals("scheduled", store.get("task-a")!!.status)
            assertNull(store.get("task-a")!!.lastResult)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun userActivityRescheduleUsesActivityTimeInsteadOfOldWaitingSnapshot() {
        val task = AutomationTask(
            id = "chat-a",
            prompt = "继续互动",
            createdAt = 1L,
            nextRunAt = 2L,
            recurringMinutes = 60L,
            scheduleType = AutomationScheduleType.INTERVAL,
            mode = AutomationMode.CHAT,
            targetSessionId = "session-a",
            status = "waiting_user",
        )
        val userMessageAt = 10_000L

        assertEquals(
            userMessageAt + 60L * 60L * 1000L,
            nextAutomationRunAfterUserActivity(task, userMessageAt),
        )
    }
}
