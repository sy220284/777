package com.labteto.dshmobile.harness.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSurfaceTest {
    @Test
    fun replaceRoundTripsWithExplicitProvenance() {
        val mutation = SessionSurfaceMutation(
            surface = MODEL_HISTORY_SURFACE,
            operation = SessionSurfaceOperation.REPLACE,
            sourceEventSeqs = listOf(11L),
            replacementEventSeq = 12L,
        )

        assertEquals(mutation, decodeSessionSurfaceMutation(encodeSessionSurfaceMutation(mutation)))
    }

    @Test
    fun replaceWithoutSourceFailsClosed() {
        val invalid = kotlinx.serialization.json.buildJsonObject {
            put("version", 1)
            put("surface", MODEL_HISTORY_SURFACE)
            put("op", "replace")
            put("source_event_seqs", kotlinx.serialization.json.JsonArray(emptyList()))
            put("replacement_event_seq", 4)
        }
        assertNull(decodeSessionSurfaceMutation(invalid))
    }
}
