package com.labteto.dshmobile.automation

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Actual WorkManager -> Hilt Worker -> durable task receipt, without paid model calls.
 * CHAT with a deliberately absent target has a deterministic fail-closed outcome.
 * This is dispatch-and-settlement E2E, not successful AI task completion latency.
 */
class AutomationDispatchEndToEndAndroidTest {
    @Test fun scheduledTaskDispatchAndPersistedFailureReceiptLatency() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AutomationStore(context, Json { ignoreUnknownKeys = true; encodeDefaults = true })
        val scheduler = HarnessAutomationScheduler(context, store)
        val manager = WorkManager.getInstance(context)
        val measurements = mutableListOf<Double>()
        val prefix = "perf-e2e-" + UUID.randomUUID().toString().take(8)
        try {
            repeat(10) { index ->
                val id = "$prefix-$index"
                val start = SystemClock.elapsedRealtimeNanos()
                scheduler.scheduleOnce(
                    id = id, prompt = "e2e validation: no target session, no model calls",
                    triggerAtMillis = System.currentTimeMillis(),
                    notify = false, mode = AutomationMode.CHAT, targetSessionId = null,
                )
                val task = withTimeout<AutomationTask>(60_000L) {
                    while (true) {
                        val current = store.get(id)
                        if (current?.status == AutomationStatus.FAILED &&
                            current.runReceipts.lastOrNull()?.status == AutomationStatus.FAILED) {
                            return@withTimeout current
                        }
                        delay(100)
                    }
                }
                assertEquals(1, task.runReceipts.size)
                assertNotNull(task.lastError)
                assertTrue(task.lastError!!.contains("目标会话"))
                val infos = manager.getWorkInfosForUniqueWork(
                    HarnessAutomationScheduler.workName(id, task.scheduleGeneration)
                ).get()
                assertTrue("WorkManager request must exist", infos.isNotEmpty())
                measurements += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                scheduler.cancel(id)
            }
            val sorted = measurements.sorted()
            fun percentile(fraction: Double): Double =
                sorted[(kotlin.math.ceil(fraction * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
            val line = String.format(Locale.US,
                "ANDROID_PERF api=%d scenario=automation_real_workmanager_fail_closed_e2e " +
                    "samples=%d p50_ms=%.3f p95_ms=%.3f",
                Build.VERSION.SDK_INT, sorted.size, percentile(0.5), percentile(0.95))
            InstrumentationRegistry.getInstrumentation().sendStatus(2,
                Bundle().apply { putString("stream", line) })
            val dir = File(context.getExternalFilesDir(null), "work-timeline-review")
            check(dir.isDirectory || dir.mkdirs())
            File(dir, "automation-dispatch-api${Build.VERSION.SDK_INT}.txt").writeText(line + "\n")
        } finally {
            (0 until 10).forEach { scheduler.cancel("$prefix-$it") }
        }
    }
}
