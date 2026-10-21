package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.harness.session.SessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android cache filesystem smoke test for directory fsync on creation, rename,
 * gzip publication and raw archive removal. A JVM test cannot prove compatibility.
 */
@RunWith(AndroidJUnit4::class)
class SessionEventLogDurabilityAndroidTest {
    @Test fun rotatedAndCompressedSegmentsSurviveColdReader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "durable-session-event-${System.nanoTime()}").apply { mkdirs() }
        val file = File(root, "events.jsonl")
        val json = Json { ignoreUnknownKeys = true }
        try {
            SessionEventLog(file, json, maxBytes = 700).use { log ->
                repeat(16) { index ->
                    log.append(
                        "android/record",
                        buildJsonObject { put("index", index); put("data", "x".repeat(96)) },
                    )
                }
                assertTrue("must rotate at least one event segment", root.listFiles().orEmpty().any {
                    it.name.matches(Regex("events\\.jsonl\\.part-[0-9]+"))
                })
                while (log.archiveLegacySegments(limit = 16) > 0) Unit
                assertTrue("at least one segment must be compressed", root.listFiles().orEmpty().any {
                    it.name.endsWith(".gz")
                })
            }
            SessionEventLog(file, json, maxBytes = 700).use { cold ->
                assertEquals((0L..15L).toList(), cold.snapshot().map { it.sequence })
                assertEquals(15L, cold.latestSequence())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
