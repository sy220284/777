package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.HarnessTerminalProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistent Android terminal with a real PTY when the JNI bridge is packaged.
 *
 * JVM tests and environments without the native bridge keep the original pipe backend. The
 * fallback is explicit: [nativePtyAvailable] remains false and resize returns false instead of
 * pretending that a pipe process has terminal semantics.
 */
class PersistentPipeTerminalProvider(
    private val defaultWorkingDirectory: File? = null,
    private val extraSearchPaths: () -> List<File> = { emptyList() },
    private val baseEnvironment: () -> Map<String, String> = { emptyMap() },
) : HarnessTerminalProvider {
    private sealed interface Session

    private data class PipeSession(
        val managed: ManagedProcess,
        val input: java.io.OutputStream,
        val output: java.io.InputStream,
    ) : Session

    private data class PtySession(
        val handle: Long,
    ) : Session

    private val sessions = ConcurrentHashMap<String, Session>()

    override suspend fun open(command: List<String>, workingDirectory: String?): String =
        withContext(Dispatchers.IO) {
            require(command.isNotEmpty()) { "终端命令不能为空" }
            val directory = workingDirectory?.let(::File) ?: defaultWorkingDirectory
            if (directory != null) require(directory.isDirectory) { "工作目录不存在：${directory.path}" }
            val resolved = resolveCommand(command)
            val environment = terminalEnvironment()

            val session: Session = if (NativePtyBridge.available) {
                PtySession(
                    NativePtyBridge.open(
                        command = resolved,
                        workingDirectory = directory?.absolutePath,
                        environment = environment,
                    ),
                )
            } else {
                val managed = ManagedProcess.start(
                    ProcessBuilder(resolved)
                        .directory(directory)
                        .redirectErrorStream(true)
                        .apply {
                            environment().clear()
                            environment().putAll(environment)
                        },
                )
                val process = managed.process
                PipeSession(managed, process.outputStream, process.inputStream)
            }

            val id = "term-" + UUID.randomUUID().toString().replace("-", "").take(16)
            sessions[id] = session
            id
        }

    override suspend fun write(sessionId: String, input: String) = withContext(Dispatchers.IO) {
        when (val session = requireLiveSession(sessionId)) {
            is PipeSession -> {
                session.input.write(input.toByteArray())
                session.input.flush()
            }
            is PtySession -> synchronized(session) {
                NativePtyBridge.write(session.handle, input.toByteArray())
            }
        }
    }

    override suspend fun read(sessionId: String): String = withContext(Dispatchers.IO) {
        val session = requireKnownSession(sessionId)
        when (session) {
            is PipeSession -> readPipe(sessionId, session)
            is PtySession -> {
                val bytes = synchronized(session) {
                    NativePtyBridge.read(session.handle, MAX_READ_BYTES)
                }
                if (
                    bytes.isEmpty() &&
                    !NativePtyBridge.isAlive(session.handle) &&
                    !NativePtyBridge.hasReadable(session.handle)
                ) {
                    releaseIfCurrent(sessionId, session)
                }
                bytes.decodeToString()
            }
        }
    }

    override suspend fun resize(sessionId: String, columns: Int, rows: Int): Boolean =
        withContext(Dispatchers.IO) {
            require(columns in 1..MAX_TERMINAL_DIMENSION) { "终端列数无效：$columns" }
            require(rows in 1..MAX_TERMINAL_DIMENSION) { "终端行数无效：$rows" }
            when (val session = requireKnownSession(sessionId)) {
                is PipeSession -> false
                is PtySession -> synchronized(session) {
                    NativePtyBridge.resize(session.handle, columns, rows)
                }
            }
        }

    override suspend fun close(sessionId: String) = withContext(Dispatchers.IO) {
        val session = sessions.remove(sessionId) ?: return@withContext
        release(session)
        Unit
    }

    fun isAlive(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        val alive = when (session) {
            is PipeSession -> session.managed.process.isAlive
            is PtySession -> synchronized(session) {
                NativePtyBridge.isAlive(session.handle)
            }
        }
        if (alive) return true

        val hasFinalOutput = when (session) {
            is PipeSession -> runCatching { session.output.available() }.getOrDefault(0) > 0
            is PtySession -> synchronized(session) {
                NativePtyBridge.hasReadable(session.handle)
            }
        }
        if (!hasFinalOutput) releaseIfCurrent(sessionId, session)
        return false
    }

    override fun nativePtyAvailable(): Boolean = NativePtyBridge.available

    private fun readPipe(id: String, session: PipeSession): String {
        val available = runCatching { session.output.available() }.getOrDefault(0)
        if (available <= 0) {
            if (!session.managed.process.isAlive) releaseIfCurrent(id, session)
            return ""
        }

        val bytes = ByteArray(minOf(available, MAX_READ_BYTES))
        val count = session.output.read(bytes)
        val text = if (count <= 0) "" else bytes.decodeToString(0, count)

        if (
            !session.managed.process.isAlive &&
            runCatching { session.output.available() }.getOrDefault(0) <= 0
        ) {
            releaseIfCurrent(id, session)
        }
        return text
    }

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

    private fun terminalEnvironment(): Map<String, String> = buildMap {
        putAll(System.getenv())
        put("PATH", searchPaths().joinToString(File.pathSeparator) { it.path })
        putAll(baseEnvironment())
        putIfAbsent("TERM", "xterm-256color")
        putIfAbsent("COLORTERM", "truecolor")
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
        sessions[id] ?: error("终端会话不存在：$id")

    private fun requireLiveSession(id: String): Session {
        val session = requireKnownSession(id)
        if (isSessionAlive(session)) return session
        if (!hasReadableOutput(session)) releaseIfCurrent(id, session)
        error("终端会话已结束：$id")
    }

    private fun isSessionAlive(session: Session): Boolean = when (session) {
        is PipeSession -> session.managed.process.isAlive
        is PtySession -> synchronized(session) { NativePtyBridge.isAlive(session.handle) }
    }

    private fun hasReadableOutput(session: Session): Boolean = when (session) {
        is PipeSession -> runCatching { session.output.available() }.getOrDefault(0) > 0
        is PtySession -> synchronized(session) { NativePtyBridge.hasReadable(session.handle) }
    }

    private fun releaseIfCurrent(id: String, session: Session) {
        if (sessions.remove(id, session)) release(session)
    }

    private fun release(session: Session) {
        when (session) {
            is PipeSession -> {
                session.managed.terminate()
                runCatching { session.input.close() }
                runCatching { session.output.close() }
            }
            is PtySession -> synchronized(session) {
                NativePtyBridge.close(session.handle)
            }
        }
    }

    private companion object {
        const val MAX_READ_BYTES = 64 * 1024
        const val MAX_TERMINAL_DIMENSION = 10_000
    }
}
