package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkArtifactProjectionTest {
    private fun event(seq: Long, path: String, error: Boolean = false) =
        LocalSessionEventLog.Event(
            sequence = seq, type = "tool/result", createdAt = seq,
            data = buildJsonObject {
                put("id", "call-$seq")
                put("path", path)
                put("is_error", error)
            },
        )

    @Test fun onlySuccessfulResultsProduceArtifacts() {
        val artifacts = projectLocalWorkArtifacts(listOf(
            event(1, "reports/one.md"),
            event(2, "reports/failed.md", error = true),
            event(3, "../escape.md"),
            event(4, "reports/one.md"),
        ))
        assertEquals(1, artifacts.size)
        assertEquals(4L, artifacts.single().asOfSequence)
        assertEquals("reports/one.md", artifacts.single().reference)
    }

    @Test fun declarationsCannotPretendToBeResults() {
        val declaration = event(9, "unconfirmed.md").copy(type = "tool/call")
        assertTrue(projectLocalWorkArtifacts(listOf(declaration)).isEmpty())
    }
}
