package com.labteto.dshmobile.interop.lsp

import com.labteto.dshmobile.harness.plugin.*
import com.labteto.dshmobile.harness.tools.*
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** User-configured, workspace-scoped language server. Starting a process always needs approval. */
class LspPlugin(
    private val root: File,
    private val json: Json,
    private val command: () -> List<String>,
    private val commandResolver: (List<String>) -> List<String> = { it },
    private val environment: () -> Map<String, String> = { emptyMap() },
) : HarnessPlugin {
    override val id = "local-language-server"
    private val mutex = Mutex()
    private var client: LspProcessClient? = null
    private val versions = mutableMapOf<String, Int>()

    override suspend fun install(context: HarnessContext) {
        for (name in names) {
            val processTool = name in setOf("lsp_start", "lsp_stop")
            context.tools.register(HarnessTool(
                name = name, schema = schema(name),
                access = if (processTool) ToolAccess.PROCESS else ToolAccess.READ_ONLY,
                approvalPolicy = if (processTool) ToolApprovalPolicy.ALWAYS else ToolApprovalPolicy.NEVER,
                executor = HarnessToolExecutor { _, input, _ -> mutex.withLock { execute(name, input) } },
            ))
        }
    }

    suspend fun stop() = mutex.withLock { closeClient() }

    override suspend fun uninstall(context: HarnessContext) {
        stop()
        names.forEach(context.tools::unregister)
    }

    private fun closeClient() {
        client?.close()
        client = null
        versions.clear()
    }

    private suspend fun execute(name: String, input: JsonObject): ToolResult {
        if (name == "lsp_stop") { closeClient(); return ToolResult("语言服务器已停止") }
        if (name == "lsp_start") {
            val configured = command()
            require(configured.isNotEmpty()) { "请先在设置中配置语言服务器启动命令" }
            closeClient()
            val next = LspProcessClient(configured, json, root, commandResolver, environment)
            try { next.initialize(root.canonicalFile.toURI().toString()); client = next }
            catch (error: Throwable) { next.close(); throw error }
            return ToolResult("语言服务器已启动")
        }
        if (name == "lsp_status") return ToolResult(if (client == null) "语言服务器未启动" else "语言服务器已启动")
        val active = client ?: return ToolResult("请先调用 lsp_start 启动语言服务器", isError = true)
        val file = workspaceFile(root, input["path"]?.jsonPrimitive?.content.orEmpty())
        require(file.isFile && file.length() <= 1024 * 1024) { "文件不存在或超过 1 MiB" }
        val uri = file.toURI().toString()
        val version = (versions[uri] ?: 0) + 1
        val language = input["language_id"]?.jsonPrimitive?.contentOrNull ?: file.extension
        val text = file.readText()
        try {
            if (version == 1) active.didOpen(uri, language, version, text)
            else active.didChange(uri, version, text)
            versions[uri] = version
            val line = input["line"]?.jsonPrimitive?.intOrNull ?: 0
            val character = input["character"]?.jsonPrimitive?.intOrNull ?: 0
            require(line >= 0 && character >= 0) { "行号和列号必须为从零开始的非负整数" }
            val result = when (name) {
                "lsp_definition" -> active.definition(uri, line, character)
                "lsp_references" -> active.references(uri, line, character)
                "lsp_hover" -> active.hover(uri, line, character)
                "lsp_symbols" -> active.documentSymbols(uri)
                else -> error("未知语言工具")
            }
            val output = result.toString()
            return ToolResult(if (output.length <= 40_000) output else output.take(40_000) + "\n[结果过长，已截断]")
        } catch (error: Throwable) {
            closeClient()
            throw error
        }
    }

    private fun schema(name: String) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", when (name) {
                "lsp_start" -> "启动设置中配置的语言服务器，需要进程执行审批"
                "lsp_stop" -> "停止语言服务器并释放进程"
                "lsp_status" -> "查看语言服务器状态"
                "lsp_definition" -> "查询代码定义；行号从零开始，列号按 UTF-16 从零开始"
                "lsp_references" -> "查询代码引用；行号从零开始，列号按 UTF-16 从零开始"
                "lsp_hover" -> "查询代码类型与说明；行号从零开始，列号按 UTF-16 从零开始"
                else -> "列出文件中的代码符号"
            })
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    if (name !in setOf("lsp_start", "lsp_stop", "lsp_status")) {
                        put("path", buildJsonObject { put("type", "string") })
                        put("language_id", buildJsonObject { put("type", "string") })
                        put("line", buildJsonObject { put("type", "integer"); put("minimum", 0) })
                        put("character", buildJsonObject { put("type", "integer"); put("minimum", 0) })
                    }
                })
                if (name !in setOf("lsp_start", "lsp_stop", "lsp_status")) {
                    put("required", buildJsonArray { add(JsonPrimitive("path")) })
                }
                put("additionalProperties", false)
            })
        })
    }

    companion object {
        private val names = listOf("lsp_start", "lsp_stop", "lsp_status", "lsp_definition", "lsp_references", "lsp_hover", "lsp_symbols")
        internal fun workspaceFile(root: File, path: String): File {
            require(path.isNotBlank() && !File(path).isAbsolute) { "请使用工作区内的相对路径" }
            val base = root.canonicalFile
            val target = File(base, path).canonicalFile
            require(target.toPath().startsWith(base.toPath())) { "语言工具禁止访问工作区之外的文件" }
            return target
        }
    }
}
