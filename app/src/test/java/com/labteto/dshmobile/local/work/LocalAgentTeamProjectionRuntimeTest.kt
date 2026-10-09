package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalAgentTeamProjectionRuntimeTest {
    @Test fun boundedColdReplayPersistsProgressAndRestartsWithoutRescanning() = runBlocking {
        val root = Files.createTempDirectory("team-projection-budget").toFile()
        val file = root.resolve("lead.events.jsonl")
        var reads = 0
        fun runtime() = LocalAgentTeamProjectionRuntime(1, { _, state, event ->
            reads++
            state.copy(asOfSequence = event.sequence)
        }, null, {})
        var log = LocalSessionEventLog(file, Json)
        try {
            repeat(2_000) { log.append("non-team", buildJsonObject { put("index", it) }) }
            val first = runtime()
            assertThrows(LocalTeamProjectionRebuilding::class.java) { first.project("lead", log) }
            assertEquals(LocalAgentTeamProjectionRuntime.PAGE_SIZE * LocalAgentTeamProjectionRuntime.MAX_BATCH_PAGES, reads)
            log.close()
            log = LocalSessionEventLog(file, Json)
            reads = 0
            val recovered = runtime()
            recovered.awaitReady("lead", log)
            assertEquals(2_000 - 640, reads)
            assertEquals(1_999L, recovered.project("lead", log).asOfSequence)
            reads = 0
            assertEquals(1_999L, runtime().project("lead", log).asOfSequence)
            assertEquals(0, reads)
            log.append("non-team", buildJsonObject { put("index", 2_000) })
            assertEquals(2_000L, recovered.project("lead", log).asOfSequence)
            log.clear()
            log.append("replacement", buildJsonObject { put("new", true) })
            assertEquals(0L, runtime().project("lead", log).asOfSequence)
        } finally { log.close(); root.deleteRecursively() }
    }

    @Test fun checkpointCannotCrossSessionAndClosedLogCannotRecreateDeletedCache() {
        val root = Files.createTempDirectory("team-checkpoint-ownership").toFile()
        val file = root.resolve("lead.events.jsonl")
        val log = LocalSessionEventLog(file, Json)
        var reads = 0
        fun runtime() = LocalAgentTeamProjectionRuntime(1, { _, state, event ->
            reads++
            state.copy(asOfSequence = event.sequence)
        }, null, {})
        try {
            log.append("event", buildJsonObject { put("value", 1) })
            runtime().project("first", log)
            reads = 0
            runtime().project("second", log)
            assertEquals(1, reads)
            val generation = log.resetGeneration
            log.close()
            val checkpoint = root.listFiles().orEmpty().single { it.name.contains(".projection-") }
            checkpoint.delete()
            log.writeProjectionCheckpoint("work.agent-team", 1, 0, "{}", generation)
            org.junit.Assert.assertFalse(checkpoint.exists())
        } finally { log.close(); root.deleteRecursively() }
    }

}
