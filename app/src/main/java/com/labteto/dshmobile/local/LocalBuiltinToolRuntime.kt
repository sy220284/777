package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.local.memory.LocalMemoryTools
import com.labteto.dshmobile.local.memory.LocalMemoryToolContext
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.runtime.BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS
import com.labteto.dshmobile.local.runtime.DEFAULT_WEB_FETCH_BYTES
import com.labteto.dshmobile.local.runtime.FOREGROUND_WEB_FETCH_TIMEOUT_SECONDS
import com.labteto.dshmobile.local.runtime.MAX_PATCH_CHARS
import com.labteto.dshmobile.local.runtime.MAX_WEB_FETCH_BYTES
import com.labteto.dshmobile.local.runtime.PLAN_MODE_BLOCKED_TOOLS
import com.labteto.dshmobile.local.runtime.LocalDiagnosticsRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalSessionAccessCoordinator
import com.labteto.dshmobile.local.session.LocalSessionAccessScope
import com.labteto.dshmobile.local.tools.LocalArchiveReader
import com.labteto.dshmobile.local.tools.LocalFileInspector
import com.labteto.dshmobile.local.tools.LocalExtensionWorkshop
import com.labteto.dshmobile.local.tools.LocalShellTool
import com.labteto.dshmobile.local.tools.toolCapabilityTarget
import kotlinx.coroutines.currentCoroutineContext
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import com.labteto.dshmobile.local.files.executionLocalWorkspace
import com.labteto.dshmobile.local.tools.boolean
import com.labteto.dshmobile.local.tools.int
import com.labteto.dshmobile.local.tools.long
import com.labteto.dshmobile.local.tools.optionalString
import com.labteto.dshmobile.local.tools.string
import com.labteto.dshmobile.local.usage.LocalTokenUsageContextBridge
import com.labteto.dshmobile.local.usage.LocalTokenUsageSessionFacts
import com.labteto.dshmobile.local.tools.LocalPinnedInvocation
import com.labteto.dshmobile.local.work.LocalWorkComposition
import com.labteto.dshmobile.local.work.LocalWorkRunBinding
import com.labteto.dshmobile.local.work.LocalWorkRunRegistry
import com.labteto.dshmobile.local.work.toEnvironmentRunSnapshot
import com.labteto.dshmobile.local.work.validateWorkspacePatchPaths
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

/**
 * Process-wide dispatcher for built-in tool implementations.
 *
 * ToolRegistry/PluginComposition call this owner directly. Product-specific built-ins are delegated
 * to their Feature composition so the shared Tool graph never depends on the Runtime Kernel.
 */
