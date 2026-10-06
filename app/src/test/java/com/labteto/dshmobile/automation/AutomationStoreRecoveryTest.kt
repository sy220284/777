package com.labteto.dshmobile.automation

import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationStoreRecoveryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun corruptPrimaryRecoversLastKnownGoodBackup() {
        val directory = Files.createTempDirectory("automation-store-recovery").toFile()
        val file = directory.resolve("automations.json")
        try {
            val store = AutomationStore(file, json)
            store.upsert(task("first", 1_000L))
            store.upsert(task("second", 2_000L))

            file.writeText("{broken")
            val recovered = AutomationStore(file, json).list()

            assertEquals(listOf("first", "second"), recovered.map(AutomationTask::id))
            assertTrue(file.readText().contains("\"first\""))
            assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("automations.corrupt-") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingPrimaryRestoresBackupInsteadOfDroppingTasks() {
        val directory = Files.createTempDirectory("automation-store-backup").toFile()
        val file = directory.resolve("automations.json")
        try {
            val store = AutomationStore(file, json)
            store.upsert(task("first", 1_000L))
            assertTrue(file.delete())

            val recovered = AutomationStore(file, json).list()

            assertEquals(listOf("first"), recovered.map(AutomationTask::id))
            assertTrue(file.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedWalTailKeepsEarlierDurableMutations() {
        val directory = Files.createTempDirectory("automation-store-wal-tail").toFile()
        val file = directory.resolve("automations.json")
        val journal = directory.resolve("automations.json.wal.jsonl")
        try {
            val store = AutomationStore(file, json)
            store.upsert(task("first", 1_000L))
            store.upsert(task("second", 2_000L))
            journal.appendText("{broken-tail")

            val recovered = AutomationStore(file, json).list()

            assertEquals(listOf("first", "second"), recovered.map(AutomationTask::id))
            assertTrue(
                directory.listFiles().orEmpty()
                    .any { it.name.startsWith("automations.wal.corrupt-") },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun task(id: String, nextRunAt: Long) = AutomationTask(
        id = id,
        prompt = "prompt-$id",
        createdAt = 1L,
        nextRunAt = nextRunAt,
    )

    @Test
    fun writeFailsClosedWhenPrimaryAndBackupAreCorrupt() {
        val directory = Files.createTempDirectory("automation-store-fail-closed").toFile()
        val file = directory.resolve("automations.json")
        val backup = directory.resolve("automations.json.bak")
        try {
            file.writeText("{broken-primary")
            backup.writeText("{broken-backup")
            val store = AutomationStore(file, json)

            assertThrows(IllegalStateException::class.java) {
                store.upsert(task("must-not-overwrite", 3_000L))
            }

            assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("automations.corrupt-") })
            assertTrue(backup.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun durableWorkerStyleMutationsPublishLatestTaskState() {
        val directory = Files.createTempDirectory("automation-flow").toFile()
        try {
            val store = AutomationStore(directory.resolve("automations.json"), json)
            store.upsert(task("first", 1000L))
            store.update("first") { it.copy(nextRunAt = 2000L) }
            assertEquals(2000L, store.tasks.value.single().nextRunAt)
            assertEquals(store.list(), store.tasks.value)
            store.remove("first")
            assertTrue(store.tasks.value.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun generationOwnershipRejectsEffectsAfterEditOrRemoval() {
        val directory = Files.createTempDirectory("automation-effect-ownership").toFile()
        try {
            val store = AutomationStore(directory.resolve("automations.json"), json)
            store.upsert(task("effect", 1_000L).copy(scheduleGeneration = 4L))
            var submissions = 0
            assertTrue(store.withCurrentGeneration("effect", 4L) { submissions++ })
            store.update("effect") { it.copy(scheduleGeneration = 5L, status = AutomationStatus.PAUSED) }
            assertEquals(false, store.withCurrentGeneration("effect", 4L) { submissions++ })
            store.remove("effect")
            assertEquals(false, store.withCurrentGeneration("effect", 5L) { submissions++ })
            assertEquals(1, submissions)
        } finally {
            directory.deleteRecursively()
        }
    }

}
