package com.labteto.dshmobile.ui.screens.local

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.ui.theme.DshTheme
import java.io.File
import java.util.Collections
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Samples real Android Window frame metrics while scrolling production Work timeline rows.
 * Measurements are diagnostic; CI must not fail due solely to virtualized host speed.
 */
class DeviceScrollFrameAndMemoryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun workTimelineScrollReportsFrameRateJankAndPeakPss() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rows = List(400) { index ->
            LocalHarnessMessage(
                id = "scroll-perf-$index", role = "tool", content = "步骤 $index 已核查",
                toolName = "mcp__github__get_pr_info", createdAt = index.toLong(),
                toolCallId = "scroll-call-$index",
            )
        }
        compose.setContent {
            DshTheme {
                LazyColumn(Modifier.fillMaxSize().testTag("perf-real-work-timeline")) {
                    items(rows, key = { it.id }) { row -> WorkTimelineStepRow(row) }
                }
            }
        }
        compose.waitForIdle()

        lateinit var activity: Activity
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).first()
        }
        var refreshRate = 60f
        instrumentation.runOnMainSync {
            refreshRate = (activity.display?.refreshRate ?: 60f).coerceAtLeast(30f)
        }
        val refreshNs = 1_000_000_000.0 / refreshRate
        val metrics = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val thread = HandlerThread("perf-frame-callback").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            val total = frame.getMetric(FrameMetrics.TOTAL_DURATION)
            val vsync = frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
            if (total > 0L) metrics.add(total to vsync)
        }
        instrumentation.runOnMainSync {
            activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }
        val heap = Runtime.getRuntime()
        val pss = Debug.MemoryInfo()
        fun pssKb(): Int {
            Debug.getMemoryInfo(pss)
            return pss.totalPss
        }
        var peakPssKb = pssKb()
        var peakJavaKb = (heap.totalMemory() - heap.freeMemory()) / 1024L
        try {
            repeat(12) {
                compose.onNodeWithTag("perf-real-work-timeline").performTouchInput { swipeUp() }
                compose.waitForIdle()
                peakPssKb = maxOf(peakPssKb, pssKb())
                peakJavaKb = maxOf(peakJavaKb, (heap.totalMemory() - heap.freeMemory()) / 1024L)
            }
        } finally {
            instrumentation.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            thread.join(2_000)
        }
        val frames = synchronized(metrics) { metrics.toList() }
        assertTrue("FrameMetrics listener did not capture actual rendered frames", frames.size >= 20)
        val longFrames = frames.count { it.first > refreshNs * 1.5 }
        val timing = frames.map { it.second }.filter { it > 0L }.sorted()
        val intervals = timing.zipWithNext { a, b -> b - a }
            .filter { it > 0L && it < 250_000_000L }
        val observedFps = if (intervals.isNotEmpty()) {
            1_000_000_000.0 / intervals.sorted()[intervals.size / 2]
        } else 0.0
        val jankPct = 100.0 * longFrames / frames.size
        val output = String.format(Locale.US,
            "ANDROID_PERF api=%d scenario=work_timeline_scroll_frames samples=%d " +
                "refresh_hz=%.1f observed_fps_p50=%.2f jank_pct=%.2f " +
                "peak_pss_kib=%d peak_java_used_kib=%d",
            Build.VERSION.SDK_INT, frames.size, refreshRate, observedFps, jankPct, peakPssKb, peakJavaKb)
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", output) })
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "work-timeline-review")
        check(dir.isDirectory || dir.mkdirs())
        File(dir, "scroll-memory-api${Build.VERSION.SDK_INT}.txt").writeText(output + "\n")
        assertTrue("Invalid peak PSS", peakPssKb > 0)
    }

}
