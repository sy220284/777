package com.labteto.dshmobile.interop.lsp

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceLease
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolWorkspaceAccess
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import com.labteto.dshmobile.runtime.ProcessIsolationContext
import com.labteto.dshmobile.runtime.secureIsolatedProcessBuilder
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

/** Structured failure for missing, rejected or crashed language servers. */
internal fun lspExtensionFailure(toolName: String, error: Exception): ToolResult {
    val message = error.message.orEmpty()
    val code = when {
        message.contains("未检测到可运行的语言服务器") -> "LSP_SERVER_UNAVAILABLE"
        message.contains("首次下载语言服务器需要人工审批") -> "LSP_INSTALL_APPROVAL_REQUIRED"
        message.contains("只读任务或计划模式不允许下载组件") -> "LSP_INSTALL_NOT_ALLOWED"
        message.contains("拒绝下载") -> "LSP_INSTALL_DECLINED"
        message.contains("下载安装失败") -> "LSP_INSTALL_FAILED"
        message.contains("拒绝启动") || message.contains("需要人工审批") -> "LSP_START_APPROVAL_REQUIRED"
        message.contains("启动失败") || error is IOException -> "LSP_START_FAILED"
        error is IllegalArgumentException -> "LSP_INPUT_INVALID"
        else -> "LSP_REQUEST_FAILED"
    }
    val hint = when (code) {
        "LSP_SERVER_UNAVAILABLE" -> "当前可继续使用 read、grep、glob 和编译测试；如支持按需安装，可批准首次下载。"
        "LSP_START_APPROVAL_REQUIRED" -> "返回当前会话批准语言服务器首次启动，再重新执行查询。"
        "LSP_INSTALL_APPROVAL_REQUIRED" -> "首次下载需要明确批准；可返回主任务确认安装。"
        "LSP_INSTALL_NOT_ALLOWED" -> "只读子任务和计划模式不可安装组件；可继续使用现有文件搜索，或在主任务中安装。"
        "LSP_INSTALL_DECLINED" -> "已取消本次语言服务器下载；可以继续使用文件搜索与编译测试。"
        "LSP_INSTALL_FAILED" -> "语言服务器下载或部署失败；检查网络和存储后重试，原有代码工具仍可使用。"
        "LSP_START_FAILED" -> "核对可执行文件、运行环境和启动命令；修复后重新查询可再次启动。"
        "LSP_INPUT_INVALID" -> "检查路径、大小、语言类型和工具查询参数。"
        else -> "检查语言服务器状态；此次调用停止，下次查询会按需重新启动。"
    }
    return ToolResult(
        content = "$toolName 无法完成：$hint",
        isError = true,
        errorCode = code,
        retryable = false,
        recoveryHint = hint,
    )
}

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
    private val resourceScheduler: HarnessResourceScheduler? = null,
    private val canInstallOnDemand: (String?) -> Boolean = { false },
    private val installOnDemand: suspend (String?) -> List<String> = { emptyList() },
    private val managedStatus: () -> String = { "" },
    private val uninstallOnDemand: (String) -> Boolean = { false },
    private val processReadRoots: () -> List<File> = { emptyList() },
) : HarnessPlugin {
    override val id = "local-language-server"

    private data class ClientHandle(
        val key: String,
        val command: List<String>,
        val client: LspProcessClient,
        val lease: HarnessResourceLease? = null,
    )

    private val mutex = Mutex()
    private val clients = linkedMapOf<String, ClientHandle>()
    private val versions = mutableMapOf<String, MutableMap<String, Int>>()

    override suspend fun install(context: HarnessContext) {
        for (name in names) {
            context.tools.register(
                HarnessTool(
                    name = name,
                    schema = schemaFor(name),
                    access = if (name == "lsp_uninstall_component") ToolAccess.PROCESS else ToolAccess.READ_ONLY,
                    approvalPolicy = if (name == "lsp_uninstall_component") {
                        ToolApprovalPolicy.ALWAYS
                    } else ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.OPTIONAL,
                    metadata = ToolMetadata(
                        family = "LSP",
                        workspaceAccess = if (name == "lsp_uninstall_component") ToolWorkspaceAccess.NONE else ToolWorkspaceAccess.READ,
                        discoveryKeywords = discoveryKeywordsFor(name),
                        requirements = if (name == "lsp_uninstall_component") {
                            listOf("需用户审批；卸载前会停止当前运行的语言服务")
                        } else listOf(
                            "TypeScript/JavaScript 和 Python 可在首次语义查询时经授权按需下载安装；其他语言需已有兼容的本地语言服务器",
                            "语言服务器首次启动需独立进程审批；状态查询及能力发现不会触发下载",
                        ),
                    ),
                    executor = HarnessToolExecutor { toolContext, input, _ ->
                        mutex.withLock {
                            try {
                                execute(name, input, toolContext)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                lspExtensionFailure(name, error)
                            }
                        }
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
        val root = com.labteto.dshmobile.harness.capability.executionWorkspaceRoot(this.root)
        if (name == "lsp_uninstall_component") {
            val component = input["component"]?.jsonPrimitive?.contentOrNull.orEmpty()
            require(component in setOf("typescript", "pyright")) { "未知组件：仅支持 typescript、pyright" }
            // Only release processes belonging to this component; keep other languages live.
            closeClientsGracefully { handle ->
                handle.command.any { it.contains("/components/" + component + "-") }
            }
            check(uninstallOnDemand(component)) { "组件卸载失败：" + component }
            return ToolResult("代码智能组件已卸载：" + component + "；需要时可重新下载")
        }
        if (name == "lsp_status") {
            val liveCount = clients.values.count { it.client.isLive() }
            if (liveCount > 0) {
                return ToolResult("代码智能已初始化 $liveCount 个语言服务；" + managedStatus())
            }
            val detected = runCatching { command(null) }.getOrDefault(emptyList())
            return ToolResult(
                if (detected.isEmpty()) {
                    if (canInstallOnDemand(null)) {
                        "语言服务器尚未安装；首次语义查询会询问是否按需下载"
                    } else {
                        "当前项目未检测到可运行的语言服务器；代码任务会继续使用文件读取、搜索与编译/测试能力"
                    }
                } else {
                    "发现语言服务器候选，需完成实际 LSP 初始化和语义查询才算可用"
                } + "; " + managedStatus(),
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
            val diagnosticsBeforeChange = active.client.diagnosticRevision(uri)
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
                    // Publish is asynchronous. A documentSymbol response cannot guarantee the
                    // server has published diagnostics for the latest document version.
                    val latest = active.client.awaitPublishedDiagnostics(
                        uri = uri,
                        sinceRevision = diagnosticsBeforeChange,
                        version = version,
                    )
                    return@runWithClient ToolResult(
                        buildJsonObject {
                            put("published", latest != null)
                            put("state", if (latest == null) "timeout_waiting_for_publish" else "published")
                            put("diagnostics", latest?.get("diagnostics") ?: buildJsonArray { })
                            put("uri", uri)
                            put("version", version)
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
        val root = com.labteto.dshmobile.harness.capability.executionWorkspaceRoot(this.root)
        val detected = runCatching { command(relativePath) }.getOrElse { error ->
            throw IllegalStateException("语言服务器自动检测失败：${error.message ?: error::class.java.simpleName}", error)
        }
        val configured = if (detected.isEmpty() && canInstallOnDemand(relativePath)) {
            check(context.allowMutation && context.attributes["plan_mode"] != true) {
                "只读任务或计划模式不允许下载组件；请由可写主任务先完成安装"
            }
            val approval = context.approval ?: error("首次下载语言服务器需要人工审批")
            if (!approval(installApprovalTool(relativePath))) {
                error("用户拒绝下载语言服务器")
            }
            try {
                installOnDemand(relativePath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException("语言服务器下载安装失败：" + error.message, error)
            }
        } else detected
        require(configured.isNotEmpty()) {
            "当前文件/项目未检测到可运行的语言服务器；请继续使用 read、grep、glob、编译或测试完成分析"
        }

        val resolved = commandResolver(configured)
        require(resolved.isNotEmpty()) { "语言服务器解析后的命令不能为空" }
        val isolation = currentCoroutineContext()[ProcessIsolationContext] != null
        val key = root.canonicalPath + "\u0000" + resolved.joinToString("\\u0000") + if (isolation) "\u0000isolated" else ""
        clients[key]?.let { cached ->
            if (cached.client.isLive()) return cached
            cached.client.close()
            cached.lease?.close()
            clients.remove(key)
            versions.remove(key)
        }

        val approval = context.approval
            ?: error("首次启动代码智能进程需要人工审批")
        if (!approval(startApprovalTool(resolved))) {
            error("用户拒绝启动代码智能进程")
        }

        val lease = resourceScheduler?.acquire(
            HarnessResourceKind.LANGUAGE_SERVER,
            owner = "lsp:" + resolved.firstOrNull().orEmpty(),
        )
        val prepared = try {
            currentCoroutineContext()[com.labteto.dshmobile.harness.agent.AgentBackendExecutionContext]?.requireCurrent()
            currentCoroutineContext()[com.labteto.dshmobile.harness.agent.AgentRunExecutionContext]?.requireCurrent()
            val trustedEnvironment = environment()
            secureIsolatedProcessBuilder(ProcessBuilder(resolved).directory(root).apply {
                environment().putAll(trustedEnvironment)
            }, trustedEnvironment["PATH"].orEmpty().split(File.pathSeparatorChar).filter(String::isNotBlank).map(::File),
                trustedEnvironment, processReadRoots())
        } catch (failure: Exception) { lease?.close(); throw failure }
        val next = LspProcessClient(
            command = prepared.command().toList(),
            json = json,
            workingDirectory = root,
            commandResolver = { it },
            environmentProvider = { prepared.environment().toMap() },
            replaceEnvironment = isolation,
        )
        try {
            next.initialize(root.canonicalFile.toURI().toString())
        } catch (cancelled: CancellationException) {
            next.close()
            lease?.close()
            throw cancelled
        } catch (error: Exception) {
            next.close()
            lease?.close()
            throw IllegalStateException(
                "语言服务器启动失败（${resolved.firstOrNull().orEmpty()}）：${error.message ?: error::class.java.simpleName}",
                error,
            )
        }
        return ClientHandle(key, resolved, next, lease).also { clients[key] = it }
    }

    private fun installApprovalTool(relativePath: String?): HarnessTool = HarnessTool(
        name = "lsp_install",
        schema = functionToolSchema(
            name = "lsp_install",
            description = "从 npm 官方源按需下载安装语言服务器及依赖：" + (relativePath ?: "当前项目"),
        ),
        access = ToolAccess.NETWORK,
        approvalPolicy = ToolApprovalPolicy.ALWAYS,
        exposure = ToolExposure.INTERNAL,
        metadata = ToolMetadata(family = "LSP"),
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("代码智能组件安装已批准") },
    )

    private fun startApprovalTool(resolved: List<String>): HarnessTool = HarnessTool(
        name = "lsp_start",
        schema = functionToolSchema(
            name = "lsp_start",
            description = "启动代码智能进程：${resolved.firstOrNull().orEmpty()}",
        ),
        access = ToolAccess.PROCESS,
        approvalPolicy = ToolApprovalPolicy.ALWAYS,
        exposure = ToolExposure.INTERNAL,
        metadata = ToolMetadata(
            family = "LSP",
            usageNotes = listOf("仅作为首次启动语言服务器时的内部审批描述，不暴露给模型"),
        ),
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
        handle.lease?.close()
        throw error
    }

    private suspend fun closeClientsGracefully(
        shouldClose: (ClientHandle) -> Boolean = { true },
    ) {
        val current = clients.values.filter(shouldClose)
        current.forEach { handle ->
            clients.remove(handle.key)
            versions.remove(handle.key)
        }
        var cancellation: CancellationException? = null
        for (handle in current) {
            try {
                if (cancellation == null) {
                    handle.client.shutdown()
                } else {
                    handle.client.close()
                }
            } catch (cancelled: CancellationException) {
                handle.client.close()
                cancellation = cancelled
            } catch (_: Exception) {
                handle.client.close()
            } finally {
                handle.lease?.close()
            }
        }
        cancellation?.let { throw it }
    }

    private fun bounded(output: String): String =
        if (output.length <= 40_000) output else output.take(40_000) + "\\n[结果过长，已截断]"

    private fun schemaFor(name: String): JsonObject {
        val description = when (name) {
            "lsp_status" -> "查看当前项目代码智能状态、已安装的语言服务器组件；不触发下载"
            "lsp_uninstall_component" -> "卸载本机已安装的代码智能组件；需用户审批，再次使用时重新下载"
            "lsp_definition" -> "查询函数、变量和符号的精确定义位置；TypeScript/Python 首次使用可经授权下载语言服务"
            "lsp_references" -> "查找符号的跨文件引用与调用关系；TypeScript/Python 首次使用可经授权下载语言服务"
            "lsp_hover" -> "查询变量、函数的类型与悬停说明；需要时启动本地语言服务器"
            "lsp_implementation" -> "查询接口和抽象成员的具体实现位置；需要时启动本地语言服务器"
            "lsp_workspace_symbols" -> "按关键词查询工作区全局代码符号；需要时启动或经授权下载语言服务器"
            "lsp_rename_preview" -> "计算重命名 WorkspaceEdit，只返回修改预览，不直接写文件"
            "lsp_diagnostics" -> "分析当前文件的语义和类型错误，查询本地语言服务器诊断；published=false 表示本次尚未收到诊断发布"
            else -> "列出文件中的代码符号；需要时自动启动匹配的语言服务器"
        }
        val properties = buildJsonObject {
            if (name == "lsp_uninstall_component") {
                put("component", buildJsonObject { put("type", "string") })
            } else if (name == "lsp_workspace_symbols") {
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
        }
        val required = when (name) {
            "lsp_uninstall_component" -> setOf("component")
            "lsp_workspace_symbols" -> setOf("query")
            "lsp_rename_preview" -> setOf("path", "new_name")
            "lsp_status" -> emptySet()
            else -> setOf("path")
        }
        return functionToolSchema(name, description, properties, required)
    }

    companion object {
        /**
         * Use per-operation intent. The former shared "代码/定义/引用/诊断"
         * keywords activated every semantic tool for a single ordinary code request.
         * capability_search still indexes the LSP family and tool descriptions.
         */
        private fun discoveryKeywordsFor(name: String): Set<String> = when (name) {
            "lsp_status" -> setOf("语言服务器状态", "代码智能状态", "lsp状态", "lsp_status")
            "lsp_uninstall_component" -> setOf("卸载语言服务器", "移除语言组件", "lsp_uninstall")
            "lsp_definition" -> setOf("跳转定义", "查找定义", "定位定义", "函数定义", "方法定义")
            "lsp_references" -> setOf("查找引用", "引用关系", "被谁调用", "调用关系", "调用位置")
            "lsp_hover" -> setOf("类型信息", "类型推断", "变量类型", "悬停说明", "类型说明")
            "lsp_implementation" -> setOf("接口实现", "实现类", "实现位置", "查找实现")
            "lsp_symbols" -> setOf("文件符号", "函数列表", "类列表", "成员列表", "代码结构")
            "lsp_workspace_symbols" -> setOf("工作区符号", "全局符号", "跨文件符号", "整个项目符号")
            "lsp_rename_preview" -> setOf("符号重命名", "批量改名", "重命名预览", "跨文件改名")
            "lsp_diagnostics" -> setOf("代码诊断", "错误诊断", "检查代码", "类型错误", "静态检查", "代码报错")
            else -> setOf(name)
        }
        private val names = listOf(
            "lsp_status",
            "lsp_uninstall_component",
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
