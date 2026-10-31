package com.labteto.dshmobile.ui.screens.local

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import java.io.FileInputStream
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
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
        lateinit var listState: LazyListState
        var listBoundsOnScreen: Rect? = null
        compose.setContent {
            listState = rememberLazyListState()
            DshTheme {
                LazyColumn(
                    Modifier.fillMaxSize()
                        .testTag("perf-real-work-timeline")
                        .onGloballyPositioned { coordinates ->
                            val topLeft = coordinates.positionOnScreen()
                            val size = coordinates.size
                            listBoundsOnScreen = Rect(
                                topLeft.x, topLeft.y,
                                topLeft.x + size.width, topLeft.y + size.height,
                            )
                        },
                    state = listState,
                ) {
                    items(rows, key = { it.id }) { row -> WorkTimelineStepRow(row) }
                }
            }
        }
        compose.waitForIdle()
        val viewport = requireNotNull(listBoundsOnScreen) {
            "Work list must be laid out before injecting a real touch gesture"
        }
        assertTrue("Work list must be long enough to scroll", listState.canScrollForward)

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
        var maxVisibleIndex = 0
        fun realSwipe(downwards: Boolean) {
            // Android's 'input swipe' injects actual wall-clock paced touch events
            // through UiAutomation; Compose's synthetic test clock is never used.
            // Waiting for shell stdout EOF ensures each swipe has finished before
            // inspecting the visible index or starting the next gesture.
            val x = ((viewport.left + viewport.right) / 2f).toInt()
            val topY = (viewport.top + viewport.height * 0.22f).toInt()
            val bottomY = (viewport.top + viewport.height * 0.80f).toInt()
            val fromY = if (downwards) topY else bottomY
            val toY = if (downwards) bottomY else topY
            val command = "input touchscreen swipe $x $fromY $x $toY 480"
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { pipe ->
                    val buffer = ByteArray(256)
                    // A returned pipe is complete only after the real shell input gesture exits.
                    while (pipe.read(buffer) >= 0) { /* no output is expected */ }
                }
            }
        }
        try {
            repeat(16) { index ->
                realSwipe(downwards = index % 2 == 1)
                compose.waitForIdle()
                compose.runOnIdle {
                    maxVisibleIndex = maxOf(maxVisibleIndex, listState.firstVisibleItemIndex)
                }
                peakPssKb = maxOf(peakPssKb, pssKb())
                peakJavaKb = maxOf(peakJavaKb, (heap.totalMemory() - heap.freeMemory()) / 1024L)
            }
        } finally {
            instrumentation.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            thread.join(2_000)
        }
        val frames = synchronized(metrics) { metrics.toList() }
        assertTrue("Real scrolling never advanced the Work list: viewport=$viewport maxVisibleIndex=$maxVisibleIndex", maxVisibleIndex > 2)
        // Wall-clock input generates real frame opportunities. Even then an emulator can
        // drop FrameMetrics callbacks under host contention: never fabricate FPS/jank
        // from fewer than 60 real frames and 40 valid VSYNC intervals.
        // A physical-device performance gate must require sample_quality=valid separately.
        val timing = frames.map { it.second }.filter { it > 0L }.sorted()
        val intervals = timing.zipWithNext { a, b -> b - a }
            .filter { it > 0L && it < 250_000_000L }
        val validSample = frames.size >= 60 && intervals.size >= 40
        val observedFps = if (validSample) {
            val ordered = intervals.sorted()
            String.format(Locale.US, "%.2f", 1_000_000_000.0 / ordered[ordered.size / 2])
        } else "NA"
        val jankPct = if (validSample) {
            val longFrames = frames.count { it.first > refreshNs * 1.5 }
            String.format(Locale.US, "%.2f", 100.0 * longFrames / frames.size)
        } else "NA"
        val output = String.format(Locale.US,
            "ANDROID_PERF api=%d scenario=work_timeline_scroll_frames samples=%d " +
                "refresh_hz=%.1f observed_fps_p50=%s jank_pct=%s " +
                "sample_quality=%s gesture_source=android_input_realtime_touch " +
                "valid_vsync_intervals=%d max_visible_index=%d " +
                "peak_pss_kib=%d peak_java_used_kib=%d",
            Build.VERSION.SDK_INT, frames.size, refreshRate, observedFps, jankPct,
            if (validSample) "valid" else "insufficient", intervals.size, maxVisibleIndex,
            peakPssKb, peakJavaKb)
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", output) })
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "work-timeline-review")
        check(dir.isDirectory || dir.mkdirs())
        File(dir, "scroll-memory-api${Build.VERSION.SDK_INT}.txt").writeText(output + "\n")
        assertTrue("Invalid peak PSS", peakPssKb > 0)
    }

}
