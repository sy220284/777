package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.ProcessResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Each managed command owns a process group; Android 36 has no public Process.pid API. */
class ManagedProcess private constructor(val process: Process, private val groupId: Long, private val shell: String) {
    @Synchronized fun terminate() {
        if (closed) return
        closed = true
        // Positional arguments only; never interpolate a caller-supplied command into this script.
        runCatching {
            val killer = ProcessBuilder(shell, "-c", "kill -KILL -\"\$1\"", "group-cleanup", groupId.toString()).start()
            if (!killer.waitFor(1, TimeUnit.SECONDS)) killer.destroyForcibly()
        }
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
    }
    private var closed = false

    companion object {
        fun start(builder: ProcessBuilder): ManagedProcess {
            val shell = listOf("/system/bin/sh", "/bin/sh").firstOrNull { File(it).canExecute() }
                ?: error("缺少系统 shell，无法建立受管进程")
            val setsid = listOf("/system/bin/setsid", "/usr/bin/setsid", "/bin/setsid")
                .firstOrNull { File(it).canExecute() }
                ?: error("缺少 setsid，拒绝启动无法整体取消的进程")
            val command = builder.command().toList()
            builder.command(listOf(setsid, shell, "-c", "printf '%s\\n' \"\$\$\"; exec \"\$@\"", "managed") + command)
            val process = builder.start()
            try {
                // Read only the fixed startup line, without buffering any command output.
                val pid = StringBuilder()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (System.nanoTime() < deadline && pid.length < 20) {
                    if (process.inputStream.available() == 0) {
                        check(process.isAlive) { "进程组启动失败" }
                        Thread.sleep(2)
                        continue
                    }
                    val byte = process.inputStream.read()
                    if (byte == 10) {
                        val group = pid.toString().toLongOrNull()
                        require(group != null && group > 1) { "进程组编号无效" }
                        return ManagedProcess(process, group, shell)
                    }
                    require(byte in 48..57) { "进程组握手无效" }
                    pid.append(byte.toChar())
                }
                error("进程组启动超时")
            } catch (error: Throwable) {
                process.destroyForcibly()
                throw error
            }
        }
    }
}

suspend fun executeManagedProcess(
    builder: ProcessBuilder,
    stdin: String? = null,
    timeoutMillis: Long,
    maxChars: Int = 1_048_576,
    onProgress: ((String) -> Unit)? = null,
): ProcessResult = withContext(Dispatchers.IO) {
    val managed = ManagedProcess.start(builder)
    val process = managed.process
    try {
        coroutineScope {
            suspend fun capture(input: java.io.InputStream, progress: ((String) -> Unit)?): String {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var truncated = false
                while (true) {
                    val available = runCatching { input.available() }.getOrDefault(0)
                    if (available == 0) {
                        if (!process.isAlive) break
                        delay(10)
                        continue
                    }
                    val count = input.read(buffer, 0, minOf(buffer.size, available))
                    if (count < 0) break
                    val keep = minOf(count, (maxChars * 4 - output.size()).coerceAtLeast(0))
                    output.write(buffer, 0, keep)
                    if (keep < count) truncated = true
                    progress?.invoke(output.toString("UTF-8").take(maxChars))
                }
                val text = output.toString("UTF-8")
                return text.take(maxChars) + if (truncated || text.length > maxChars) "\n[输出已截断]" else ""
            }
            val stdout = async { capture(process.inputStream, onProgress) }
            val stderr = async { capture(process.errorStream, null) }
            val writer = async {
                runCatching {
                    runInterruptible {
                        process.outputStream.bufferedWriter().use { it.write(stdin.orEmpty()) }
                    }
                }
            }
            try {
                val code = withTimeoutOrNull(timeoutMillis.coerceAtLeast(1)) {
                    writer.await().getOrThrow()
                    runInterruptible { process.waitFor() }
                }
                managed.terminate()
                ProcessResult(code ?: -1, stdout.await(), stderr.await(), timedOut = code == null)
            } finally {
                // Must run BEFORE coroutineScope waits for blocked child IO on cancellation.
                managed.terminate()
            }
        }
    } finally {
        managed.terminate()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }
}
