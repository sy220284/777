package com.labteto.dshmobile.automation

import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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

            assertEquals(listOf("first"), recovered.map(AutomationTask::id))
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

    private fun task(id: String, nextRunAt: Long) = AutomationTask(
        id = id,
        prompt = "prompt-$id",
        createdAt = 1L,
        nextRunAt = nextRunAt,
    )

    @Test
    fun writeRepairsInvalidBackupAfterCorruptPrimary() {
        val directory = Files.createTempDirectory("automation-store-repair-backup").toFile()
        val file = directory.resolve("automations.json")
        val backup = directory.resolve("automations.json.bak")
        try {
            file.writeText("{broken-primary")
            backup.writeText("{broken-backup")
            val store = AutomationStore(file, json)

            store.upsert(task("recovered", 3_000L))

            file.writeText("{broken-again")
            assertEquals(listOf("recovered"), AutomationStore(file, json).list().map(AutomationTask::id))
        } finally {
            directory.deleteRecursively()
        }
    }

}
