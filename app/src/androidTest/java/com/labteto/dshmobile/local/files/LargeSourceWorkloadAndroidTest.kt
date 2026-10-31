package com.labteto.dshmobile.local.files

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device workloads use real files, not sparse fixtures: 32 MiB streamed chunks,
 * 128 MiB and 256 MiB Kotlin source reads, indexed grep, edit, random access,
 * and repeated analysis. The generator holds only a single source line in RAM.
 */
@RunWith(AndroidJUnit4::class)
class LargeSourceWorkloadAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private val header = "package workload\nobject LargeSource {\n"
    private val comment = "    // SOURCE_FLOW parse -> validate -> transform -> publish; API stability and deterministic branch analysis.\n"
    private val footer =
        "    fun criticalHandler(input: Long): Long = input + 7007L // UNIQUE_LONG_TASK_MARKER\n}\n"

    @Test fun code32MiB_chunkedWriteRestartAndReadAnalyze() {
        case(32, "chunked") { root, report ->
            val workspace = LocalWorkspace(root)
            workspace.write("module.kt", "previous stable version")
            val prefix = header + comment.repeat(1_024)
            var offset = 0L
            var sessionId: String? = null
            report.step("chunk_start") {
                val result = workspace.writeChunk("module.kt", prefix, offset, false)
                offset = offsetFrom(result)
                sessionId = sessionFrom(result)
                assertEquals("previous stable version", File(root, "module.kt").readText())
            }
            val repeatChunk = comment.repeat(20_000)
            val expected = 32L * 1024L * 1024L
            val restarted = LocalWorkspace(root)
            report.step("chunk_resume_32m") {
                while (offset < expected) {
                    val result = restarted.writeChunk("module.kt", repeatChunk, offset, false, sessionId)
                    offset = offsetFrom(result)
                }
                val final = restarted.writeChunk("module.kt", footer, offset, true, sessionId)
                offset = offsetFrom(final)
                assertTrue(final.contains("complete=true"))
            }
            val file = File(root, "module.kt")
            assertTrue(file.length() >= expected)
            assertEquals(offset, file.length())
            report.step("chunk_read_32m") {
                val endLine = 2 + 1_024 + (offset - header.toByteArray().size -
                    (comment.toByteArray().size * 1_024L) - footer.toByteArray().size) /
                    repeatChunk.toByteArray().size * 20_000L + 1
                assertTrue(restarted.read("module.kt", endLine.toInt(), endLine.toInt() + 3)
                    .contains("criticalHandler"))
                assertTrue(restarted.preview("module.kt", offset = offset - footer.toByteArray().size)
                    .text.orEmpty().contains("criticalHandler"))
            }
            report.step("chunk_integrity_32m") {
                assertEquals(offset, file.length())
                assertTrue(restarted.readRawPage("module.kt", offset - 64, 64).isNotEmpty())
                assertFalse(root.listFiles().orEmpty().any { it.name.contains(".write-in-progress-") })
            }
        }
    }

    @Test fun code128MiB_longIndexedReadSearchAndEdit() {
        sourceWorkload(128, grep = true, edit = true)
    }

    @Test fun code256MiB_longIndexedReadAnalysisAndEdit() {
        sourceWorkload(256, grep = true, edit = true)
    }

    private fun sourceWorkload(sizeMiB: Int, grep: Boolean, edit: Boolean) {
        case(sizeMiB, "source") { root, report ->
            val expectedBodyBytes = sizeMiB.toLong() * 1024L * 1024L
            val line = comment.toByteArray(Charsets.UTF_8)
            val count = expectedBodyBytes / line.size
            val fullLength = header.toByteArray().size.toLong() + count * line.size +
                footer.toByteArray().size
            val workspace = LocalWorkspace(root)
            var published = 0L
            report.step("stream_write") {
                val input = SequenceInputStream(
                    SequenceInputStream(ByteArrayInputStream(header.toByteArray()),
                        RepeatedLineInput(line, count)),
                    ByteArrayInputStream(footer.toByteArray()),
                )
                val written = workspace.writeFromStream("module.kt", input) { published = it }
                assertEquals(fullLength, written)
                assertEquals(fullLength, published)
                assertEquals(fullLength, File(root, "module.kt").length())
            }
            val lastLine = (count + 3L).toInt()
            report.step("indexed_read_tail") {
                val read = workspace.read("module.kt", lastLine, lastLine + 2)
                assertTrue(read.contains("criticalHandler"))
                assertTrue(read.contains("7007L"))
                val index = File(root, ".dsh/text-index").listFiles().orEmpty()
                assertEquals(1, index.size)
                assertTrue(index.single().length() > 100)
            }
            report.step("repeated_code_analysis") {
                repeat(24) { n ->
                    val lineNo = (count / 2 + 3 + n * 11).toInt()
                    assertTrue(workspace.read("module.kt", lineNo, lineNo + 2)
                        .contains("SOURCE_FLOW"))
                    val byteOffset = fullLength - footer.toByteArray().size + (n % 10)
                    val sample = workspace.readRawPage("module.kt", byteOffset, 128)
                    assertTrue(sample.isNotEmpty())
                }
                val view = workspace.preview("module.kt",
                    offset = fullLength - footer.toByteArray().size)
                assertTrue(view.text.orEmpty().contains("criticalHandler"))
                assertEquals(null, view.nextOffset)
                assertTrue(workspace.glob("*.kt").contains("module.kt"))
            }
            if (grep) report.step("multi_page_code_grep") {
                var cursor: String? = null
                var found = false
                val used = HashSet<String>()
                repeat(120) {
                    if (found) return@repeat
                    val result = workspace.search("criticalHandler", "module.kt", cursor = cursor)
                    if (result.contains("module.kt:" + lastLine + ":")) {
                        found = true
                        return@repeat
                    }
                    val next = Regex("cursor=\"([0-9]+:[0-9]+)\"").find(result)
                        ?.groupValues?.get(1) ?: error("源码搜索意外终止，没有找到目标函数：" + result.take(350))
                    check(used.add(next)) { "搜索游标没有进展：" + next }
                    cursor = next
                }
                assertTrue("无法在分页搜索中找到文件尾部函数", found)
            }
            if (edit) report.step("stream_edit_verified") {
                assertEquals("已编辑 module.kt", workspace.edit("module.kt",
                    "fun criticalHandler(input: Long): Long = input + 7007L",
                    "fun validatedHandler(input: Long): Long = input + 9009L"))
                val suffix = workspace.read("module.kt", lastLine, lastLine + 2)
                assertTrue(suffix.contains("validatedHandler"))
                assertFalse(suffix.contains("criticalHandler"))
                assertTrue(suffix.contains("9009L"))
                assertEquals(fullLength + 1L, File(root, "module.kt").length())
            }
            report.step("cold_index_restore") {
                val reopened = LocalWorkspace(root)
                val result = reopened.read("module.kt", lastLine, lastLine + 1)
                assertTrue(result.contains(if (edit) "validatedHandler" else "criticalHandler"))
                assertTrue(File(root, ".dsh/text-index").listFiles().orEmpty().isNotEmpty())
            }
        }
    }

    private fun sessionFrom(reply: String): String =
        Regex("session_id=([0-9a-f-]+)").find(reply)?.groupValues?.get(1)
            ?: error("续写没有返回会话标识：" + reply)

    private fun offsetFrom(reply: String): Long =
        Regex("next_byte=([0-9]+)").find(reply)?.groupValues?.get(1)?.toLong()
            ?: error("续写没有返回有效位置：" + reply)

    private fun case(sizeMiB: Int, mode: String, body: (File, BenchmarkRecorder) -> Unit) {
        val root = File(context.cacheDir, "source-workload-" + UUID.randomUUID())
        check(root.mkdirs())
        val sampler = HeapSampler()
        val report = BenchmarkRecorder(sizeMiB, mode)
        try {
            sampler.start()
            report.step("whole_scenario") { body(root, report) }
            report.emit("complete", 0, sampler.peakJava.get(), sampler.peakNative.get())
        } catch (failure: Throwable) {
            report.emit("failed", 0, sampler.peakJava.get(), sampler.peakNative.get())
            throw failure
        } finally {
            sampler.stop()
            root.deleteRecursively()
        }
    }

    private inner class BenchmarkRecorder(private val sizeMiB: Int, private val mode: String) {
        private val output: File = File(
            checkNotNull(context.getExternalFilesDir(null)), "large-source-benchmarks/results.csv"
        ).also {
            check(it.parentFile?.isDirectory == true || it.parentFile?.mkdirs() == true)
            if (!it.exists()) it.appendText(
                "android_sdk,size_mib,mode,step,elapsed_ms,java_heap_bytes,native_heap_bytes,pss_kb\n")
        }

        fun <T> step(name: String, action: () -> T): T {
            val start = SystemClock.elapsedRealtimeNanos()
            try {
                return action()
            } finally {
                val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
                val vm = Runtime.getRuntime()
                emit(name, elapsed, vm.totalMemory() - vm.freeMemory(),
                    Debug.getNativeHeapAllocatedSize())
            }
        }

        fun emit(name: String, ms: Long, javaBytes: Long, nativeBytes: Long) {
            val line = listOf(android.os.Build.VERSION.SDK_INT, sizeMiB, mode, name,
                ms, javaBytes, nativeBytes, Debug.getPss()).joinToString(",")
            output.appendText(line + "\n")
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "ANDROID_SOURCE_LONG_TASK " + line)
            })
        }
    }

    private class HeapSampler {
        val peakJava = AtomicLong(0)
        val peakNative = AtomicLong(0)
        private val running = AtomicBoolean(false)
        private var thread: Thread? = null
        private val stopSignal = java.util.concurrent.CountDownLatch(1)

        fun start() {
            running.set(true)
            thread = Thread({
                val vm = Runtime.getRuntime()
                while (running.get()) {
                    peakJava.accumulateAndGet(vm.totalMemory() - vm.freeMemory()) { a, b -> maxOf(a, b) }
                    peakNative.accumulateAndGet(Debug.getNativeHeapAllocatedSize()) { a, b -> maxOf(a, b) }
                    if (stopSignal.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                }
            }, "large-source-memory-sampler").apply { isDaemon = true; start() }
        }

        fun stop() {
            running.set(false)
            stopSignal.countDown()
            thread?.join(3_000)
        }
    }

    private class RepeatedLineInput(private val line: ByteArray, times: Long) : InputStream() {
        private var remaining = times * line.size.toLong()
        private var position = 0

        override fun read(): Int {
            if (remaining == 0L) return -1
            val value = line[position].toInt() and 255
            position = (position + 1) % line.size
            remaining--
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            if (remaining == 0L) return -1
            var consumed = 0
            val goal = minOf(length.toLong(), remaining).toInt()
            while (consumed < goal) {
                val n = minOf(goal - consumed, line.size - position)
                System.arraycopy(line, position, bytes, offset + consumed, n)
                consumed += n
                position = (position + n) % line.size
            }
            remaining -= consumed
            return consumed
        }
    }
}
