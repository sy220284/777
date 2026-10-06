package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.local.memory.LocalMemoryTools
import com.labteto.dshmobile.local.memory.LocalMemoryToolContext
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.runtime.BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS
import com.labteto.dshmobile.local.runtime.DEFAULT_DOWNLOAD_BYTES
import com.labteto.dshmobile.local.runtime.DEFAULT_WEB_FETCH_BYTES
import com.labteto.dshmobile.local.runtime.FOREGROUND_WEB_FETCH_TIMEOUT_SECONDS
import com.labteto.dshmobile.local.runtime.MAX_DOWNLOAD_BYTES
import com.labteto.dshmobile.local.runtime.MAX_PATCH_CHARS
import com.labteto.dshmobile.local.runtime.MAX_WEB_FETCH_BYTES
import com.labteto.dshmobile.local.runtime.PLAN_MODE_BLOCKED_TOOLS
import com.labteto.dshmobile.local.runtime.LocalDiagnosticsRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalSessionAccessCoordinator
import com.labteto.dshmobile.local.session.LocalSessionAccessScope
import com.labteto.dshmobile.local.tools.LocalFileInspector
import com.labteto.dshmobile.local.tools.LocalShellTool
import com.labteto.dshmobile.local.tools.boolean
import com.labteto.dshmobile.local.tools.int
import com.labteto.dshmobile.local.tools.long
import com.labteto.dshmobile.local.tools.optionalString
import com.labteto.dshmobile.local.tools.string
import com.labteto.dshmobile.local.usage.LocalTokenUsageContextBridge
import com.labteto.dshmobile.local.usage.LocalTokenUsageSessionFacts
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
) {
    private val jobs get() = runtimeStateStore.jobManager
    private val workspace get() = sessionStorage.files.workspace
    private val fileInspector by lazy { LocalFileInspector(File(workspace.path)) }
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

    internal suspend fun execute(
        call: LocalToolCall,
        allowMutation: Boolean,
        executionSessionId: String?,
    ): String {
        val root = tools.get()
        val args = call.arguments
        val binding = executionSessionId?.let(workRuns::get)
        val state = binding?.aggregateSnapshot() ?: runtimeStateStore.state.value
        val boundSessionId = binding?.sessionId ?: executionSessionId ?: runtimeStateStore.currentSessionId
        if (state.work.planMode && call.name in PLAN_MODE_BLOCKED_TOOLS) {
            return "当前处于规划模式，只能检查和制定方案；请先通过 exit_plan_mode 提交计划。"
        }
        work.get().executeBuiltin(call, allowMutation, binding)?.let { return it }

        return when (call.name) {
            "read", "read_file" -> workspace.read(
                relativePath = args.string("path"),
                startLine = args.int("start_line", 1),
                endLine = args.int("end_line", args.int("start_line", 1) + 399),
            )
            "tool_output_read" -> root.toolOutputStore.read(
                sessionId = boundSessionId,
                callId = args.string("call_id"),
                startByte = args.int("start_byte", 0),
                maxBytes = args.int("max_bytes", LocalToolOutputStore.DEFAULT_READ_BYTES),
            )
            "file_inspect" -> fileInspector.inspect(args.string("path"))
            "write", "write_file" -> {
                if (!allowMutation) return "子代理无写入权限"
                workspace.write(args.string("path"), args.string("content"))
            }
            "edit", "edit_file" -> {
                if (!allowMutation) return "该子任务处于只读模式"
                val path = args.string("path")
                workspace.requireFreshObservation(path)
                workspace.edit(path, args.string("old_text"), args.string("new_text"))
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
            "list_files" -> workspace.list(args.optionalString("path") ?: ".", args.int("depth", 3))
            "glob", "glob_files" -> workspace.glob(args.string("pattern"), args.optionalString("path") ?: ".")
            "grep", "search_text" -> workspace.search(
                args.string("query"),
                args.optionalString("path") ?: ".",
                args.boolean("regex", false),
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
                root.webTools.search(
                    queries,
                    usageBridge.resolve(
                        boundSessionId,
                        call.id,
                        TokenUsageAction.WEB_SEARCH,
                        queries.firstOrNull(),
                    ),
                )
            }
            "web_fetch" -> {
                val input = args.string("url")
                val maxBytes = args.int("max_bytes", DEFAULT_WEB_FETCH_BYTES)
                    .coerceIn(16 * 1024, MAX_WEB_FETCH_BYTES)
                val format = args.optionalString("format") ?: "text"
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
                    startPersistentWebFetch(input, maxBytes, format, timeout, boundSessionId)
                } else {
                    root.webTools.fetch(
                        input,
                        maxBytes,
                        format,
                        timeout,
                        allowArtifactWrite = !readOnlyScope,
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
                root.webTools.httpRequest(
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
                root.webTools.download(
                    url = args.string("url"),
                    path = args.string("path"),
                    maxBytes = args.int("max_bytes", DEFAULT_DOWNLOAD_BYTES)
                        .coerceIn(1_024, MAX_DOWNLOAD_BYTES)
                        .toLong(),
                    timeoutSeconds = BACKGROUND_WEB_FETCH_TIMEOUT_SECONDS,
                )
            }
            "json_query" -> root.webTools.jsonQuery(
                path = args.string("path"),
                query = args.optionalString("query").orEmpty(),
                allowArtifactWrite = allowMutation && !state.work.planMode,
            )
            "network_diagnose" -> root.webTools.diagnose(args.string("url"))
            "environment_info" -> diagnostics.get().environmentInfo(
                binding?.toEnvironmentRunSnapshot(
                    runtimeStateStore.historyBudgetFor(binding.aggregateSnapshot()).maxHistoryChars,
                ),
            )
            "capability_search" -> if (binding == null) {
                root.execution.searchCapabilities(args.string("query"))
            } else {
                root.execution.searchCapabilities(args.string("query"), binding.enabledOptionalTools)
            }
            "skill" -> args.optionalString("name")?.takeIf(String::isNotBlank)?.let(workspace::readSkill)
                ?: workspace.skills().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "未安装技能"
            "list_skills" -> workspace.skills().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "未安装技能"
            "read_skill" -> workspace.readSkill(args.string("name"))
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

    private fun startPersistentWebFetch(
        url: String,
        maxBytes: Int,
        format: String,
        timeoutSeconds: Long,
        sessionId: String,
    ): String = jobs.startPersistent(
        label = "网页抓取：${url.take(120)}",
        resumeKind = "web_fetch",
        resumePayload = buildJsonObject {
            put("session_id", sessionId)
            put("url", url)
            put("max_bytes", maxBytes)
            put("format", format)
            put("timeout_seconds", timeoutSeconds)
        }.toString(),
        ownerSessionId = sessionId,
    ) { _, report ->
        report("正在抓取：$url")
        tools.get().webTools.fetch(url, maxBytes, format, timeoutSeconds)
    }
}
