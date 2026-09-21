package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.HarnessProcessRuntime
import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.harness.capability.ProcessResult
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class AndroidProcessRuntime(
    private val defaultWorkingDirectory: File? = null,
    private val extraSearchPaths: List<File> = emptyList(),
) : HarnessProcessRuntime {

    override fun isCommandAvailable(command: String): Boolean {
        if (command.isBlank()) return false
        if (command.contains(File.separatorChar)) return File(command).canExecute()
        return searchPaths().any { directory -> File(directory, command).canExecute() }
    }

    override suspend fun execute(request: ProcessRequest): ProcessResult = withContext(Dispatchers.IO) {
        require(request.command.isNotEmpty()) { "进程命令不能为空" }
        val workingDirectory = request.workingDirectory?.let(::File) ?: defaultWorkingDirectory
        if (workingDirectory != null) require(workingDirectory.isDirectory) {
            "工作目录不存在：${workingDirectory.path}"
        }

        val resolvedCommand = resolveCommand(request.command)
        val process = ProcessBuilder(resolvedCommand)
            .directory(workingDirectory)
            .apply {
                environment()["PATH"] = searchPaths().joinToString(File.pathSeparator) { it.path }
                environment().putAll(request.environment)
            }
            .start()

        try {
            request.stdin?.let { input ->
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(input)
                    writer.flush()
                }
            } ?: process.outputStream.close()

            coroutineScope {
                val stdout = async { process.inputStream.bufferedReader().use { it.readText() } }
                val stderr = async { process.errorStream.bufferedReader().use { it.readText() } }
                val completed = runInterruptible {
                    process.waitFor(request.timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                }
                if (!completed) {
                    process.destroyForcibly()
                    process.waitFor()
                    return@coroutineScope ProcessResult(
                        exitCode = -1,
                        stdout = stdout.await(),
                        stderr = stderr.await(),
                        timedOut = true,
                    )
                }
                ProcessResult(
                    exitCode = process.exitValue(),
                    stdout = stdout.await(),
                    stderr = stderr.await(),
                )
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
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

    private fun searchPaths(): List<File> {
        val envPaths = System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map(::File)
        val androidDefaults = listOf(
            File("/system/bin"),
            File("/system/xbin"),
            File("/product/bin"),
            File("/vendor/bin"),
        )
        return (extraSearchPaths + envPaths + androidDefaults).distinctBy { it.path }
    }
}

data class RuntimeCommandStatus(
    val command: String,
    val available: Boolean,
)

class RuntimeDiagnostics(
    private val runtime: HarnessProcessRuntime,
) {
    fun commands(names: List<String> = DEFAULT_COMMANDS): List<RuntimeCommandStatus> =
        names.distinct().map { RuntimeCommandStatus(it, runtime.isCommandAvailable(it)) }

    companion object {
        val DEFAULT_COMMANDS = listOf(
            "sh", "git", "python3", "python", "node", "curl", "ssh",
            "rg", "jq", "tar", "zip",
        )
    }
}
