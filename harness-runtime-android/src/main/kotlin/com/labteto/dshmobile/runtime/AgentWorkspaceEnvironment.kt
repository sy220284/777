package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.agent.AgentEnvironmentToolRequest
import com.labteto.dshmobile.harness.agent.AgentEnvironmentTools
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolInvocationContext
import com.labteto.dshmobile.harness.tools.ToolResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** One activation owns file observations and native terminal sessions in a server-configured root. */
class AgentWorkspaceEnvironment private constructor(
    private val root: File,
    private val context: HarnessContext,
    private val plugin: AndroidRuntimePlugin,
    private val process: AndroidProcessRuntime,
) : AgentEnvironmentTools {
    private val observations = mutableMapOf<String, String>()

    override suspend fun execute(request: AgentEnvironmentToolRequest): ToolResult =
        withContext(Dispatchers.IO + ProcessIsolationContext() + ToolInvocationContext(request.sessionId.orEmpty(), request.callId, request.executionId)) {
            when (request.name) {
                "read", "read_file", "write", "write_file", "edit", "edit_file" -> executeFile(request)
                else -> {
                    // Only tools explicitly installed in this environment are callable. Device,
                    // account and model providers are never installed or exported here.
                    val tool = requireNotNull(context.tools.get(request.name)) { "远端环境不支持工具：${request.name}" }
                    require(tool.metadata.environmentBound) { "工具不属于远端执行环境：${request.name}" }
                    context.tools.execute(request.name, request.input, request.rawArguments,
                        ToolContext(sessionId = request.sessionId, allowMutation = request.allowMutation,
                            approval = { true }))
                }
            }
        }

    private fun resolve(path: String): File {
        val candidate = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
        require(candidate != root && candidate.toPath().startsWith(root.toPath())) { "路径必须位于远端工作区内：$path" }
        return candidate
    }

    private suspend fun executeFile(request: AgentEnvironmentToolRequest): ToolResult {
        val operation = request.name.removeSuffix("_file")
        require(operation == "read" || request.allowMutation) { "该任务没有写入权限" }
        val original = request.input.text("path")
        val file = resolve(original)
        val payload = JsonObject(request.input + buildJsonObject {
            put("operation", operation)
            put("path", file.relativeTo(root).invariantSeparatorsPath)
            put("original_path", original)
            observations[file.path]?.let { put("observed", it) }
        })
        val script = requireNotNull(javaClass.getResourceAsStream(
            "/com/labteto/dshmobile/runtime/agent_workspace_files.py")).bufferedReader().use { it.readText() }
        val receipt = process.execute(com.labteto.dshmobile.harness.capability.ProcessRequest(
            listOf("python3", "-P", "-s", "-u", "-c", script), workingDirectory = root.path,
            stdin = payload.toString(),
        ))
        check(!receipt.timedOut && receipt.exitCode == 0) { "远端文件操作中断，须检查实际状态：${receipt.stderr}" }
        val result = com.labteto.dshmobile.harness.agent.AgentBackendProtocol.json.decodeFromString<ToolResult>(receipt.stdout)
        if (result.isError) error(result.content)
        if (operation == "write") observations.remove(file.path)
        else result.fileVersions[original]?.let { observations[file.path] = it }
        return result
    }

    override suspend fun close() { plugin.uninstall(context); observations.clear() }

    companion object {
        /** Runtime paths/environment are supplied by the server administrator, never the model. */
        suspend fun create(root: File,
            runtimePaths: List<File> = System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .filter(String::isNotBlank).map(::File),
            runtimeReadRoots: List<File> = listOf("/usr/lib", "/usr/local/lib", "/lib", "/lib64", "/usr/share", "/etc/ssl/certs").map(::File),
            process: AndroidProcessRuntime = AndroidProcessRuntime(root, extraSearchPaths = runtimePaths,
                inheritEnvironment = false, trustedReadRoots = runtimeReadRoots),
            terminals: PersistentPipeTerminalProvider = PersistentPipeTerminalProvider(defaultWorkingDirectory = root,
                extraSearchPaths = { runtimePaths }, inheritEnvironment = false, trustedReadRoots = runtimeReadRoots),
        ): AgentWorkspaceEnvironment {
            val directory = root.canonicalFile
            require(directory.isDirectory) { "远端工作区不存在：$directory" }
            val context = HarnessContext()
            val plugin = AndroidRuntimePlugin(directory, process, terminals)
            plugin.install(context)
            return AgentWorkspaceEnvironment(directory, context, plugin, process)
        }
    }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
