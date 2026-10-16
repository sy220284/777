package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class LocalWorkUiDocumentSourceTest {
    @Test fun continuationAndProcessRecoveryKeepOriginalCardAcrossPagedLogs() {
        val root = Files.createTempDirectory("work-ui-source").toFile()
        val logs = LocalSessionEventLogRegistry(root, Json)
        try {
            val log = logs.get("s")
            log.append("turn/start", buildJsonObject { put("run_id", "run-1"); put("source_message_id", "original-user") })
            repeat(260) { log.append("step/start", buildJsonObject { put("step", it) }) }
            log.append("turn/continuation-queued", buildJsonObject {
                put("source_run_id", "run-1"); put("continuation_id", "continuation-run-1-1")
            })
            assertEquals("original-user", resolveWorkUiDocumentSource(log, "continuation-run-1-1"))
            log.append("turn/start", buildJsonObject { put("run_id", "run-2"); put("source_message_id", "original-user") })
            assertEquals("original-user", resolveWorkUiDocumentSource(log, "run-recovery:run-2"))
            assertEquals("new-user", resolveWorkUiDocumentSource(log, "new-user"))
            assertEquals("run-recovery:unknown", resolveWorkUiDocumentSource(log, "run-recovery:unknown"))
            assertNull(resolveWorkUiDocumentSource(log, null))
        } finally { logs.clearAndEvict(setOf("s")); root.deleteRecursively() }
    }
}
