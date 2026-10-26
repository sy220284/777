package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.agent.AgentEnvironmentToolRequest
import com.labteto.dshmobile.harness.agent.AgentEnvironmentTools
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolInvocationContext
import com.labteto.dshmobile.harness.tools.ToolResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** One activation owns file observations and native terminal sessions in a server-configured root. */
class AgentWorkspaceEnvironment private constructor(
    private val root: File,
    private val context: HarnessContext,
    private val plugin: AndroidRuntimePlugin,
) : AgentEnvironmentTools {
    private val observations = mutableMapOf<String, String>()

    override suspend fun execute(request: AgentEnvironmentToolRequest): ToolResult =
        withContext(Dispatchers.IO + ToolInvocationContext(request.sessionId.orEmpty(), request.callId, request.executionId)) {
            when (request.name) {
                "read", "read_file" -> {
                    val file = resolve(request.input.text("path"))
                    val before = fingerprint(file)
                    val from = request.input.number("start_line", 1).coerceAtLeast(1)
                    val until = request.input.number("end_line", from + 399).coerceAtLeast(from)
                    val content = file.bufferedReader().useLines { lines -> lines.drop(from - 1)
                        .take(until - from + 1).mapIndexed { index, row -> "${from + index}: $row" }.joinToString("\n") }
                    check(before == fingerprint(file)) { "文件在读取过程中变化，请重新读取" }
                    observations[file.path] = before
                    ToolResult(content, fileVersions = mapOf(request.input.text("path") to before))
                }
                "write", "write_file", "edit", "edit_file" -> {
                    require(request.allowMutation) { "该任务没有写入权限" }
                    val path = request.input.text("path")
                    val file = resolve(path)
                    val content = if (request.name in setOf("edit", "edit_file")) {
                        val observed = requireNotNull(observations[file.path]) { "编辑前必须先读取文件" }
                        check(observed == fingerprint(file)) { "文件在读取后变化，请重新读取" }
                        val source = file.readText()
                        check(observed == fingerprint(file)) { "文件在准备编辑时变化，请重新读取" }
                        val old = request.input.text("old_text")
                        require(old.isNotEmpty()) { "待替换内容不能为空" }
                        val offset = source.indexOf(old)
                        require(offset >= 0 && source.indexOf(old, offset + old.length) < 0) { "待替换内容必须唯一" }
                        source.replaceRange(offset, offset + old.length, request.input.text("new_text"))
                    } else request.input.text("content")
                    file.parentFile.mkdirs()
                    val staged = Files.createTempFile(file.parentFile.toPath(), ".agent-write-", ".tmp")
                    try {
                        Files.write(staged, content.toByteArray(Charsets.UTF_8))
                        Files.move(staged, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(staged) }
                    val version = fingerprint(file)
                    if (request.name in setOf("edit", "edit_file")) observations[file.path] = version
                    else observations.remove(file.path)
                    ToolResult("已写入远端文件 $path", fileVersions = mapOf(path to version))
                }
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

    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override suspend fun close() { plugin.uninstall(context); observations.clear() }

    companion object {
        /** Runtime paths/environment are supplied by the server administrator, never the model. */
        suspend fun create(root: File, process: AndroidProcessRuntime = AndroidProcessRuntime(root),
            terminals: PersistentPipeTerminalProvider = PersistentPipeTerminalProvider(defaultWorkingDirectory = root),
        ): AgentWorkspaceEnvironment {
            val directory = root.canonicalFile
            require(directory.isDirectory) { "远端工作区不存在：$directory" }
            val context = HarnessContext()
            val plugin = AndroidRuntimePlugin(directory, process, terminals)
            plugin.install(context)
            return AgentWorkspaceEnvironment(directory, context, plugin)
        }
    }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.number(key: String, default: Int): Int = get(key)?.jsonPrimitive?.intOrNull ?: default
