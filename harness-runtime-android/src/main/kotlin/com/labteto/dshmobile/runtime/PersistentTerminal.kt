package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.HarnessTerminalProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import java.nio.ByteBuffer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal data class TerminalReadResult(val output: String, val exited: Boolean, val exitCode: Int?)

/**
 * Persistent terminal owner. Legacy open uses pipes; openPty uses the bundled Python POSIX bridge
 * for a controlling terminal, job control, control characters and window resizing.
 */
class PersistentPipeTerminalProvider(
    private val defaultWorkingDirectory: File? = null,
    private val extraSearchPaths: () -> List<File> = { emptyList() },
    private val baseEnvironment: () -> Map<String, String> = { emptyMap() },
) : HarnessTerminalProvider {
    private data class Session(
        val managed: ManagedProcess,
        val input: java.io.OutputStream,
        val output: java.io.InputStream,
        val pty: Boolean = false,
        val bufferedOutput: TerminalOutputBuffer = TerminalOutputBuffer(output),
        val inputLock: Any = Any(),
        val outputLock: Any = Any(),
        val workingDirectory: File? = null,
        val isolated: Boolean = false,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    // A terminal session lives in this process/provider instance. Reconstructed agents must
    // never mistake a prior runtime's session ID for an accessible live process.
    private val runtimeEpoch = UUID.randomUUID().toString().replace("-", "").take(10)
    @Volatile private var ptyObserved = false

    fun runtimeGeneration(): String = runtimeEpoch

    fun isFromPreviousRuntime(sessionId: String): Boolean =
        isPriorTerminalRuntimeSession(sessionId, runtimeEpoch)

    /** A real controlling terminal, using only the Python runtime already shipped in the APK. */
    suspend fun openPty(
        command: List<String>, workingDirectory: String? = null, rows: Int = 24, columns: Int = 80,
    ): String {
        require(rows in 1..65535 && columns in 1..65535) { "终端尺寸必须在 1..65535" }
        require(command.isNotEmpty()) { "终端命令不能为空" }
        var managed: ManagedProcess? = null
        var publishedId: String? = null
        try {
            return withContext(Dispatchers.IO) {
                val python = searchPaths().asSequence().map { File(it, "python3") }
                    .firstOrNull { it.isFile && it.canExecute() }
                    ?: error("PTY_PYTHON_UNAVAILABLE：内置 Python 尚未准备好")
                val script = requireNotNull(PersistentPipeTerminalProvider::class.java.getResourceAsStream("/com/labteto/dshmobile/runtime/pty_bridge.py")) {
                    "PTY_BRIDGE_MISSING：安装包缺少 PTY 传输代码"
                }.bufferedReader().use { it.readText() }
                val directory = workingDirectory?.let(::File) ?: defaultWorkingDirectory
                if (directory != null) require(directory.isDirectory) { "工作目录不存在：${directory.path}" }
                val childBuilder = secureIsolatedProcessBuilder(ProcessBuilder(resolveCommand(command)).directory(directory).apply {
                    environment()["PATH"] = searchPaths().joinToString(File.pathSeparator) { it.path }
                    environment().putAll(baseEnvironment())
                }, extraSearchPaths(), baseEnvironment())
                val process = ManagedProcess.start(ProcessBuilder(
                    python.path, "-P", "-s", "-u", "-c", script,
                    JsonArray(childBuilder.command().map(::JsonPrimitive)).toString(),
                    rows.toString(), columns.toString(),
                ).directory(directory).redirectErrorStream(true).apply {
                    environment()["PATH"] = searchPaths().joinToString(File.pathSeparator) { it.path }
                    environment().clear(); environment().putAll(childBuilder.environment())
                })
                managed = process
                val child = awaitPtyChild(process)
                process.registerProcessSession(child)
                currentCoroutineContext().ensureActive()
                // The child is blocked behind its private gate until ownership is registered.
                writePtyFrame(process.process.outputStream, 'S')
                val id = "term-$runtimeEpoch-" + UUID.randomUUID().toString().replace("-", "").take(12)
                sessions[id] = Session(process, process.process.outputStream, process.process.inputStream, pty = true, workingDirectory = directory, isolated = currentCoroutineContext()[ProcessIsolationContext] != null)
                publishedId = id
                ptyObserved = true
                id
            }
        } catch (error: Throwable) {
            // Includes prompt cancellation on the IO-to-caller handoff after publication.
            withContext(NonCancellable + Dispatchers.IO) {
                val session = publishedId?.let(sessions::remove)
                if (session != null) release(session) else managed?.let {
                    it.terminate()
                    runCatching { it.process.outputStream.close() }
                    runCatching { it.process.inputStream.close() }
                }
            }
            throw error
        }
    }

    suspend fun resize(sessionId: String, rows: Int, columns: Int) = send(sessionId) { session ->
        require(rows in 1..65535 && columns in 1..65535) { "终端尺寸必须在 1..65535" }
        require(session.pty) { "TERMINAL_NOT_PTY：管道会话不支持终端尺寸" }
        synchronized(session.inputLock) {
            writePtyFrame(session.input, 'R', ByteBuffer.allocate(8).putInt(rows).putInt(columns).array())
        }
    }

    fun isPty(sessionId: String): Boolean = sessions[sessionId]?.pty == true

    fun terminalType(sessionId: String): String? = sessions[sessionId]?.let { if (it.pty) "pty" else "pipe" }

    fun workingDirectory(sessionId: String): File? = sessions[sessionId]?.workingDirectory

    fun exitCode(sessionId: String): Int? = sessions[sessionId]?.managed?.process?.let {
        if (it.isAlive) null else it.exitValue()
    }

    override suspend fun open(command: List<String>, workingDirectory: String?): String {
        var owned: ManagedProcess? = null
        var published: String? = null
        try {
            return withContext(Dispatchers.IO) {
                require(command.isNotEmpty()) { "终端命令不能为空" }
                val directory = workingDirectory?.let(::File) ?: defaultWorkingDirectory
                if (directory != null) require(directory.isDirectory) { "工作目录不存在：${directory.path}" }
                val resolved = resolveCommand(command)
                val managed = ManagedProcess.start(
                    secureIsolatedProcessBuilder(ProcessBuilder(resolved)
                        .directory(directory)
                        .redirectErrorStream(true)
                        .apply {
                            environment()["PATH"] = searchPaths().joinToString(File.pathSeparator) { it.path }
                            environment().putAll(baseEnvironment())
                        }, extraSearchPaths(), baseEnvironment()),
                )
                val process = managed.process
                owned = managed
                val id = "term-" + runtimeEpoch + "-" +
                    UUID.randomUUID().toString().replace("-", "").take(12)
                sessions[id] = Session(managed, process.outputStream, process.inputStream, workingDirectory = directory, isolated = currentCoroutineContext()[ProcessIsolationContext] != null)
                published = id
                id
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                val session = published?.let(sessions::remove)
                if (session != null) release(session) else owned?.terminate()
            }
            throw error
        }
    }

    override suspend fun write(sessionId: String, input: String) = send(sessionId) { session ->
        synchronized(session.inputLock) {
            val bytes = input.toByteArray()
            if (session.pty) {
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(bytes.size, offset + MAX_READ_BYTES)
                    writePtyFrame(session.input, 'D', bytes.copyOfRange(offset, end))
                    offset = end
                }
            } else {
                session.input.write(bytes)
                session.input.flush()
            }
        }
    }

    private suspend fun send(sessionId: String, action: (Session) -> Unit): Unit = supervisorScope {
        val session = requireLiveSession(sessionId)
        require(currentCoroutineContext()[ProcessIsolationContext] == null || session.isolated) {
            "TERMINAL_ISOLATION_MISMATCH：隔离任务不能控制此前的普通终端"
        }
        val writer = async(Dispatchers.IO) { action(session) }
        try {
            writer.await()
        } catch (cancelled: CancellationException) {
            // await is cancellable even when the child stopped consuming stdin. Close the
            // process before the scope joins the blocked OS writer, releasing its pipe.
            // Teardown's broken pipe must not replace the caller's cancellation cause.
            withContext(NonCancellable + Dispatchers.IO) { close(sessionId) }
            throw cancelled
        }
    }

    override suspend fun read(sessionId: String): String = readResult(sessionId).output

    internal suspend fun readResult(sessionId: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = requireKnownSession(sessionId)
        synchronized(session.outputLock) {
            val text = session.bufferedOutput.read()
            val exited = !session.managed.process.isAlive && session.bufferedOutput.isDrained()
            val code = if (exited) session.managed.process.exitValue() else null
            if (exited) {
                releaseIfCurrent(sessionId, session)
            }
            TerminalReadResult(text, exited, code)
        }
    }

    override suspend fun close(sessionId: String) = withContext(Dispatchers.IO) {
        val session = sessions.remove(sessionId) ?: return@withContext
        release(session)
        Unit
    }

    suspend fun closeAll() = withContext(Dispatchers.IO) {
        sessions.keys.toList().forEach { id -> sessions.remove(id)?.let(::release) }
    }

    fun isAlive(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        if (session.managed.process.isAlive) return true

        // Preserve a finished process just long enough for terminal_read to drain its final output.
        // Empty finished sessions can be released immediately instead of accumulating forever.
        if (session.bufferedOutput.isDrained()) {
            releaseIfCurrent(sessionId, session)
        }
        return false
    }

    fun nativePtyAvailable(): Boolean = ptyObserved

    private fun resolveCommand(command: List<String>): List<String> {
        val executable = command.first()
        if (executable.contains(File.separatorChar)) return command
        val resolved = searchPaths()
            .asSequence()
            .map { directory -> File(directory, executable) }
            .firstOrNull(File::canExecute)
            ?: return command
        return listOf(resolved.absolutePath) + command.drop(1)
    }

    private fun searchPaths(): List<File> {
        val inherited = System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map(::File)
        val androidDefaults = listOf(
            File("/system/bin"),
            File("/system/xbin"),
            File("/product/bin"),
            File("/vendor/bin"),
        )
        return (extraSearchPaths() + inherited + androidDefaults).distinctBy { it.path }
    }

    private fun requireKnownSession(id: String): Session =
        sessions[id] ?: if (isFromPreviousRuntime(id)) {
            error("TERMINAL_RUNTIME_RESET：终端执行环境已重建，旧会话失效。请检查之前命令的持久产物，重新打开终端；不要直接重复可能有副作用的写操作。")
        } else {
            error("TERMINAL_SESSION_NOT_FOUND：终端会话已结束、被关闭或不存在：$id")
        }

    private fun requireLiveSession(id: String): Session {
        val session = requireKnownSession(id)
        if (session.managed.process.isAlive) return session
        // A failed write must not discard unread final output or the exit receipt.
        error("终端会话已结束：$id")
    }

    private fun releaseIfCurrent(id: String, session: Session) {
        if (sessions.remove(id, session)) release(session)
    }

    private fun release(session: Session) {
        session.managed.terminate()
        runCatching { session.input.close() }
        runCatching { session.output.close() }
        session.bufferedOutput.awaitClosed()
    }

    private companion object {
        const val MAX_READ_BYTES = 64 * 1024
    }
}

/**
 * Only epoch-bearing IDs identify a *proven* prior runtime. Legacy or malformed identifiers
 * remain ordinary unknown sessions; do not pretend they definitely came from a reset.
 */
internal fun isPriorTerminalRuntimeSession(sessionId: String, currentEpoch: String): Boolean {
    val match = Regex("""^term-([a-f0-9]{10})-[a-f0-9]{12}$""").matchEntire(sessionId)
        ?: return false
    return match.groupValues[1] != currentEpoch
}
