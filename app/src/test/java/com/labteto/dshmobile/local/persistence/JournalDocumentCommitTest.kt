package com.labteto.dshmobile.local.persistence

import com.labteto.dshmobile.automation.*
import com.labteto.dshmobile.local.memory.*
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JournalDocumentCommitTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun memoryReplaySurvivesEachCompactionInterruptionAndLostPrimary() {
        for (stage in SnapshotCommitStage.entries) {
            val root = temporary.newFolder()
            val store = MemoryDocumentStore(root, Json) { if (it == stage) error("commit interruption") }
            // Initial snapshot uses the same primitive; seed through a non-faulting owner.
            val seed = MemoryRecord("memory", MemoryScope.GLOBAL, MemoryKind.FACT, "seed", createdAt = 1, updatedAt = 1)
            MemoryDocumentStore(root, Json).write(MemoryDocument(records = listOf(seed)))
            var expected = seed
            assertThrows(IllegalStateException::class.java) {
                repeat(129) { i ->
                    expected = seed.copy(content = "revision-$i", updatedAt = i + 2L)
                    store.write(MemoryDocument(records = listOf(expected)))
                }
            }
            assertEquals(expected, MemoryDocumentStore(root, Json).read().records.single())
            assertTrue(File(root, "memories.json").delete())
            assertEquals(expected, MemoryDocumentStore(root, Json).read().records.single())
        }
    }

    @Test fun automationReplayPreservesDeletedGenerationAtEachCommitInterruption() {
        for (stage in SnapshotCommitStage.entries) {
            val file = File(temporary.newFolder(), "automations.json")
            val seed = AutomationTask("task", "prompt", createdAt = 1, nextRunAt = 1, scheduleGeneration = 1000)
            AutomationDocumentStore(file, Json).write(AutomationDocument(tasks = listOf(seed)))
            val store = AutomationDocumentStore(file, Json) { if (it == stage) error("commit interruption") }
            var expected = AutomationDocument()
            assertThrows(IllegalStateException::class.java) {
                repeat(129) { i ->
                    expected = AutomationDocument(tasks = if (i == 128) emptyList() else listOf(seed.copy(prompt = "revision-$i")),
                        generationWatermark = 1000)
                    store.write(expected)
                }
            }
            assertEquals(expected, AutomationDocumentStore(file, Json).read())
            assertTrue(file.delete())
            assertEquals(expected, AutomationDocumentStore(file, Json).read())
        }
    }

    @Test fun validUnterminatedMutationDoesNotMergeWithNextAppend() {
        val root = temporary.newFolder()
        val record = MemoryRecord("first", MemoryScope.GLOBAL, MemoryKind.FACT, "seed", createdAt = 1, updatedAt = 1)
        MemoryDocumentStore(root, Json).apply {
            write(MemoryDocument(records = listOf(record)))
            write(MemoryDocument(records = listOf(record.copy(content = "second"))))
        }
        val wal = File(root, "memories.wal.jsonl")
        wal.writeText(wal.readText().trimEnd())
        val expected = record.copy(content = "third")
        MemoryDocumentStore(root, Json).write(MemoryDocument(records = listOf(expected)))
        assertEquals(expected, MemoryDocumentStore(root, Json).read().records.single())
        assertEquals(2, wal.readLines().size)
    }
}
