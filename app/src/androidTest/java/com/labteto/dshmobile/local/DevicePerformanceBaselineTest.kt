package com.labteto.dshmobile.local

import android.os.Build
import android.os.Debug
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.work.LocalWorkOpenGraphProjection
import com.labteto.dshmobile.ui.screens.local.buildLocalTranscript
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android 16/17 emulator measurements, independent of paid models and external services.
 * Diagnostic P50/P95 values are emitted as instrumentation status and saved in the existing
 * Work timeline artifact; there are no artificial runner-dependent latency gates.
 */
@RunWith(AndroidJUnit4::class)
class DevicePerformanceBaselineTest {
    private fun measure(block: () -> Unit): Double {
        val started = SystemClock.elapsedRealtimeNanos()
        block()
        return (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
    }

    private fun report(name: String, samples: List<Double>) {
        require(samples.size >= 10)
        val sorted = samples.sorted()
        fun percentile(value: Double): Double = sorted[(kotlin.math.ceil(value * sorted.size).toInt() - 1)
            .coerceIn(0, sorted.lastIndex)]
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val heap = Runtime.getRuntime()
        val javaUsedKiB = (heap.totalMemory() - heap.freeMemory()) / 1024
        val output = "ANDROID_PERF api=${Build.VERSION.SDK_INT} scenario=$name samples=${sorted.size} " +
            "p50_ms=${"%.3f".format(Locale.US, percentile(0.50))} " +
            "p95_ms=${"%.3f".format(Locale.US, percentile(0.95))} " +
            "max_ms=${"%.3f".format(Locale.US, sorted.last())} " +
            "sample_pss_kib=${memory.totalPss} sample_java_used_kib=$javaUsedKiB"
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", output)
        })
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidenceDir = File(requireNotNull(context.getExternalFilesDir(null)), "work-timeline-review")
        check(evidenceDir.isDirectory || evidenceDir.mkdirs())
        File(evidenceDir, "performance-api${Build.VERSION.SDK_INT}.txt").appendText("$output\n")
    }

    @Test fun longWorkEventLogColdAndIncrementalRecoveryPercentiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "work-perf-${System.nanoTime()}").apply { mkdirs() }
        val file = File(root, "events.jsonl")
        val json = Json { ignoreUnknownKeys = true }
        try {
            val writer = LocalSessionEventLog(file, json, maxBytes = 1_048_576L)
            try {
                repeat(1_000) { index ->
                    val fact = buildJsonObject {
                        put("graph_id", "graph-$index")
                        put("goal_id", "goal")
                        put("graph_execution_id", "execution-$index")
                        put("goal_fingerprint", "fingerprint-$index")
                    }
                    writer.append("taskgraph/start", fact)
                    writer.append("taskgraph/end", fact)
                }
                assertEquals(1_999L, writer.latestSequence())
            } finally {
                writer.close()
            }

            val coldSamples = mutableListOf<Double>()
            repeat(20) {
                val log = LocalSessionEventLog(file, json, maxBytes = 1_048_576L)
                try {
                    coldSamples += measure {
                        assertTrue(LocalWorkOpenGraphProjection().unresolved(log).isEmpty())
                    }
                } finally {
                    log.close()
                }
            }
            report("work_2000_events_cold_projection", coldSamples)

            val live = LocalSessionEventLog(file, json, maxBytes = 1_048_576L)
            try {
                val projection = LocalWorkOpenGraphProjection()
                assertTrue(projection.unresolved(live).isEmpty())
                val incremental = mutableListOf<Double>()
                repeat(30) { index ->
                    live.append("benchmark/trace", buildJsonObject { put("index", index) })
                    incremental += measure { assertTrue(projection.unresolved(live).isEmpty()) }
                }
                report("work_2000_events_incremental_projection", incremental)
            } finally {
                live.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun largeChatAndToolTimelineMaterializationPercentiles() {
        val messages = List(10_000) { index ->
            LocalHarnessMessage(
                id = "tool-$index",
                role = "tool",
                content = "result-$index",
                createdAt = index.toLong(),
                toolDeclaredAt = if (index % 3 == 0) null else (index / 2).toLong(),
                toolDeclaredSequence = index.toLong(),
            )
        }
        val samples = List(20) {
            measure { assertEquals(messages.size, buildLocalTranscript(messages).size) }
        }
        report("chat_10000_tool_rows_timeline_materialization", samples)
    }
}
