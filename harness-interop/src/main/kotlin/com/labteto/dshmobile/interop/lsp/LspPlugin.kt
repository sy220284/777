package com.labteto.dshmobile.interop.lsp

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Workspace-scoped language intelligence.
 *
 * The app supplies an automatic resolver instead of exposing a launch command in ordinary UI.
 * Semantic queries lazily start the matching server and ask for process approval only when a new
 * external server process is about to be created. Once started, subsequent read-only queries reuse
 * it without repeated prompts.
 */
class LspPlugin(
    private val root: File,
    private val json: Json,
    private val command: (String?) -> List<String>,
    private val commandResolver: (List<String>) -> List<String> = { it },
    private val environment: () -> Map<String, String> = { emptyMap() },
) : HarnessPlugin {
    override val id = "local-language-server"

    private data class ClientHandle(
        val key: String,
        val command: List<String>,
        val client: LspProcessClient,
    )

    private val mutex = Mutex()
    private val clients = linkedMapOf<String, ClientHandle>()
    private val versions = mutableMapOf<String, MutableMap<String, Int>>()

    override suspend fun install(context: HarnessContext) {
        for (name in names) {
            context.tools.register(
                HarnessTool(
                    name = name,
                    schema = schema(name),
                    access = ToolAccess.READ_ONLY,
                    approvalPolicy = ToolApprovalPolicy.NEVER,
                    executor = HarnessToolExecutor { toolContext, input, _ ->
                        mutex.withLock { execute(name, input, toolContext) }
                    },
                ),
            )
        }
    }

    suspend fun stop() = mutex.withLock { closeClientsGracefully() }

    override suspend fun uninstall(context: HarnessContext) {
        stop()
        names.forEach(context.tools::unregister)
    }

    private suspend fun execute(
        name: String,
        input: JsonObject,
        context: ToolContext,
    ): ToolResult {
        if (name == "lsp_status") {
            if (clients.isNotEmpty()) {
                return ToolResult("代码智能已启动 ${clients.size} 个语言服务")
            }
            val detected = runCatching { command(null) }.getOrDefault(emptyList())
            return ToolResult(
                if (detected.isEmpty()) {
                    "当前项目未检测到可运行的语言服务器；代码任务会继续使用文件读取、搜索与编译/测试能力"
                } else {
                    "已检测到可用语言服务器，将在首次语义查询时按需启动"
                },
            )
        }

        if (name == "lsp_workspace_symbols") {
            val query = input["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(query.isNotEmpty()) { "工作区符号查询不能为空" }
            val active = activeClient(relativePath = null, context = context)
            return runWithClient(active) {
                ToolResult(bounded(active.client.workspaceSymbols(query).toString()))
            }
        }

        val file = workspaceFile(root, input["path"]?.jsonPrimitive?.content.orEmpty())
        require(file.isFile && file.length() <= 1024 * 1024) { "文件不存在或超过 1 MiB" }
        val relativePath = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
        val active = activeClient(relativePath, context)
        val uri = file.toURI().toString()
        val clientVersions = versions.getOrPut(active.key) { mutableMapOf() }
        val version = (clientVersions[uri] ?: 0) + 1
        val language = input["language_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: languageId(file)
        val text = file.readText()

        return runWithClient(active) {
            if (version == 1) active.client.didOpen(uri, language, version, text)
            else active.client.didChange(uri, version, text)
            clientVersions[uri] = version

            val line = input["line"]?.jsonPrimitive?.intOrNull ?: 0
            val character = input["character"]?.jsonPrimitive?.intOrNull ?: 0
            require(line >= 0 && character >= 0) { "行号和列号必须为从零开始的非负整数" }

            val result = when (name) {
                "lsp_definition" -> active.client.definition(uri, line, character)
                "lsp_references" -> active.client.references(uri, line, character)
                "lsp_hover" -> active.client.hover(uri, line, character)
                "lsp_implementation" -> active.client.implementation(uri, line, character)
                "lsp_symbols" -> active.client.documentSymbols(uri)
                "lsp_rename_preview" -> {
                    val newName = input["new_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    require(newName.isNotEmpty()) { "新名称不能为空" }
                    active.client.rename(uri, line, character, newName)
                }
                "lsp_diagnostics" -> {
                    val triggerError = try {
                        active.client.documentSymbols(uri)
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        error
                    }
                    val notifications = active.client.drainNotifications()
                    val published = notifications.filter { notification ->
                        notification["method"]?.jsonPrimitive?.contentOrNull == "textDocument/publishDiagnostics" &&
                            notification["params"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull == uri
                    }
                    val latest = published.lastOrNull()?.get("params")?.jsonObject
                    return@runWithClient ToolResult(
                        buildJsonObject {
                            put("published", latest != null)
                            put("diagnostics", latest?.get("diagnostics") ?: buildJsonArray { })
                            triggerError?.message?.let { put("pump_warning", it.take(1_000)) }
                        }.toString(),
                    )
                }
                else -> error("未知语言工具")
            }
            ToolResult(bounded(result.toString()))
        }
    }

    private suspend fun activeClient(
        relativePath: String?,
        context: ToolContext,
    ): ClientHandle {
        val configured = runCatching { command(relativePath) }.getOrElse { error ->
            throw IllegalStateException("语言服务器自动检测失败：${error.message ?: error::class.java.simpleName}", error)
        }
        require(configured.isNotEmpty()) {
            "当前文件/项目未检测到可运行的语言服务器；请继续使用 read、grep、glob、编译或测试完成分析"
        }

        val resolved = commandResolver(configured)
        require(resolved.isNotEmpty()) { "语言服务器解析后的命令不能为空" }
        val key = resolved.joinToString("\\u0000")
        clients[key]?.let { return it }

        val approval = context.approval
            ?: error("首次启动代码智能进程需要人工审批")
        if (!approval(startApprovalTool(resolved))) {
            error("用户拒绝启动代码智能进程")
        }

        val next = LspProcessClient(
            command = configured,
            json = json,
            workingDirectory = root,
            commandResolver = commandResolver,
            environmentProvider = environment,
        )
        try {
            next.initialize(root.canonicalFile.toURI().toString())
        } catch (cancelled: CancellationException) {
            next.close()
            throw cancelled
        } catch (error: Exception) {
            next.close()
            throw IllegalStateException(
                "语言服务器启动失败（${resolved.firstOrNull().orEmpty()}）：${error.message ?: error::class.java.simpleName}",
                error,
            )
        }
        return ClientHandle(key, resolved, next).also { clients[key] = it }
    }

    private fun startApprovalTool(resolved: List<String>): HarnessTool = HarnessTool(
        name = "lsp_start",
        schema = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "lsp_start")
                put("description", "启动代码智能进程：${resolved.firstOrNull().orEmpty()}")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { })
                    put("additionalProperties", false)
                })
            })
        },
        access = ToolAccess.PROCESS,
        approvalPolicy = ToolApprovalPolicy.ALWAYS,
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("代码智能进程已批准") },
    )

    private suspend fun runWithClient(
        handle: ClientHandle,
        block: suspend () -> ToolResult,
    ): ToolResult = try {
        block()
    } catch (error: Throwable) {
        clients.remove(handle.key)
        versions.remove(handle.key)
        handle.client.close()
        throw error
    }

    private suspend fun closeClientsGracefully() {
        val current = clients.values.toList()
        clients.clear()
        versions.clear()
        for (handle in current) {
            try {
                handle.client.shutdown()
            } catch (cancelled: CancellationException) {
                handle.client.close()
                throw cancelled
            } catch (_: Exception) {
                handle.client.close()
            }
        }
    }

    private fun bounded(output: String): String =
        if (output.length <= 40_000) output else output.take(40_000) + "\\n[结果过长，已截断]"

    private fun schema(name: String) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", when (name) {
                "lsp_status" -> "查看当前项目代码智能状态；语言服务器由 777 自动检测并按需启动"
                "lsp_definition" -> "查询代码定义；需要时自动启动匹配的语言服务器"
                "lsp_references" -> "查询代码引用；需要时自动启动匹配的语言服务器"
                "lsp_hover" -> "查询代码类型与说明；需要时自动启动匹配的语言服务器"
                "lsp_implementation" -> "查询接口/抽象成员的实现位置；需要时自动启动匹配的语言服务器"
                "lsp_workspace_symbols" -> "按关键词查询整个工作区的代码符号；需要时自动启动语言服务器"
                "lsp_rename_preview" -> "计算重命名 WorkspaceEdit，只返回修改预览，不直接写文件"
                "lsp_diagnostics" -> "同步当前文件并读取语言服务器诊断；published=false 仅表示本次未收到诊断发布"
                else -> "列出文件中的代码符号；需要时自动启动匹配的语言服务器"
            })
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    if (name == "lsp_workspace_symbols") {
                        put("query", buildJsonObject { put("type", "string") })
                    } else if (name != "lsp_status") {
                        put("path", buildJsonObject { put("type", "string") })
                        put("language_id", buildJsonObject { put("type", "string") })
                        put("line", buildJsonObject { put("type", "integer"); put("minimum", 0) })
                        put("character", buildJsonObject { put("type", "integer"); put("minimum", 0) })
                        if (name == "lsp_rename_preview") {
                            put("new_name", buildJsonObject { put("type", "string") })
                        }
                    }
                })
                when (name) {
                    "lsp_workspace_symbols" ->
                        put("required", buildJsonArray { add(JsonPrimitive("query")) })
                    "lsp_rename_preview" ->
                        put("required", buildJsonArray {
                            add(JsonPrimitive("path"))
                            add(JsonPrimitive("new_name"))
                        })
                    else -> if (name != "lsp_status") {
                        put("required", buildJsonArray { add(JsonPrimitive("path")) })
                    }
                }
                put("additionalProperties", false)
            })
        })
    }

    companion object {
        private val names = listOf(
            "lsp_status",
            "lsp_definition",
            "lsp_references",
            "lsp_hover",
            "lsp_implementation",
            "lsp_symbols",
            "lsp_workspace_symbols",
            "lsp_rename_preview",
            "lsp_diagnostics",
        )

        internal fun workspaceFile(root: File, path: String): File {
            require(path.isNotBlank() && !File(path).isAbsolute) { "请使用工作区内的相对路径" }
            val base = root.canonicalFile
            val target = File(base, path).canonicalFile
            require(target.toPath().startsWith(base.toPath())) { "语言工具禁止访问工作区之外的文件" }
            return target
        }

        internal fun languageId(file: File): String = when (file.extension.lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py", "pyw" -> "python"
            "js", "mjs", "cjs" -> "javascript"
            "jsx" -> "javascriptreact"
            "ts", "mts", "cts" -> "typescript"
            "tsx" -> "typescriptreact"
            "rs" -> "rust"
            "go" -> "go"
            "c", "h" -> "c"
            "cc", "cpp", "cxx", "hh", "hpp", "hxx" -> "cpp"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "xml" -> "xml"
            "html", "htm" -> "html"
            "css" -> "css"
            "md", "markdown" -> "markdown"
            else -> file.extension.lowercase().ifBlank { "plaintext" }
        }
    }
}
