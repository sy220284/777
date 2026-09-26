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
    private val dynamicSearchPaths: () -> List<File> = { emptyList() },
    private val baseEnvironment: () -> Map<String, String> = { emptyMap() },
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
        val builder = ProcessBuilder(resolvedCommand).directory(workingDirectory).apply {
            environment().clear()
            environment().putAll(processEnvironment(request.environment))
        }
        executeManagedProcess(
            builder = builder,
            stdin = request.stdin,
            timeoutMillis = request.timeoutMillis,
        )
    }

    fun processEnvironment(overrides: Map<String, String> = emptyMap()): Map<String, String> {
        fun validEntry(key: String, value: String): Boolean =
            ENV_NAME.matches(key) &&
                '\u0000' !in value &&
                key !in BLOCKED_ENVIRONMENT_KEYS

        val base = baseEnvironment().filter { (key, value) -> validEntry(key, value) }
        val safeOverrides = overrides.filter { (key, value) -> validEntry(key, value) }
        return buildMap {
            putAll(base)
            putAll(safeOverrides)
            put("PATH", searchPaths().joinToString(File.pathSeparator) { it.path })
        }
    }

    fun resolveCommand(command: List<String>): List<String> {
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
        return (extraSearchPaths + dynamicSearchPaths() + envPaths + androidDefaults)
            .distinctBy { it.path }
    }

    private companion object {
        const val MAX_CAPTURED_PROCESS_CHARS = 1_048_576
        val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val BLOCKED_ENVIRONMENT_KEYS = setOf(
            "PATH",
            "LD_PRELOAD",
            "DYLD_INSERT_LIBRARIES",
        )
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
