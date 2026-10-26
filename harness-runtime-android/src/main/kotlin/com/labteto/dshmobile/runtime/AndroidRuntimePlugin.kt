package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.CapabilityDescriptor
import com.labteto.dshmobile.harness.capability.ProcessRequest
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
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Makes the Android-native process and persistent-terminal providers visible to the model.
 *
 * Existing `bash` remains owned by the app adapter because it already has streaming background
 * output and legacy approval semantics. These tools expose the lower-level native runtime without
 * replacing that stable path.
 */
class AndroidRuntimePlugin(
    private val workspaceRoot: File,
    private val processRuntime: AndroidProcessRuntime = AndroidProcessRuntime(
        defaultWorkingDirectory = workspaceRoot,
    ),
    private val terminalProvider: PersistentPipeTerminalProvider = PersistentPipeTerminalProvider(
        defaultWorkingDirectory = workspaceRoot,
    ),
    private val resourceScheduler: HarnessResourceScheduler? = null,
) : HarnessPlugin {
    override val id: String = "android-runtime"
    private val terminalLeases = ConcurrentHashMap<String, HarnessResourceLease>()
    private val gitWorkspaces = GitWorkspaceRuntime(workspaceRoot, processRuntime)

    override suspend fun install(context: HarnessContext) {
        gitWorkspaces.install(context)
        context.tools.register(
            HarnessTool(
                name = "runtime_command_status",
                schema = runtimeStatusSchema(),
                access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                timeoutMillis = 5_000L,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "运行时",
                    environmentBound = true,
                    discoveryKeywords = RUNTIME_DISCOVERY_KEYWORDS,
                    requirements = emptyList(),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val commands = input["commands"]
                        ?.takeIf { it is JsonArray }
                        ?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.filter(String::isNotBlank)
                        .orEmpty()
                    val statuses = RuntimeDiagnostics(processRuntime).commands(
                        if (commands.isEmpty()) RuntimeDiagnostics.DEFAULT_COMMANDS else commands,
                    )
                    ToolResult(
                        buildJsonArray {
                            statuses.forEach { status ->
                                add(
                                    buildJsonObject {
                                        put("command", status.command)
                                        put("available", status.available)
                                    },
                                )
                            }
                        }.toString(),
                    )
                },
            ),
        )

        context.tools.register(
            HarnessTool(
                name = "process_exec",
                schema = processExecSchema(),
                access = ToolAccess.PROCESS,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = PROCESS_TOOL_TIMEOUT_MILLIS,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "运行时",
                    environmentBound = true,
                    discoveryKeywords = RUNTIME_DISCOVERY_KEYWORDS,
                    requirements = listOf("目标命令必须存在；可先调用 runtime_command_status 检查"),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val command = input.requiredStringArray("command")
                    val workingDirectory = resolveWorkingDirectory(input.optionalString("working_directory"))
                    val environment = input["environment"]?.jsonObject?.mapValues { (_, value) ->
                        value.jsonPrimitive.content
                    }.orEmpty()
                    val timeoutMillis = input["timeout_ms"]?.jsonPrimitive?.intOrNull
                        ?.toLong()
                        ?.coerceIn(MIN_PROCESS_TIMEOUT_MILLIS, MAX_PROCESS_TIMEOUT_MILLIS)
                        ?: DEFAULT_PROCESS_TIMEOUT_MILLIS
                    val result = processRuntime.execute(
                        ProcessRequest(
                            command = command,
                            workingDirectory = workingDirectory,
                            environment = environment,
                            stdin = input.optionalString("stdin"),
                            timeoutMillis = timeoutMillis,
                        ),
                    )
                    ToolResult(
                        content = formatProcessResult(result.exitCode, result.stdout, result.stderr, result.timedOut),
                        isError = result.timedOut || result.exitCode != 0,
                        processExitCode = result.exitCode.takeUnless { result.timedOut },
                    )
                },
            ),
        )

        context.tools.register(
            HarnessTool(
                name = "terminal_open",
                schema = terminalOpenSchema(),
                access = ToolAccess.PROCESS,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = 10_000L,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "运行时",
                    environmentBound = true,
                    discoveryKeywords = RUNTIME_DISCOVERY_KEYWORDS,
                    requirements = listOf("目标命令必须存在；可先调用 runtime_command_status 检查"),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val command = input["command"]
                        ?.takeIf { it is JsonArray }
                        ?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.filter(String::isNotBlank)
                        ?.takeIf { it.isNotEmpty() }
                        ?: listOf("sh")
                    val workingDirectory = resolveWorkingDirectory(input.optionalString("working_directory"))
                    val lease = resourceScheduler?.acquire(
                        HarnessResourceKind.TERMINAL,
                        owner = "terminal:" + command.firstOrNull().orEmpty(),
                    )
                    var opened: String? = null
                    try {
                        val usePty = input["tty"]?.jsonPrimitive?.booleanOrNull ?: true
                        val sessionId = if (usePty) terminalProvider.openPty(
                            command, workingDirectory,
                            input["rows"]?.jsonPrimitive?.intOrNull ?: 24,
                            input["columns"]?.jsonPrimitive?.intOrNull ?: 80,
                        ) else terminalProvider.open(command, workingDirectory)
                        opened = sessionId
                        currentCoroutineContext().ensureActive()
                        if (lease != null) terminalLeases[sessionId] = lease
                        ToolResult(sessionId)
                    } catch (error: Throwable) {
                        withContext(NonCancellable) { opened?.let { terminalProvider.close(it) } }
                        lease?.close()
                        throw error
                    }
                },
            ),
        )

        context.tools.register(
            HarnessTool(
                name = "terminal_write",
                schema = terminalWriteSchema(),
                access = ToolAccess.PROCESS,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = 10_000L,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "运行时",
                    environmentBound = true,
                    discoveryKeywords = RUNTIME_DISCOVERY_KEYWORDS,
                    requirements = emptyList(),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val sessionId = input.requiredString("session_id")
                    requireTerminalWorkspace(sessionId)
                    try {
                        terminalProvider.write(sessionId, input.requiredString("input"))
                        ToolResult("已写入终端会话")
                    } finally {
                        releaseTerminalLeaseIfFinished(sessionId)
                    }
                },
            ),
        )

        context.tools.register(
            HarnessTool(
                name = "terminal_read",
                schema = terminalSessionSchema("terminal_read", "读取持久终端当前可用输出"),
                access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                timeoutMillis = 10_000L,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "运行时",
                    environmentBound = true,
                    discoveryKeywords = RUNTIME_DISCOVERY_KEYWORDS,
                    requirements = emptyList(),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val sessionId = input.requiredString("session_id")
                    requireTerminalWorkspace(sessionId)
                    try {
                        val read = terminalProvider.readResult(sessionId)
                        ToolResult(
                            content = read.output + if (read.exited) "\n[终端已退出，退出码：${read.exitCode}]" else "",
                            isError = read.exited && read.exitCode != 0,
                            processExitCode = read.exitCode,
                        )
                    } finally {
                        releaseTerminalLeaseIfFinished(sessionId)
                    }
                },
            ),
        )

        context.tools.register(
            HarnessTool(
                name = "terminal_status",
                schema = terminalSessionSchema("terminal_status", "检查持久终端会话是否仍在运行"),
                access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                timeoutMillis = 5_000L,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "运行时",
                    environmentBound = true,
                    discoveryKeywords = RUNTIME_DISCOVERY_KEYWORDS,
                    requirements = emptyList(),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val sessionId = input.requiredString("session_id")
                    requireTerminalWorkspace(sessionId)
                    val fromPreviousRuntime = terminalProvider.isFromPreviousRuntime(sessionId)
                    val kind = terminalProvider.terminalType(sessionId)
                    val exitCode = terminalProvider.exitCode(sessionId)
                    val alive = !fromPreviousRuntime && terminalProvider.isAlive(sessionId)
                    if (!alive) terminalLeases.remove(sessionId)?.close()
                    ToolResult(
                        buildJsonObject {
                            put("session_id", sessionId)
                            put("alive", alive)
                            put("stale_runtime", fromPreviousRuntime)
                            put("runtime_generation", terminalProvider.runtimeGeneration())
                            put("native_pty", kind == "pty")
                            put("terminal_kind", kind ?: "unknown")
                            exitCode?.let { put("exit_code", it) }
                            if (fromPreviousRuntime) {
                                put("recovery_hint",
                                    "执行环境已重建，旧终端状态不可用。先核对文件和外部操作结果，再重新打开终端，避免重复写入。")
                            }
                        }.toString(),
                    )
                },
            ),
        )

        context.tools.register(
            HarnessTool(
                name = "terminal_close",
                schema = terminalSessionSchema("terminal_close", "关闭持久终端会话"),
                access = ToolAccess.PROCESS,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = 10_000L,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "运行时",
                    environmentBound = true,
                    discoveryKeywords = RUNTIME_DISCOVERY_KEYWORDS,
                    requirements = emptyList(),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val sessionId = input.requiredString("session_id")
                    requireTerminalWorkspace(sessionId)
                    try {
                        terminalProvider.close(sessionId)
                    } finally {
                        terminalLeases.remove(sessionId)?.close()
                    }
                    ToolResult("终端会话已关闭")
                },
            ),
        )

        context.tools.register(HarnessTool(
            name = "terminal_resize",
            schema = functionToolSchema("terminal_resize", "调整真实 PTY 尺寸，系统向前台进程发送 SIGWINCH", buildJsonObject {
                put("session_id", buildJsonObject { put("type", "string") })
                for (key in listOf("rows", "columns")) put(key, buildJsonObject {
                    put("type", "integer"); put("minimum", 1); put("maximum", 65535)
                })
            }, required = setOf("session_id", "rows", "columns")),
            access = ToolAccess.PROCESS, approvalPolicy = ToolApprovalPolicy.ALWAYS,
            exposure = ToolExposure.OPTIONAL, timeoutMillis = 10_000L,
            metadata = ToolMetadata("运行时", environmentBound = true, discoveryKeywords = RUNTIME_DISCOVERY_KEYWORDS),
            executor = HarnessToolExecutor { _, input, _ ->
                val sessionId = input.requiredString("session_id")
                requireTerminalWorkspace(sessionId)
                terminalProvider.resize(sessionId,
                    requireNotNull(input["rows"]?.jsonPrimitive?.intOrNull),
                    requireNotNull(input["columns"]?.jsonPrimitive?.intOrNull))
                ToolResult("终端尺寸已更新")
            },
        ))

        context.capabilities.register(
            CapabilityDescriptor(
                id = "android-process-runtime",
                attributes = mapOf("workspace" to workspaceRoot.canonicalPath),
            ),
            processRuntime,
        )
        context.capabilities.register(
            CapabilityDescriptor(
                id = "android-terminal-provider",
                attributes = mapOf("ptyTransport" to "bundled-python-posix", "ptyVerification" to "per-session"),
            ),
            terminalProvider,
        )
    }

    override suspend fun uninstall(context: HarnessContext) {
        gitWorkspaces.uninstall(context)
        terminalProvider.closeAll()
        terminalLeases.keys.toList().forEach { sessionId ->
            runCatching { terminalProvider.close(sessionId) }
            terminalLeases.remove(sessionId)?.close()
        }
        TOOL_NAMES.forEach(context.tools::unregister)
        context.capabilities.unregister("android-process-runtime")
        context.capabilities.unregister("android-terminal-provider")
    }

    private fun releaseTerminalLeaseIfFinished(sessionId: String) {
        if (!terminalProvider.isAlive(sessionId)) {
            terminalLeases.remove(sessionId)?.close()
        }
    }

    private suspend fun requireTerminalWorkspace(sessionId: String) {
        val scope = currentCoroutineContext()[com.labteto.dshmobile.harness.capability.WorkspaceExecutionContext] ?: return
        val directory = terminalProvider.workingDirectory(sessionId)?.canonicalFile ?: return
        val root = scope.rootFor(workspaceRoot).canonicalFile
        require(directory == root || directory.toPath().startsWith(root.toPath())) { "终端不属于当前子代理工作区" }
    }

    private suspend fun resolveWorkingDirectory(requested: String?): String {
        val root = com.labteto.dshmobile.harness.capability.executionWorkspaceRoot(workspaceRoot).canonicalFile
        val target = if (requested.isNullOrBlank()) {
            root
        } else {
            val candidate = File(requested)
            if (candidate.isAbsolute) candidate.canonicalFile else File(root, requested).canonicalFile
        }
        val insideWorkspace = target == root || target.path.startsWith(root.path + File.separator)
        require(insideWorkspace) { "工作目录必须位于本机工作区内：$requested" }
        require(target.isDirectory) { "工作目录不存在：${target.path}" }
        return target.path
    }

    private fun JsonObject.requiredString(key: String): String =
        optionalString(key)?.takeIf(String::isNotBlank) ?: error("缺少参数：$key")

    private fun JsonObject.optionalString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredStringArray(key: String): List<String> =
        this[key]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter(String::isNotBlank)
            ?.takeIf(List<String>::isNotEmpty)
            ?: error("缺少参数：$key")

    private fun formatProcessResult(
        exitCode: Int,
        stdout: String,
        stderr: String,
        timedOut: Boolean,
    ): String = buildString {
        appendLine("退出码：$exitCode")
        appendLine("超时：$timedOut")
        if (stdout.isNotEmpty()) {
            appendLine("标准输出：")
            appendLine(stdout.take(MAX_TOOL_OUTPUT_CHARS))
        }
        if (stderr.isNotEmpty()) {
            appendLine("标准错误：")
            appendLine(stderr.take(MAX_TOOL_OUTPUT_CHARS))
        }
        if (stdout.length > MAX_TOOL_OUTPUT_CHARS || stderr.length > MAX_TOOL_OUTPUT_CHARS) {
            appendLine("输出过长，已截断显示。")
        }
    }.trimEnd()

    private fun runtimeStatusSchema(): JsonObject = functionToolSchema(
        "runtime_command_status",
        "检查本机进程环境中常用命令是否可执行",
        buildJsonObject {
            put(
                "commands",
                buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                },
            )
        },
    )

    private fun processExecSchema(): JsonObject = functionToolSchema(
        "process_exec",
        "直接执行一个本机进程参数数组；用于 Git、Python、Node 等已存在的命令",
        buildJsonObject {
            put(
                "command",
                buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("minItems", 1)
                },
            )
            put("working_directory", buildJsonObject { put("type", "string") })
            put(
                "environment",
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", buildJsonObject { put("type", "string") })
                },
            )
            put("stdin", buildJsonObject { put("type", "string") })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("minimum", MIN_PROCESS_TIMEOUT_MILLIS)
                put("maximum", MAX_PROCESS_TIMEOUT_MILLIS)
            })
        },
        required = setOf("command"),
    )

    private fun terminalOpenSchema(): JsonObject = functionToolSchema(
        "terminal_open",
        "打开真实 PTY，支持交互、控制字符和窗口尺寸；tty=false 显式选择普通管道。PTY 依赖安装包内置 Python，启动失败会明确报错",
        buildJsonObject {
            put(
                "command",
                buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("minItems", 1)
                },
            )
            put("working_directory", buildJsonObject { put("type", "string") })
            put("tty", buildJsonObject { put("type", "boolean"); put("description", "默认 true；false 为普通管道") })
            for (key in listOf("rows", "columns")) put(key, buildJsonObject {
                put("type", "integer"); put("minimum", 1); put("maximum", 65535)
            })
        },
    )

    private fun terminalWriteSchema(): JsonObject = functionToolSchema(
        "terminal_write",
        "向持久终端写入标准输入",
        buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string") })
            put("input", buildJsonObject { put("type", "string") })
        },
        required = setOf("session_id", "input"),
    )

    private fun terminalSessionSchema(name: String, description: String): JsonObject = functionToolSchema(
        name,
        description,
        buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string") })
        },
        required = setOf("session_id"),
    )

    private companion object {
        const val MIN_PROCESS_TIMEOUT_MILLIS = 100L
        const val DEFAULT_PROCESS_TIMEOUT_MILLIS = 30_000L
        const val MAX_PROCESS_TIMEOUT_MILLIS = 120_000L
        const val PROCESS_TOOL_TIMEOUT_MILLIS = 125_000L
        const val MAX_TOOL_OUTPUT_CHARS = 120_000
        val RUNTIME_DISCOVERY_KEYWORDS = setOf(
            "runtime", "运行时", "进程", "终端", "命令", "shell", "git", "python", "node",
        )
        val TOOL_NAMES = listOf(
            "runtime_command_status",
            "process_exec",
            "terminal_open",
            "terminal_write",
            "terminal_read",
            "terminal_status",
            "terminal_close",
            "terminal_resize",
        )
    }
}
