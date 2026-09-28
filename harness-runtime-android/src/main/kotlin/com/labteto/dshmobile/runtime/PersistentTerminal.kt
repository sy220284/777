package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.HarnessTerminalProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android replacement for a desktop PTY when no native pseudo-terminal bridge is installed.
 *
 * It preserves one interactive process and stdin/stdout across calls. Terminal features that require
 * a controlling TTY (window size, job control, full-screen curses applications) are intentionally
 * reported as unsupported rather than faked.
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
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    override suspend fun open(command: List<String>, workingDirectory: String?): String =
        withContext(Dispatchers.IO) {
            require(command.isNotEmpty()) { "终端命令不能为空" }
            val directory = workingDirectory?.let(::File) ?: defaultWorkingDirectory
            if (directory != null) require(directory.isDirectory) { "工作目录不存在：${directory.path}" }
            val resolved = resolveCommand(command)
            val managed = ManagedProcess.start(
                ProcessBuilder(resolved)
                    .directory(directory)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["PATH"] = searchPaths().joinToString(File.pathSeparator) { it.path }
                        environment().putAll(baseEnvironment())
                    },
            )
            val process = managed.process
            val id = "term-" + UUID.randomUUID().toString().replace("-", "").take(16)
            sessions[id] = Session(managed, process.outputStream, process.inputStream)
            id
        }

    override suspend fun write(sessionId: String, input: String) = withContext(Dispatchers.IO) {
        val session = requireLiveSession(sessionId)
        session.input.write(input.toByteArray())
        session.input.flush()
    }

    override suspend fun read(sessionId: String): String = withContext(Dispatchers.IO) {
        val session = requireKnownSession(sessionId)
        val available = runCatching { session.output.available() }.getOrDefault(0)
        if (available <= 0) {
            if (!session.managed.process.isAlive) releaseIfCurrent(sessionId, session)
            return@withContext ""
        }

        val bytes = ByteArray(minOf(available, MAX_READ_BYTES))
        val count = session.output.read(bytes)
        val text = if (count <= 0) "" else bytes.decodeToString(0, count)

        if (
            !session.managed.process.isAlive &&
            runCatching { session.output.available() }.getOrDefault(0) <= 0
        ) {
            releaseIfCurrent(sessionId, session)
        }
        text
    }

    override suspend fun close(sessionId: String) = withContext(Dispatchers.IO) {
        val session = sessions.remove(sessionId) ?: return@withContext
        release(session)
        Unit
    }

    fun isAlive(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        if (session.managed.process.isAlive) return true

        // Preserve a finished process just long enough for terminal_read to drain its final output.
        // Empty finished sessions can be released immediately instead of accumulating forever.
        if (runCatching { session.output.available() }.getOrDefault(0) <= 0) {
            releaseIfCurrent(sessionId, session)
        }
        return false
    }

    fun nativePtyAvailable(): Boolean = false

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
        sessions[id] ?: error("终端会话不存在：$id")

    private fun requireLiveSession(id: String): Session {
        val session = requireKnownSession(id)
        if (session.managed.process.isAlive) return session
        releaseIfCurrent(id, session)
        error("终端会话已结束：$id")
    }

    private fun releaseIfCurrent(id: String, session: Session) {
        if (sessions.remove(id, session)) release(session)
    }

    private fun release(session: Session) {
        session.managed.terminate()
        runCatching { session.input.close() }
        runCatching { session.output.close() }
    }

    private companion object {
        const val MAX_READ_BYTES = 64 * 1024
    }
}
