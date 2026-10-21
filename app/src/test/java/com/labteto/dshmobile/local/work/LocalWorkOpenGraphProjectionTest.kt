package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkOpenGraphProjectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun incrementallyReadsNewFactsAndInvalidatesAfterClear() {
        val log = LocalSessionEventLog(File(temporary.root, "facts.jsonl"), Json)
        val projection = LocalWorkOpenGraphProjection()
        fun fact(id: String) = buildJsonObject {
            put("graph_id", id); put("graph_execution_id", "$"+"id-run"); put("goal_id", "goal")
        }
        assertTrue(projection.unresolved(log).isEmpty())
        log.append("taskgraph/start", fact("first"))
        assertEquals(listOf("first"), projection.unresolved(log).map { it.graphId })
        log.append("other-event", buildJsonObject { put("note", "not a graph") })
        assertEquals(listOf("first"), projection.unresolved(log).map { it.graphId })
        log.append("taskgraph/end", fact("first"))
        assertTrue(projection.unresolved(log).isEmpty())
        log.append("taskgraph/start", fact("second"))
        assertEquals(listOf("second"), projection.unresolved(log).map { it.graphId })
        log.clear()
        assertTrue(projection.unresolved(log).isEmpty())
        log.append("taskgraph/start", fact("third"))
        assertEquals(listOf("third"), projection.unresolved(log).map { it.graphId })
        log.close()
    }
}
