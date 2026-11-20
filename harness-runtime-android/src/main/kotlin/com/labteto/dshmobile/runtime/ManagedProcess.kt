package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.ProcessResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The system setsid/sh loader must start with Android's platform libraries.
 * Bundled Termux libraries belong only to the actual child command. Pass them
 * through inert environment keys until the trusted shell has printed its PID.
 * No caller command or environment value is interpolated into shell source.
 */
internal fun prepareManagedProcessLauncher(
    builder: ProcessBuilder,
    setsid: String,
    shell: String,
) {
    val originalCommand = builder.command().toList()
    val deferredLinkerVariables = listOf("LD_LIBRARY_PATH", "LD_PRELOAD", "LD_AUDIT")
    val bootstrapEnvironment = builder.environment()
    deferredLinkerVariables.forEach { key ->
        val stagedKey = "DSH_MANAGED_$key"
        require(!bootstrapEnvironment.containsKey(stagedKey)) { "进程启动环境变量冲突：$stagedKey" }
        bootstrapEnvironment.remove(key)?.let { bootstrapEnvironment[stagedKey] = it }
    }
    val launchScript = buildString {
        append("printf '%s\\n' \"\$\$\"; ")
        deferredLinkerVariables.forEach { key ->
            val stagedKey = "DSH_MANAGED_$key"
            append("if [ \"\${$stagedKey+x}\" ]; then ")
            append("export $key=\"\$${stagedKey}\"; unset $stagedKey; fi; ")
        }
        append("exec \"\$@\"")
    }
    builder.command(listOf(setsid, shell, "-c", launchScript, "managed") + originalCommand)
}

/** Each managed command owns a process group; Android 36 has no public Process.pid API. */
class ManagedProcess private constructor(val process: Process, private val groupId: Long, private val shell: String) {
    private val ownedGroups = linkedSetOf(groupId)
    private val ownedSessions = linkedSetOf<Long>()

    /** A PTY child owns a separate session. Register it before allowing that child to exec. */
    @Synchronized fun registerProcessSession(id: Long) {
        check(!closed) { "受管进程已关闭" }
        require(id > 1L) { "进程组编号无效" }
        ownedGroups += id
        ownedSessions += id
    }
    @Synchronized fun terminate() {
        if (closed) return
        closed = true
        val namespace = runCatching { Files.readSymbolicLink(File("/proc/self/ns/pid").toPath()).toString() }.getOrNull()
        // PTY job control assigns each job its own group. The session identity is stable
        // even after its shell exits; /proc/stat lets us include those sibling groups.
        if (ownedSessions.isNotEmpty()) File("/proc").listFiles()?.forEach { entry ->
            if (entry.name.toLongOrNull() == null) return@forEach
            runCatching {
                if (namespace != null && Files.readSymbolicLink(File(entry, "ns/pid").toPath()).toString() != namespace) {
                    return@runCatching
                }
                val fields = File(entry, "stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))
                val status = File(entry, "status").readLines().filter { ':' in it }
                    .associate { it.substringBefore(':') to it.substringAfter(':').trim().substringAfterLast('\t') }
                val session = (if (namespace != null) status["NSsid"] ?: fields.getOrNull(3) else fields.getOrNull(3))?.toLongOrNull()
                val group = (if (namespace != null) status["NSpgid"] ?: fields.getOrNull(2) else fields.getOrNull(2))?.toLongOrNull()
                if (session in ownedSessions) {
                    group?.takeIf { it > 1 }?.let(ownedGroups::add)
                }
            }
        }
        // Positional arguments only; never interpolate a caller-supplied command into this script.
        ownedGroups.toList().asReversed().forEach { group ->
            runCatching {
                val killer = ProcessBuilder(shell, "-c", "kill -KILL -\"\$1\"", "group-cleanup", group.toString()).start()
                if (!killer.waitFor(1, TimeUnit.SECONDS)) killer.destroyForcibly()
            }
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
            prepareManagedProcessLauncher(builder, setsid, shell)
            val process = builder.start()
            try {
                // The child can exit between available() and isAlive, especially
                // for a tiny --version command. A dead process may still have
                // unread PID bytes in the OS pipe; always drain them before
                // treating it as a launcher failure.
                return ManagedProcess(process, readManagedProcessGroup(process), shell)
            } catch (error: Throwable) {
                process.destroyForcibly()
                throw error
            }
        }
    }
}

/**
 * Consume only the PID header. A short-lived executable may exit before the
 * Java pipe reports bytes as available; an exited process is still readable.
 * Never consume the command's stdout after the newline.
 */
internal fun readManagedProcessGroup(process: Process): Long {
    val pid = StringBuilder()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (System.nanoTime() < deadline && pid.length < 20) {
        if (process.inputStream.available() == 0 && process.isAlive) {
            Thread.sleep(2)
            continue
        }
        val byte = process.inputStream.read()
        if (byte == 10) {
            val group = pid.toString().toLongOrNull()
            require(group != null && group > 1L) { "进程组编号无效" }
            return group
        }
        if (byte < 0) {
            val state = if (process.isAlive) "启动器输出已关闭" else "退出码 ${process.exitValue()}"
            val detail = if (process.isAlive) "" else process.errorStream.bufferedReader().readText().takeLast(1400)
            error("进程组握手提前结束（$state，已读 $pid）：$detail")
        }
        require(byte in 48..57) { "进程组握手无效" }
        pid.append(byte.toChar())
    }
    error("进程组启动超时（已读 $pid）")
}

/** Preserve surrogate pairs when bounding UTF-16 process output. */
internal fun truncateProcessOutput(text: String, maxChars: Int): String {
    val end = maxChars.coerceIn(0, text.length)
    val safeEnd = if (
        end > 0 && end < text.length &&
        text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()
    ) end - 1 else end
    return text.substring(0, safeEnd)
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
                    if (keep > 0) progress?.invoke(truncateProcessOutput(output.toString("UTF-8"), maxChars))
                }
                val text = output.toString("UTF-8")
                return truncateProcessOutput(text, maxChars) + if (truncated || text.length > maxChars) "\n[输出已截断]" else ""
            }
            val stdout = async { capture(process.inputStream, onProgress) }
            val stderr = async { capture(process.errorStream, null) }
            val stdinFailure = AtomicReference<Throwable?>(null)
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
                        writer.await().exceptionOrNull()?.let { failure ->
                            if (!stdin.isNullOrEmpty()) stdinFailure.compareAndSet(null, failure)
                        }
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
                        writer.await().exceptionOrNull()?.let { failure ->
                            if (!stdin.isNullOrEmpty()) stdinFailure.compareAndSet(null, failure)
                        }
                        runInterruptible { process.waitFor() }
                    }
                }
                teardownStarted.set(true)
                managed.terminate()
                val failedStdin = stdinFailure.get() != null
                val stderrText = stderr.await() + if (failedStdin) {
                    "\n[STDIN_WRITE_FAILED] 子进程未完整接收输入；结果不可视为成功"
                } else ""
                val reportedCode = if (failedStdin && code == 0) -1 else code ?: -1
                ProcessResult(
                    reportedCode, stdout.await(), stderrText, timedOut = code == null,
                    stdinWriteFailed = failedStdin,
                )
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
