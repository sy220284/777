package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.ProcessResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    // Shell work may be arbitrarily long while stdout/stderr is still flowing.
    // Other process clients retain the original absolute timeout by default.
    progressResetsTimeout: Boolean = false,
): ProcessResult = withContext(Dispatchers.IO) {
    val managed = ManagedProcess.start(builder)
    val process = managed.process
    val lastOutputNanos = AtomicLong(System.nanoTime())
    val teardownStarted = AtomicBoolean(false)
    val processPollMillis = 250L
    try {
        coroutineScope {
            suspend fun capture(input: java.io.InputStream, progress: ((String) -> Unit)?): String {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var truncated = false
                while (true) {
                    // available()==0 does not imply EOF, even after waitFor() returns.
                    // Drain both pipes until their writers actually close; otherwise fast
                    // failing commands can lose their final permission/path diagnostics.
                    // Process-group teardown closes the writers on timeout/cancellation.
                    val count = try {
                        runInterruptible { input.read(buffer) }
                    } catch (readFailure: java.io.IOException) {
                        // Terminating a process group can close a pipe from another thread on
                        // Android. That expected teardown must not turn a timed-out command into
                        // an IO failure, but genuine read failures before teardown still surface.
                        if (!teardownStarted.get()) throw readFailure
                        break
                    }
                    if (count < 0) break
                    if (count > 0) lastOutputNanos.set(System.nanoTime())
                    val keep = minOf(count, (maxChars * 4 - output.size()).coerceAtLeast(0))
                    output.write(buffer, 0, keep)
                    if (keep < count) truncated = true
                    if (keep > 0) progress?.invoke(output.toString("UTF-8").take(maxChars))
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
                val effectiveTimeoutMillis = timeoutMillis.coerceAtLeast(1)
                val code = if (progressResetsTimeout) {
                    // Guard stdin startup too: a blocked pipe must never stall this task forever.
                    val wrote = withTimeoutOrNull(effectiveTimeoutMillis) {
                        writer.await().getOrThrow()
                        true
                    } ?: false
                    if (!wrote) null else {
                        var exitCode: Int? = null
                        val idleNanos = TimeUnit.MILLISECONDS.toNanos(effectiveTimeoutMillis)
                        while (true) {
                            val finished = runInterruptible {
                                process.waitFor(processPollMillis, TimeUnit.MILLISECONDS)
                            }
                            if (finished) {
                                exitCode = process.exitValue()
                                break
                            }
                            if (System.nanoTime() - lastOutputNanos.get() >= idleNanos) break
                        }
                        exitCode
                    }
                } else {
                    withTimeoutOrNull(effectiveTimeoutMillis) {
                        writer.await().getOrThrow()
                        runInterruptible { process.waitFor() }
                    }
                }
                teardownStarted.set(true)
                managed.terminate()
                ProcessResult(code ?: -1, stdout.await(), stderr.await(), timedOut = code == null)
            } finally {
                // Must run BEFORE coroutineScope waits for blocked child IO on cancellation.
                teardownStarted.set(true)
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
