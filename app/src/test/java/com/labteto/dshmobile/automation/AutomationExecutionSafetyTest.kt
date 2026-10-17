package com.labteto.dshmobile.automation

import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                it.copy(prompt = "新任务", scheduleGeneration = 8L, status = AutomationStatus.SCHEDULED)
            }
            assertNotNull(edited)

            val staleCommit = store.updateIf(
                "task-a",
                predicate = { it.scheduleGeneration == 7L },
            ) {
                it.copy(status = AutomationStatus.COMPLETED, lastResult = "旧 Worker 结果")
            }

            assertNull(staleCommit)
            assertEquals("新任务", store.get("task-a")!!.prompt)
            assertEquals(8L, store.get("task-a")!!.scheduleGeneration)
            assertEquals(AutomationStatus.SCHEDULED, store.get("task-a")!!.status)
            assertNull(store.get("task-a")!!.lastResult)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun scheduledAutomationCannotRestartAfterTerminalSettlement() {
        val generation = 42L
        val active = AutomationTask(
            id = "scheduled", prompt = "work", createdAt = 1L, nextRunAt = 2L,
            scheduleGeneration = generation, status = AutomationStatus.RUNNING, lastRunAt = 1_000L,
        )
        assertTrue(mayStartScheduledAutomation(active, generation))
        assertTrue(ownsAutomationSettlement(active, generation, 1_000L, manualRun = false))
        for (terminal in listOf(
            AutomationStatus.COMPLETED, AutomationStatus.FAILED,
            AutomationStatus.PAUSED, AutomationStatus.BLOCKED, AutomationStatus.WAITING_USER,
        )) {
            val finished = active.copy(status = terminal)
            assertFalse(mayStartScheduledAutomation(finished, generation))
            assertFalse(ownsAutomationSettlement(finished, generation, 1_000L, manualRun = false))
        }
    }

    @Test
    fun staleScheduledWorkerCannotSettleAnotherRunOfTheSameGeneration() {
        val directory = Files.createTempDirectory("automation-attempt-fencing").toFile()
        try {
            val store = AutomationStore(directory.resolve("automations.json"), json)
            val admitted = store.upsert(AutomationTask(
                id = "scheduled", prompt = "work", createdAt = 1L, nextRunAt = 2L,
                status = AutomationStatus.RUNNING, lastRunAt = 200L,
            )).admitted
            val generation = admitted.scheduleGeneration
            // The next run uses the same schedule generation: that is not a run identity.
            store.updateIf("scheduled", predicate = { it.scheduleGeneration == generation }) {
                it.copy(status = AutomationStatus.RUNNING, lastRunAt = 300L)
            }
            assertNull(store.updateIf("scheduled", predicate = {
                ownsAutomationSettlement(it, generation, 200L, manualRun = false)
            }) { it.copy(status = AutomationStatus.COMPLETED, lastResult = "stale") })
            assertEquals(AutomationStatus.RUNNING, store.get("scheduled")!!.status)
            assertNull(store.get("scheduled")!!.lastResult)
            assertNotNull(store.updateIf("scheduled", predicate = {
                ownsAutomationSettlement(it, generation, 300L, manualRun = false)
            }) { it.copy(status = AutomationStatus.COMPLETED, lastResult = "current") })
            assertNull(store.updateIf("scheduled", predicate = {
                ownsAutomationSettlement(it, generation, 300L, manualRun = false)
            }) { it.copy(lastResult = "duplicate") })
            assertEquals("current", store.get("scheduled")!!.lastResult)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun automationSettlementRequiresExactDurableManualExecutionIdentity() {
        val task = AutomationTask(
            id = "manual", prompt = "work", createdAt = 1L, nextRunAt = 2L,
            scheduleGeneration = 10L, status = AutomationStatus.PAUSED,
        )
        val claimed = task.copy(activeManualRunId = "work-request-a")
        assertFalse(ownsAutomationSettlement(claimed, 9L, 10L, manualRun = true, manualExecutionId = "work-request-a"))
        assertFalse(ownsAutomationSettlement(claimed, 10L, 10L, manualRun = true))
        assertFalse(ownsAutomationSettlement(claimed, 10L, 10L, manualRun = true, manualExecutionId = "work-request-b"))
        assertTrue(ownsAutomationSettlement(claimed, 10L, 10L, manualRun = true, manualExecutionId = "work-request-a"))
        assertFalse(ownsAutomationSettlement(claimed, 10L, 10L, manualRun = false))
        // Replacing a manual WorkManager request revokes every late settlement of
        // its predecessor even when the scheduler generation is unchanged.
        val replacement = claimed.copy(activeManualRunId = "work-request-b")
        assertFalse(ownsAutomationSettlement(replacement, 10L, 10L, manualRun = true, manualExecutionId = "work-request-a"))
        assertTrue(ownsAutomationSettlement(replacement, 10L, 10L, manualRun = true, manualExecutionId = "work-request-b"))
        assertFalse(ownsAutomationSettlement(replacement.copy(activeManualRunId = null), 10L, 10L,
            manualRun = true, manualExecutionId = "work-request-b"))
    }

    @Test
    fun manualRunDurableClaimSurvivesReopenAndFencesReplacedReceipt() {
        val dir = Files.createTempDirectory("automation-manual-identity").toFile()
        try {
            val file = dir.resolve("automations.json")
            val firstProcess = AutomationStore(file, json)
            val task = firstProcess.upsert(AutomationTask(
                id = "manual", prompt = "one shot", createdAt = 1L,
                nextRunAt = 2L, status = AutomationStatus.PAUSED,
            )).admitted
            assertNull(task.activeManualRunId)
            assertNotNull(firstProcess.updateIf("manual", predicate = {
                it.scheduleGeneration == task.scheduleGeneration
            }) { it.copy(activeManualRunId = "request-a") })

            val restarted = AutomationStore(file, json)
            assertEquals("request-a", restarted.get("manual")!!.activeManualRunId)
            assertNotNull(restarted.updateIf("manual", predicate = {
                it.scheduleGeneration == task.scheduleGeneration
            }) { it.copy(activeManualRunId = "request-b") })
            val staleCommit = restarted.updateIf("manual", predicate = {
                ownsAutomationSettlement(it, task.scheduleGeneration, 100L,
                    manualRun = true, manualExecutionId = "request-a")
            }) { it.copy(lastResult = "stale", activeManualRunId = null) }
            assertNull(staleCommit)
            assertEquals("request-b", restarted.get("manual")!!.activeManualRunId)
            assertNull(restarted.get("manual")!!.lastResult)
            val currentCommit = restarted.updateIf("manual", predicate = {
                ownsAutomationSettlement(it, task.scheduleGeneration, 200L,
                    manualRun = true, manualExecutionId = "request-b")
            }) { it.copy(lastResult = "genuine", activeManualRunId = null) }
            assertNotNull(currentCommit)
            assertNull(restarted.updateIf("manual", predicate = {
                ownsAutomationSettlement(it, task.scheduleGeneration, 200L,
                    manualRun = true, manualExecutionId = "request-b")
            }) { it.copy(lastResult = "duplicate") })
            assertEquals("genuine", AutomationStore(file, json).get("manual")!!.lastResult)
            val edited = restarted.upsert(requireNotNull(restarted.get("manual"))
                .copy(prompt = "edited", activeManualRunId = "old-request")).admitted
            assertTrue(edited.scheduleGeneration > task.scheduleGeneration)
            assertNull(edited.activeManualRunId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun interactionPolicyUpdatePreservesScheduleAndWaitingState() {
        val original = AutomationTask(
            id = "policy-chat",
            prompt = "继续互动",
            createdAt = 1L,
            nextRunAt = 9_999L,
            recurringMinutes = 120L,
            scheduleType = AutomationScheduleType.INTERVAL,
            scheduleGeneration = 7L,
            mode = AutomationMode.CHAT,
            targetSessionId = "session-a",
            status = AutomationStatus.WAITING_USER,
            quietHoursEnabled = false,
            proactiveMinGapMinutes = 360L,
            proactiveMaxUnanswered = 3,
            notify = true,
        )

        val updated = original.withChatInteractionPolicy(
            quietHoursEnabled = true,
            quietStartHour = 23,
            quietStartMinute = 30,
            quietEndHour = 8,
            quietEndMinute = 15,
            proactiveMinGapMinutes = 720L,
            proactiveMaxUnanswered = 2,
            notify = false,
        )

        assertEquals(9_999L, updated.nextRunAt)
        assertEquals(7L, updated.scheduleGeneration)
        assertEquals(AutomationStatus.WAITING_USER, updated.status)
        assertEquals(AutomationScheduleType.INTERVAL, updated.scheduleType)
        assertEquals(120L, updated.recurringMinutes)
        assertEquals(true, updated.quietHoursEnabled)
        assertEquals(23, updated.quietStartHour)
        assertEquals(30, updated.quietStartMinute)
        assertEquals(8, updated.quietEndHour)
        assertEquals(15, updated.quietEndMinute)
        assertEquals(720L, updated.proactiveMinGapMinutes)
        assertEquals(2, updated.proactiveMaxUnanswered)
        assertEquals(false, updated.notify)
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
            status = AutomationStatus.WAITING_USER,
        )
        val userMessageAt = 10_000L

        assertEquals(
            3_610_000L,
            nextAutomationRunAfterUserActivity(task, userMessageAt),
        )
    }
    @Test
    fun intervalAfterReplyStartsFromActualActivity() {
        val task = AutomationTask(
            id = "anchored-chat", prompt = "继续互动", createdAt = 1L, nextRunAt = 2L,
            recurringMinutes = 60L, scheduleType = AutomationScheduleType.INTERVAL,
            mode = AutomationMode.CHAT, targetSessionId = "session-a", status = AutomationStatus.WAITING_USER,
        )
        assertEquals(10_800_100L, nextAutomationRunAfterUserActivity(task, 7_200_100L))
    }

    @Test
    fun silenceScheduleRestartsItsDelayFromActualUserActivity() {
        val task = AutomationTask(
            id = "silence-chat", prompt = "继续互动", createdAt = 1L, nextRunAt = 2L,
            silenceMinutes = 60L, scheduleType = AutomationScheduleType.SILENCE,
            mode = AutomationMode.CHAT, targetSessionId = "session-a", status = AutomationStatus.WAITING_USER,
        )
        assertEquals(3_610_000L, nextAutomationRunAfterUserActivity(task, 10_000L))
    }
}