@Singleton
internal class LocalBuiltinToolRuntime @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val workRuns: LocalWorkRunRegistry,
    private val memoryStore: MemoryStore,
    private val memoryManager: MemoryManager,
    private val tools: Provider<LocalToolCompositionRoot>,
    private val work: Provider<LocalWorkComposition>,
    private val diagnostics: Provider<LocalDiagnosticsRuntime>,
    private val pinnedInvocations: com.labteto.dshmobile.local.tools.LocalPinnedInvocationStore,
) {
    private val jobs get() = runtimeStateStore.jobManager
    private val workspace get() = sessionStorage.files.workspace
    private val extensionWorkshop by lazy { LocalExtensionWorkshop(workspace) }
    private val usageBridge by lazy {
        LocalTokenUsageContextBridge(
            sessionFacts = { sessionId ->
                val summary = sessionStorage.coordinator.summaries().firstOrNull { it.id == sessionId }
                LocalTokenUsageSessionFacts(
                    mode = summary?.usageMode,
                    title = summary?.title,
                )
            },
            eventLogFor = sessionStorage.eventLogs::get,
            currentSessionId = runtimeStateStore::currentSessionId,
        )
    }
    private val sessionAccess by lazy {
        LocalSessionAccessCoordinator(
            summaries = { sessionStorage.coordinator.summaries() },
            currentSessionId = runtimeStateStore::currentSessionId,
            currentScope = {
                runtimeStateStore.state.value.let { current ->
                    LocalSessionAccessScope(
                        projectId = current.projectId,
                        lineageId = current.lineageId.ifBlank { current.sessionId },
                    )
                }
            },
            activeScope = { sessionId ->
                workRuns.state(sessionId)?.let { active ->
                    LocalSessionAccessScope(
                        projectId = active.projectId,
                        lineageId = active.lineageId.ifBlank { active.sessionId },
                    )
                }
            },
            eventLogFor = sessionStorage.eventLogs::get,
        )
    }

    private fun readAuthorizedSkill(name: String): String =
        if (pinnedInvocations.current().any {
                it.kind == LocalPinnedInvocation.Kind.SKILL && it.id == name
            }) workspace.readSkill(name)
        else workspace.readModelSkill(name)

    internal suspend fun executeResult(call: LocalToolCall, allowMutation: Boolean, executionSessionId: String?): com.labteto.dshmobile.harness.tools.ToolResult {
        val workspace = executionLocalWorkspace(this.workspace)
        val captured = executionSessionId?.let(workRuns::get)?.aggregateSnapshot() ?: runtimeStateStore.state.value
        if (captured.work.planMode && call.name in PLAN_MODE_BLOCKED_TOOLS) return com.labteto.dshmobile.harness.tools.ToolResult(
            "当前处于规划模式，只能检查和制定方案", isError = true, errorCode = "PLAN_MODE_BLOCKED")
        if (call.name in setOf("bash", "run_shell")) {
            require(allowMutation) { "该子任务处于只读模式" }
            return LocalShellTool.executeResult(call.arguments, workspace, jobs, executionSessionId)
        }
        val output = execute(call, allowMutation, executionSessionId)
        if (call.name == "apply_patch") {
            val code = when {
                output.startsWith("[apply_patch][CHECK_FAILED]") -> "PATCH_CHECK_FAILED"
                output.startsWith("[apply_patch][APPLY_FAILED]") -> "PATCH_APPLY_FAILED"
                else -> null
            }
            if (code != null) return com.labteto.dshmobile.harness.tools.ToolResult(output, isError = true, errorCode = code)
        }
        return com.labteto.dshmobile.harness.tools.ToolResult(output)
    }

    internal suspend fun execute(
        call: LocalToolCall,
        allowMutation: Boolean,
        executionSessionId: String?,
    ): String {
        val root = tools.get()
        val workspace = executionLocalWorkspace(this.workspace)
        val webTools = root.webTools.atWorkspace(workspace)
        val extensionWorkshop = if (workspace === this.workspace) this.extensionWorkshop else LocalExtensionWorkshop(workspace)
        val args = call.arguments
        val binding = executionSessionId?.let(workRuns::get)
        val state = binding?.aggregateSnapshot() ?: runtimeStateStore.state.value
        val boundSessionId = binding?.sessionId ?: executionSessionId ?: runtimeStateStore.currentSessionId
        if (state.work.planMode && call.name in PLAN_MODE_BLOCKED_TOOLS) {
            return "当前处于规划模式，只能检查和制定方案；请先通过 exit_plan_mode 提交计划。"
        }
        work.get().executeBuiltin(call, allowMutation, binding)?.let { return it }

        return when (call.name) {
            "read", "read_file" -> withContext(Dispatchers.IO) { runtimeStateStore.resourceScheduler.withResource(
                HarnessResourceKind.FILE_IO, "tool:read"
            ) { runInterruptible(Dispatchers.IO) { workspace.read(
                relativePath = args.string("path"),
                startLine = args.int("start_line", 1),
                endLine = args.int("end_line", args.int("start_line", 1) + 399),
                startChar = args.int("start_char", 0),
            ) } } }
            "read_chunk" -> withContext(Dispatchers.IO) { runtimeStateStore.resourceScheduler.withResource(
                HarnessResourceKind.FILE_IO, "tool:read_chunk"
            ) {
                val offset = args.long("start_byte", 0L)
                val page = runInterruptible(Dispatchers.IO) {
                    workspace.readRawPage(args.string("path"), offset, args.int("max_bytes", 48 * 1024))
                }
                "start_byte=" + offset + "；next_byte=" + (offset + page.size) +
                    "；本页字节=" + page.size + "\n" + java.util.Base64.getEncoder().encodeToString(page)
            } }
            "tool_output_read" -> args.optionalString("query")?.takeIf(String::isNotBlank)?.let { query ->
                root.toolOutputStore.search(
                    sessionId = boundSessionId,
                    callId = args.string("call_id"),
                    query = query,
                    startByte = args.int("start_byte", 0),
                )
            } ?: root.toolOutputStore.read(
                sessionId = boundSessionId,
                callId = args.string("call_id"),
                startByte = args.int("start_byte", 0),
                maxBytes = args.int("max_bytes", LocalToolOutputStore.DEFAULT_READ_BYTES),
            )
            "archive_list" -> withContext(Dispatchers.IO) { runtimeStateStore.resourceScheduler.withResource(
                HarnessResourceKind.FILE_IO, "tool:archive_list"
            ) { runInterruptible(Dispatchers.IO) { LocalArchiveReader(File(workspace.path)).list(
                args.string("path"), args.int("cursor", 0), args.int("limit", 100),
                args.optionalString("prefix").orEmpty(),
            ) } } }
            "archive_read" -> withContext(Dispatchers.IO) { runtimeStateStore.resourceScheduler.withResource(
                HarnessResourceKind.FILE_IO, "tool:archive_read"
            ) { runInterruptible(Dispatchers.IO) { LocalArchiveReader(File(workspace.path)).read(
                args.string("path"), args.string("member"), args.long("start_char", 0L),
                args.int("max_chars", 20_000),
            ) } } }
            "file_inspect" -> LocalFileInspector(File(workspace.path)).inspect(args.string("path"))
            "write", "write_file" -> {
                if (!allowMutation) return "子代理无写入权限"
                withContext(Dispatchers.IO) { runtimeStateStore.resourceScheduler.withResource(
                    HarnessResourceKind.FILE_IO, "tool:write"
                ) { runInterruptible(Dispatchers.IO) {
                    workspace.write(args.string("path"), args.string("content"))
                } } }
            }
            "write_chunk" -> {
                if (!allowMutation) return "子代理无写入权限"
                withContext(Dispatchers.IO) { runtimeStateStore.resourceScheduler.withResource(
                    HarnessResourceKind.FILE_IO, "tool:write_chunk"
                ) {
                    runInterruptible(Dispatchers.IO) {
                        val text = args.optionalString("content")
                        val encoded = args.optionalString("content_base64")
                        require((text == null) != (encoded == null)) {
                            "content 和 content_base64 必须且只能提供一种"
                        }
                        // Keep model tool input bounded; repeat calls for arbitrarily large files.
                        val maxChunk = 256 * 1024
                        val bytes = if (encoded != null) {
                            require(encoded.length <= ((maxChunk + 2) / 3) * 4) {
                                "Base64 续写分块超过容量上限"
                            }
                            java.util.Base64.getDecoder().decode(encoded)
                        } else {
                            val value = requireNotNull(text)
                            require(value.length <= maxChunk) { "文本续写分块超过容量上限" }
                            value.toByteArray(Charsets.UTF_8)
                        }
                        require(bytes.size <= maxChunk) { "单个续写分块不能超过 256 KiB" }
                        workspace.writeChunkBytes(
                            args.string("path"), bytes, args.long("offset", 0L),
                            args.boolean("finish", false), args.optionalString("session_id"),
                        )
                    }
                } }
            }
            "edit", "edit_file" -> {
                if (!allowMutation) return "该子任务处于只读模式"
                val path = args.string("path")
                workspace.requireFreshObservation(path)
                withContext(Dispatchers.IO) { runtimeStateStore.resourceScheduler.withResource(
                    HarnessResourceKind.FILE_IO, "tool:edit"
                ) {
                    runInterruptible(Dispatchers.IO) {
                        workspace.edit(path, args.string("old_text"), args.string("new_text"))
                    }
                } }
            }
            "apply_patch" -> {
                if (!allowMutation) return "该子任务处于只读模式"
                val patch = args.string("patch")
                require(patch.length <= MAX_PATCH_CHARS) { "补丁超过 ${MAX_PATCH_CHARS} 字符上限" }
                validateWorkspacePatchPaths(patch)
                val check = root.process.execute(
                    ProcessRequest(
                        command = listOf("git", "apply", "--check", "--whitespace=nowarn", "-"),
                        workingDirectory = workspace.path,
                        stdin = patch,
                        timeoutMillis = 30_000L,
                    ),
                )
                if (check.exitCode != 0) {
                    return "[apply_patch][CHECK_FAILED] 补丁预检失败：\n" +
                        (check.stderr.ifBlank { check.stdout }).take(20_000)
                }
                val stat = root.process.execute(
                    ProcessRequest(
                        command = listOf("git", "apply", "--stat", "-"),
                        workingDirectory = workspace.path,
                        stdin = patch,
                        timeoutMillis = 30_000L,
                    ),
                )
                val applied = root.process.execute(
                    ProcessRequest(
                        command = listOf("git", "apply", "--whitespace=nowarn", "-"),
                        workingDirectory = workspace.path,
                        stdin = patch,
                        timeoutMillis = 30_000L,
                    ),
                )
                if (applied.exitCode != 0) {
                    return "[apply_patch][APPLY_FAILED] 补丁应用失败：\n" +
                        (applied.stderr.ifBlank { applied.stdout }).take(20_000)
                }
                "补丁已应用" + stat.stdout.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
            }
            "list_files" -> workspace.list(args.optionalString("path") ?: ".", args.int("depth", 3), args.int("cursor", 0))
            "glob", "glob_files" -> workspace.glob(args.string("pattern"), args.optionalString("path") ?: ".", args.int("cursor", 0))
            "grep", "search_text" -> workspace.search(
                args.string("query"),
                args.optionalString("path") ?: ".",
                args.boolean("regex", false),
                args.optionalString("cursor"),
            )
            "bash", "run_shell" -> {
                if (!allowMutation) return "该子任务处于只读模式"
                LocalShellTool.execute(args, workspace, jobs, boundSessionId)
            }
            "job_list" -> jobs.list(boundSessionId)
            "job_output" -> jobs.output(args.string("job_id"), boundSessionId)
            "job_kill" -> jobs.kill(args.string("job_id"), boundSessionId)
            "web_search" -> {
                val queries = args["queries"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                webTools.search(
                    queries,
                    usageBridge.resolve(
                        boundSessionId,
                        call.id,
                        TokenUsageAction.WEB_SEARCH,
                        queries.firstOrNull(),
                    ),
                    count = args.int("count", 10).coerceIn(1, 50),
                    needContent = args.boolean("need_content", false),
                    provider = args.optionalString("provider") ?: "auto",
                    oriQuestion = args.optionalString("ori_question"),
                    localization = args.optionalString("localization"),
                )
            }
            "web_fetch" -> {
                val input = args.string("url")
                val maxBytes = args.int("max_bytes", DEFAULT_WEB_FETCH_BYTES)
                    .coerceIn(16 * 1024, MAX_WEB_FETCH_BYTES)
                val format = args.optionalString("format") ?: "text"
                val provider = args.optionalString("provider") ?: "auto"
                val background = args.boolean("run_in_background", false)
                val readOnlyScope = !allowMutation || state.work.planMode
                if (background && readOnlyScope) {
                    return "当前为只读/规划作用域，不能创建后台网页抓取任务"
                }
                val timeout = if (background) {
                    BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS
                } else {
                    FOREGROUND_WEB_FETCH_TIMEOUT_SECONDS
                }
                if (background) {
                    startPersistentWebFetch(input, maxBytes, format, timeout, boundSessionId, provider)
                } else {
                    webTools.fetch(
                        input,
                        maxBytes,
                        format,
                        timeout,
                        allowArtifactWrite = !readOnlyScope,
                        provider = provider,
                    )
                }
            }
            "http_request" -> {
                if (
                    (!allowMutation || state.work.planMode) &&
                    args.string("method").uppercase() !in setOf("GET", "HEAD")
                ) {
                    return "只读/规划作用域仅允许 GET/HEAD 请求"
                }
                val headers = args["headers"]?.jsonObject?.mapValues { (_, value) ->
                    value.jsonPrimitive.content
                }.orEmpty()
                webTools.httpRequest(
                    method = args.string("method"),
                    url = args.string("url"),
                    headers = headers,
                    body = args.optionalString("body"),
                    maxBytes = args.int("max_bytes", DEFAULT_WEB_FETCH_BYTES)
                        .coerceIn(16 * 1024, MAX_WEB_FETCH_BYTES),
                    timeoutSeconds = FOREGROUND_WEB_FETCH_TIMEOUT_SECONDS,
                )
            }
            "download_file" -> {
                if (!allowMutation) return "只读子任务不能下载写入文件"
                webTools.download(
                    url = args.string("url"),
                    path = args.string("path"),
                    // Unspecified means stream the whole response; a caller can still opt into a limit.
                    maxBytes = args.long("max_bytes", Long.MAX_VALUE).also {
                        require(it > 0L) { "max_bytes 必须大于 0" }
                    },
                    timeoutSeconds = BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS,
                )
            }
            "json_query" -> webTools.jsonQuery(
                path = args.string("path"),
                query = args.optionalString("query").orEmpty(),
                allowArtifactWrite = allowMutation && !state.work.planMode,
            )
            "network_diagnose" -> webTools.diagnose(args.string("url"))
            "environment_info" -> diagnostics.get().environmentInfo(
                binding?.toEnvironmentRunSnapshot(
                    runtimeStateStore.historyBudgetFor(binding.aggregateSnapshot()).maxHistoryChars,
                ),
            )
            "capability_search" -> {
                val target = toolCapabilityTarget(currentCoroutineContext(), binding?.enabledOptionalTools)
                if (target == null) {
                    root.execution.searchCapabilities(args.string("query"))
                } else {
                    root.execution.searchCapabilities(args.string("query"), target)
                }
            }
            "skill_create" -> {
                require(allowMutation) { "当前子任务没有写入技能的权限" }
                extensionWorkshop.createSkill(args.string("id"), args.string("display_name"),
                    args.string("description"), args.string("instructions"))
            }
            "skill_update" -> {
                require(allowMutation) { "当前子任务没有修改技能的权限" }
                extensionWorkshop.reviseSkill(args.string("id"), args.string("document"))
            }
            "skill_rollback" -> {
                require(allowMutation) { "当前子任务没有回退技能的权限" }
                extensionWorkshop.rollbackSkill(args.string("id"), args.string("revision"))
            }
            "mcp_create_local" -> {
                require(allowMutation) { "当前子任务不能创建 MCP 代码" }
                extensionWorkshop.createMcp(
                    args.string("id"), args.string("interpreter"), args.string("source"),
                )
            }
            "mcp_update_local" -> {
                require(allowMutation) { "当前子任务不能修改 MCP" }
                extensionWorkshop.reviseMcp(args.string("id"), args.string("source"))
            }
            "mcp_rollback_local" -> {
                require(allowMutation) { "当前子任务不能回退 MCP" }
                extensionWorkshop.rollbackMcp(args.string("id"), args.string("revision"))
            }
            "mcp_start_local" -> {
                require(allowMutation) { "当前子任务不能启动 MCP" }
                val id = args.string("id")
                val (command, directory) = extensionWorkshop.launchCommand(id)
                val connection = root.plugins.connectMcpStdio(id, command, directory)
                val liveNames = root.registry.names().filter { it.startsWith("mcp_${id}_") }
                require(liveNames.isNotEmpty()) { "MCP 连接返回但没有注册任何可调用工具：$id" }
                extensionWorkshop.record("mcp", id, "registered",
                    "本地服务启动完成，登记 ${liveNames.size} 个工具；业务调用效果待测",
                    ".dsh/extensions/mcp-$id/manifest.json")
                "$connection；当前已注册工具：${liveNames.joinToString(", ")}；真实业务调用尚未验证。"
            }
            "extension_record" -> {
                require(allowMutation) { "当前子任务没有登记扩展的权限" }
                extensionWorkshop.record(args.string("kind"), args.string("id"), args.string("phase"),
                    args.string("summary"), args.string("evidence_path"))
            }
            "extension_inspect" -> extensionWorkshop.inspect(
                args.string("kind"), args.string("id"),
                root.registry.names().toSet(), root.plugins.installedPluginIds().toSet(),
            )
            "skill" -> args.optionalString("name")?.takeIf(String::isNotBlank)?.let(::readAuthorizedSkill)
                ?: workspace.modelSkillCatalog(offset = args.int("offset", 0), query = args.optionalString("query").orEmpty())
            "list_skills" -> workspace.modelSkillCatalog(offset = args.int("offset", 0), query = args.optionalString("query").orEmpty())
            "read_skill" -> readAuthorizedSkill(args.string("name"))
            "session_search" -> sessionAccess.search(args.string("query"), boundSessionId)
            "memory_search", "memory_list", "memory_remember", "memory_update", "memory_forget" ->
                memoryTools(binding).execute(call.name, args, allowMutation)
            "session_event_search" -> sessionAccess.authorizedLog(
                args.optionalString("session_id"),
                boundSessionId,
            ).search(
                query = args.string("query"),
                limit = args.int("limit", 50),
                afterSequence = args.long("after_sequence", -1L),
            )
            "session_trace" -> sessionAccess.authorizedLog(
                args.optionalString("session_id"),
                boundSessionId,
            ).tail(args.int("limit", 40))
            "session_event_trace" -> sessionAccess.authorizedLog(
                args.optionalString("session_id"),
                boundSessionId,
            ).read(args.int("seq", -1).toLong(), before = 1, after = 1)
            "session_event_read" -> sessionAccess.authorizedLog(
                args.optionalString("session_id"),
                boundSessionId,
            ).read(
                sequence = args.int("seq", -1).toLong(),
                before = args.int("before", 0),
                after = args.int("after", 0),
                offsetChars = args.int("offset_chars", 0),
            )
            "present" -> workspace.present(args.string("path"))
            else -> "未知工具：${call.name}"
        }
    }

    private fun memoryTools(binding: LocalWorkRunBinding?): LocalMemoryTools =
        LocalMemoryTools(
            memoryStore,
            memoryManager,
            context = {
                val current = binding?.aggregateSnapshot() ?: runtimeStateStore.state.value
                LocalMemoryToolContext(
                    usageMode = current.usageMode,
                    conversationMode = current.conversationMode,
                    projectId = current.projectId,
                    lineageId = current.lineageId,
                )
            },
            sessionId = { binding?.sessionId ?: runtimeStateStore.currentSessionId },
        )

    private suspend fun startPersistentWebFetch(
        url: String,
        maxBytes: Int,
        format: String,
        timeoutSeconds: Long,
        sessionId: String,
        provider: String,
    ): String {
        val boundWorkspace = executionLocalWorkspace(workspace)
        val workspaceId = currentCoroutineContext()[com.labteto.dshmobile.harness.capability.WorkspaceExecutionContext]?.workspaceId
        val selected = tools.get().webTools.atWorkspace(boundWorkspace)
        return jobs.startPersistent(
            label = "网页抓取：${url.take(120)}",
            resumeKind = "web_fetch",
            resumePayload = buildJsonObject {
                put("session_id", sessionId)
                put("url", url)
                put("max_bytes", maxBytes)
                put("format", format)
                put("provider", provider)
                put("timeout_seconds", timeoutSeconds)
                workspaceId?.let { put("workspace_id", it) }
            }.toString(),
            ownerSessionId = sessionId,
        ) { _, report ->
            report("正在抓取：$url")
            selected.fetch(url, maxBytes, format, timeoutSeconds, provider = provider)
        }
    }
}
