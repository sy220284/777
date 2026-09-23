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
            val managed = ManagedProcess.start(ProcessBuilder(resolved)
                .directory(directory)
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = searchPaths().joinToString(File.pathSeparator) { it.path }
                    environment().putAll(baseEnvironment())
                }
            )
            val process = managed.process
            val id = "term-" + UUID.randomUUID().toString().replace("-", "").take(16)
            sessions[id] = Session(managed, process.outputStream, process.inputStream)
            id
        }

    override suspend fun write(sessionId: String, input: String) = withContext(Dispatchers.IO) {
        val session = requireSession(sessionId)
        session.input.write(input.toByteArray())
        session.input.flush()
    }

    override suspend fun read(sessionId: String): String = withContext(Dispatchers.IO) {
        val session = requireSession(sessionId)
        val available = session.output.available()
        if (available <= 0) return@withContext ""
        val bytes = ByteArray(minOf(available, MAX_READ_BYTES))
        val count = session.output.read(bytes)
        if (count <= 0) "" else bytes.decodeToString(0, count)
    }

    override suspend fun close(sessionId: String) = withContext(Dispatchers.IO) {
        val session = sessions.remove(sessionId) ?: return@withContext
        session.managed.terminate()
        runCatching { session.input.close() }
        runCatching { session.output.close() }
    }

    fun isAlive(sessionId: String): Boolean = sessions[sessionId]?.managed?.process?.isAlive == true

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

    private fun requireSession(id: String): Session =
        sessions[id]?.takeIf { it.managed.process.isAlive } ?: error("终端会话不存在或已结束：$id")

    private companion object {
        const val MAX_READ_BYTES = 64 * 1024
    }
}
