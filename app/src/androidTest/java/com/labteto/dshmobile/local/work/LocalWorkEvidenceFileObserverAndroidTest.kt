package com.labteto.dshmobile.local.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android inotify events, including files beyond the old 100-item truncation. */
@RunWith(AndroidJUnit4::class)
class LocalWorkEvidenceFileObserverAndroidTest {
    @Test fun directoryWatchDetectsInPlaceChangesAmongHundredsOfArtifacts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "work-watch-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val reports = File(root, "reports").apply { mkdirs() }
            val paths = (0..300).map { "reports/result-$it.txt" }
            paths.forEach { File(root, it).writeText("original") }
            val target = File(reports, "result-300.txt")
            assertEquals(2, workEvidenceWatchTargets(root, emptyList(), paths).size)
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(6_000) {
                    watchLocalWorkEvidenceFiles(root, emptyList(), paths).first()
                }
            }
            // Repeated writes only account for instrumentation scheduling while
            // the callbackFlow registers; production never polls the filesystem.
            withTimeout(5_000) {
                while (!received.isCompleted) {
                    target.appendText("changed")
                    delay(40)
                }
            }
            received.await()
            assertTrue(target.readText().contains("changed"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun parentWatchDetectsAtomicReplacementOfAnExistingArtifact() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "work-rename-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val directory = File(root, "reports").apply { mkdirs() }
            val target = File(directory, "result.txt").apply { writeText("old") }
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(6_000) {
                    watchLocalWorkEvidenceFiles(root, emptyList(), listOf("reports/result.txt")).first()
                }
            }
            withTimeout(5_000) {
                var round = 0
                while (!received.isCompleted) {
                    val staged = File(directory, ".new-$round").apply { writeText("new-$round") }
                    check(staged.renameTo(target))
                    round += 1
                    delay(40)
                }
            }
            received.await()
            assertTrue(target.readText().startsWith("new-"))
        } finally {
            root.deleteRecursively()
        }
    }
}
