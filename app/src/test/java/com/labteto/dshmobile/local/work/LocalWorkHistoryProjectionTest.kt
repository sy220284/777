package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.presentation.LocalWorkHistoryCursor
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkHistoryProjectionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun pagesReachEarliestArtifactBeyond4096EventsWithoutDuplicates() {
        val log = LocalSessionEventLog(File(temporary.root, "events.jsonl"), Json, maxBytes = 64 * 1024)
        try {
            val early = log.append("tool/result", buildJsonObject { put("name", "write_file"); put("path", "reports/early.md"); put("content", "early artifact report.md") })
            repeat(4200) { i -> log.append("tool/call", buildJsonObject { put("name", "read_file"); put("arguments", "page-$i") }) }
            val sequences = hashSetOf<Long>()
            var cursor: LocalWorkHistoryCursor? = null
            do {
                val page = projectLocalWorkHistoryPage(log, cursor)
                assertFalse(page.invalidated)
                assertTrue(page.records.size <= 160)
                page.records.forEach { assertTrue(sequences.add(it.sequence)) }
                if (page.older == null) {
                    val record = page.records.single { it.sequence == early.sequence }
                    assertTrue(record.content.contains("report.md"))
                    assertEquals("reports/early.md", record.artifacts.single().reference)
                }
                cursor = page.older
            } while (cursor != null)
            assertEquals(4201, sequences.size)
        } finally { log.close() }
    }
    @Test fun cursorRejectsResetAndAnotherSessionLog() {
        val log = LocalSessionEventLog(File(temporary.root, "events.jsonl"), Json)
        repeat(170) { log.append("tool/call", buildJsonObject { put("name", "read_file") }) }
        val cursor = requireNotNull(projectLocalWorkHistoryPage(log, null).older)
        val other = LocalSessionEventLog(File(temporary.root, "other.jsonl"), Json)
        try {
            assertTrue(projectLocalWorkHistoryPage(other, cursor).invalidated)
            log.clear()
            assertTrue(projectLocalWorkHistoryPage(log, cursor).invalidated)
            log.close()
            assertTrue(projectLocalWorkHistoryPage(log, null).invalidated)
        } finally { log.close(); other.close() }
    }
}
