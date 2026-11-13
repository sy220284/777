package com.labteto.dshmobile.automation

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic substitutes for external schedulers: concurrent persistence and stale Worker settlement. */
class OfflineAutomationAcceptanceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun task(id: String, runAt: Long) = AutomationTask(
        id = id, prompt = "task-$id", createdAt = 1L, nextRunAt = runAt,
    )

    @Test
    fun simultaneousTaskWritesSurviveColdReloadWithoutLosingOtherTasks() {
        val root = Files.createTempDirectory("automation-acceptance-parallel-").toFile()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val store = AutomationStore(root.resolve("automations.json"), json)
            val start = CountDownLatch(1)
            val futures = (0 until 24).map { index ->
                pool.submit {
                    start.await()
                    store.upsert(task("task-$index", index.toLong() + 1_000))
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
            val reopened = AutomationStore(root.resolve("automations.json"), json)
            assertEquals(24, reopened.list().size)
            assertEquals((0 until 24).map { "task-$it" }.toSet(),
                reopened.list().map { it.id }.toSet())
        } finally {
            pool.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test
    fun staleWorkerCannotFinishAfterRepeatedDeleteRecreateAndRestart() {
        val root = Files.createTempDirectory("automation-acceptance-stale-").toFile()
        val file = root.resolve("automations.json")
        try {
            var store = AutomationStore(file, json)
            var previousGeneration = -1L
            repeat(18) { round ->
                val admitted = store.upsert(task("reused-id", 10_000L + round).copy(
                    prompt = "round-$round",
                )).admitted
                val current = admitted.scheduleGeneration
                assertTrue(current > previousGeneration)
                if (previousGeneration >= 0L) {
                    assertFalse(store.withCurrentGeneration("reused-id", previousGeneration) {
                        error("a stale Worker must not commit")
                    })
                    assertEquals(null, store.updateIf("reused-id",
                        { it.scheduleGeneration == previousGeneration }) { it.copy(lastResult = "late") })
                }
                if (round % 3 == 2) store = AutomationStore(file, json)
                previousGeneration = current
                assertTrue(store.removeIfGeneration("reused-id", current))
            }
            store = AutomationStore(file, json)
            val finalTask = store.upsert(task("reused-id", 99_999L).copy(
                prompt = "current",
            )).admitted
            assertTrue(finalTask.scheduleGeneration > previousGeneration)
            assertFalse(store.withCurrentGeneration("reused-id", previousGeneration) {
                error("old completion")
            })
            assertEquals("current", AutomationStore(file, json).get("reused-id")!!.prompt)
            assertEquals(null, AutomationStore(file, json).get("reused-id")!!.lastResult)
        } finally {
            root.deleteRecursively()
        }
    }
}
