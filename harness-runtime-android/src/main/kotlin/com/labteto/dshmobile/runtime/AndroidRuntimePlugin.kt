package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.CapabilityDescriptor
import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolResult
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
) : HarnessPlugin {
    override val id: String = "android-runtime"

    override suspend fun install(context: HarnessContext) {
        context.tools.register(
            HarnessTool(
                name = "runtime_command_status",
                schema = runtimeStatusSchema(),
                access = ToolAccess.READ_ONLY,
                timeoutMillis = 5_000L,
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
                executor = HarnessToolExecutor { _, input, _ ->
                    val command = input["command"]
                        ?.takeIf { it is JsonArray }
                        ?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.filter(String::isNotBlank)
                        ?.takeIf { it.isNotEmpty() }
                        ?: listOf("/system/bin/sh")
                    val workingDirectory = resolveWorkingDirectory(input.optionalString("working_directory"))
                    ToolResult(terminalProvider.open(command, workingDirectory))
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
                executor = HarnessToolExecutor { _, input, _ ->
                    terminalProvider.write(input.requiredString("session_id"), input.requiredString("input"))
                    ToolResult("已写入终端会话")
                },
            ),
        )

        context.tools.register(
            HarnessTool(
                name = "terminal_read",
                schema = terminalSessionSchema("terminal_read", "读取持久终端当前可用输出"),
                access = ToolAccess.READ_ONLY,
                timeoutMillis = 10_000L,
                executor = HarnessToolExecutor { _, input, _ ->
                    ToolResult(terminalProvider.read(input.requiredString("session_id")))
                },
            ),
        )

        context.tools.register(
            HarnessTool(
                name = "terminal_status",
                schema = terminalSessionSchema("terminal_status", "检查持久终端会话是否仍在运行"),
                access = ToolAccess.READ_ONLY,
                timeoutMillis = 5_000L,
                executor = HarnessToolExecutor { _, input, _ ->
                    val sessionId = input.requiredString("session_id")
                    ToolResult(
                        buildJsonObject {
                            put("session_id", sessionId)
                            put("alive", terminalProvider.isAlive(sessionId))
                            put("native_pty", terminalProvider.nativePtyAvailable())
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
                executor = HarnessToolExecutor { _, input, _ ->
                    terminalProvider.close(input.requiredString("session_id"))
                    ToolResult("终端会话已关闭")
                },
            ),
        )

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
                attributes = mapOf("nativePty" to terminalProvider.nativePtyAvailable().toString()),
            ),
            terminalProvider,
        )
    }

    override suspend fun uninstall(context: HarnessContext) {
        TOOL_NAMES.forEach(context.tools::unregister)
        context.capabilities.unregister("android-process-runtime")
        context.capabilities.unregister("android-terminal-provider")
    }

    private fun resolveWorkingDirectory(requested: String?): String {
        val root = workspaceRoot.canonicalFile
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

    private fun runtimeStatusSchema(): JsonObject = functionSchema(
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

    private fun processExecSchema(): JsonObject = functionSchema(
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
            put("timeout_ms", buildJsonObject { put("type", "integer") })
        },
        required = setOf("command"),
    )

    private fun terminalOpenSchema(): JsonObject = functionSchema(
        "terminal_open",
        "打开一个跨多次工具调用保持存活的交互进程；当前为管道终端，不伪装完整 PTY",
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
        },
    )

    private fun terminalWriteSchema(): JsonObject = functionSchema(
        "terminal_write",
        "向持久终端写入标准输入",
        buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string") })
            put("input", buildJsonObject { put("type", "string") })
        },
        required = setOf("session_id", "input"),
    )

    private fun terminalSessionSchema(name: String, description: String): JsonObject = functionSchema(
        name,
        description,
        buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string") })
        },
        required = setOf("session_id"),
    )

    private fun functionSchema(
        name: String,
        description: String,
        properties: JsonObject,
        required: Set<String> = emptySet(),
    ): JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", name)
                put("description", description)
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", "object")
                        put("properties", properties)
                        put(
                            "required",
                            buildJsonArray {
                                required.forEach { add(JsonPrimitive(it)) }
                            },
                        )
                        put("additionalProperties", false)
                    },
                )
            },
        )
    }

    private companion object {
        const val MIN_PROCESS_TIMEOUT_MILLIS = 100L
        const val DEFAULT_PROCESS_TIMEOUT_MILLIS = 30_000L
        const val MAX_PROCESS_TIMEOUT_MILLIS = 120_000L
        const val PROCESS_TOOL_TIMEOUT_MILLIS = 125_000L
        const val MAX_TOOL_OUTPUT_CHARS = 120_000
        val TOOL_NAMES = listOf(
            "runtime_command_status",
            "process_exec",
            "terminal_open",
            "terminal_write",
            "terminal_read",
            "terminal_status",
            "terminal_close",
        )
    }
}
