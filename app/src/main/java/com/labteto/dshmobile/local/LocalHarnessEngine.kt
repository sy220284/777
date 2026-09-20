package com.labteto.dshmobile.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A native Android implementation of the DeepSeek Harness execution loop.
 *
 * The official Harness keeps model-visible state in a durable session log and composes capabilities
 * around an agent loop. This engine preserves those two properties while replacing Node-specific
 * providers with Android providers: an app-private filesystem, `/system/bin/sh`, OkHttp and Android
 * Keystore. Remote mode remains separate and unchanged.
 */
@Singleton
class LocalHarnessEngine @Inject constructor(
    @ApplicationContext context: Context,
    private val apiKeys: LocalApiKeyStore,
    private val modelClient: DeepSeekClient,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = File(context.filesDir, "local-harness").apply { mkdirs() }
    private val workspace = LocalWorkspace(File(root, "workspace"))
    private val sessionFile = File(root, "session.json")
    private val preferences = context.getSharedPreferences("local_harness", Context.MODE_PRIVATE)
    private val modelHistory = mutableListOf<JsonObject>()
    private val persistenceQueue = Channel<LocalHarnessSession>(Channel.CONFLATED)
    private val _state = MutableStateFlow(LocalHarnessState(workspacePath = workspace.path))
    val state: StateFlow<LocalHarnessState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var approvalResponse: CompletableDeferred<Boolean>? = null

    init {
        seedWorkspace()
        scope.launch {
            for (snapshot in persistenceQueue) writeSession(snapshot)
        }
        scope.launch { load() }
    }

    /** Save the local model route and its encrypted credential. */
    fun configure(apiKey: String, model: String, baseUrl: String) {
        scope.launch {
            runCatching {
                apiKeys.put(apiKey)
                preferences.edit()
                    .putString(KEY_MODEL, model.ifBlank { DEFAULT_MODEL })
                    .putString(KEY_BASE_URL, baseUrl.ifBlank { DEFAULT_BASE_URL })
                    .apply()
                _state.update {
                    it.copy(
                        configured = true,
                        model = model.ifBlank { DEFAULT_MODEL },
                        baseUrl = baseUrl.ifBlank { DEFAULT_BASE_URL },
                        error = null,
                    )
                }
            }.onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    /** Queue one human turn for the on-device agent. */
    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || activeJob?.isActive == true || !_state.value.configured) return
        appendMessage("user", prompt)
        modelHistory += buildJsonObject {
            put("role", "user")
            put("content", prompt)
        }
        persist()
        activeJob = scope.launch { runTurn() }
    }

    /** Resolve the current write or shell approval. */
    fun answerApproval(approved: Boolean) {
        approvalResponse?.complete(approved)
    }

    /** Stop the active model/tool turn. */
    fun stop() {
        approvalResponse?.complete(false)
        activeJob?.cancel()
        activeJob = null
        _state.update { it.copy(running = false, pendingApproval = null) }
    }

    /** Clear the transcript and model history while retaining configuration and files. */
    fun newSession() {
        stop()
        modelHistory.clear()
        _state.update { it.copy(messages = emptyList(), plan = emptyList(), error = null) }
        persist()
    }

    /** Remove the local API key. */
    fun clearCredential() {
        stop()
        scope.launch {
            apiKeys.clear()
            _state.update { it.copy(configured = false) }
        }
    }

    private suspend fun runTurn() {
        _state.update { it.copy(running = true, error = null) }
        try {
            val key = apiKeys.get() ?: error("请先配置 DeepSeek API 密钥")
            ensureSystemMessage()
            repeat(MAX_STEPS) {
                val snapshot = _state.value
                val reply = modelClient.complete(
                    apiKey = key,
                    baseUrl = snapshot.baseUrl,
                    model = snapshot.model,
                    messages = modelHistory.toList(),
                )
                modelHistory += reply.message
                reply.reasoning?.takeIf { it.isNotBlank() }?.let {
                    appendMessage("reasoning", it)
                }
                reply.content?.takeIf { it.isNotBlank() }?.let {
                    appendMessage("assistant", it)
                }
                if (reply.toolCalls.isEmpty()) return
                reply.toolCalls.forEach { call ->
                    val result = runCatching { execute(call, allowMutation = true) }
                        .getOrElse { error -> "工具执行失败：${error.message ?: error::class.java.simpleName}" }
                    appendMessage("tool", result, call.name)
                    modelHistory += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", call.id)
                        put("content", result)
                    }
                    persist()
                }
            }
            appendMessage("system", "本轮达到 $MAX_STEPS 步安全上限，请继续发送消息以恢复任务。")
        } catch (_: CancellationException) {
            appendMessage("system", "本轮已停止。")
        } catch (error: Exception) {
            _state.update { it.copy(error = error.message ?: "本机 Harness 执行失败") }
            appendMessage("system", "执行失败：${error.message ?: error::class.java.simpleName}")
        } finally {
            approvalResponse = null
            _state.update { it.copy(running = false, pendingApproval = null) }
            persist()
        }
    }

    private suspend fun execute(call: LocalToolCall, allowMutation: Boolean): String {
        val args = call.arguments
        return when (call.name) {
            "read_file" -> workspace.read(
                relativePath = args.string("path"),
                startLine = args.int("start_line", 1),
                endLine = args.int("end_line", args.int("start_line", 1) + 399),
            )
            "write_file" -> {
                if (!allowMutation) return "子代理无写入权限"
                val path = args.string("path")
                if (!approve(call, "写入文件：$path")) return "用户拒绝写入 $path"
                workspace.write(path, args.string("content"))
            }
            "list_files" -> workspace.list(args.optionalString("path") ?: ".", args.int("depth", 3))
            "search_text" -> workspace.search(args.string("query"), args.optionalString("path") ?: ".")
            "run_shell" -> {
                if (!allowMutation) return "子代理无命令执行权限"
                val command = args.string("command")
                if (!approve(call, "执行命令：${command.take(160)}")) return "用户拒绝执行命令"
                workspace.shell(command, args.int("timeout_seconds", 30))
            }
            "web_fetch" -> fetch(args.string("url"))
            "update_plan" -> updatePlan(args)
            "list_skills" -> workspace.skills().takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "未安装技能"
            "read_skill" -> workspace.readSkill(args.string("name"))
            "spawn_subagent" -> runSubagent(args.string("task"))
            else -> "未知工具：${call.name}"
        }
    }

    private suspend fun approve(call: LocalToolCall, summary: String): Boolean {
        val response = CompletableDeferred<Boolean>()
        approvalResponse = response
        _state.update {
            it.copy(
                pendingApproval = LocalApproval(call.id, call.name, summary, call.rawArguments),
            )
        }
        return try {
            response.await()
        } finally {
            approvalResponse = null
            _state.update { it.copy(pendingApproval = null) }
        }
    }

    private suspend fun runSubagent(task: String): String {
        val key = apiKeys.get() ?: return "子代理无法读取模型密钥"
        val history = mutableListOf<JsonObject>()
        history += buildJsonObject {
            put("role", "system")
            put("content", "你是安卓本机 Harness 的只读子代理。完成指定子任务，可读取和搜索工作区、读取技能、获取网页；禁止写文件和执行命令。")
        }
        history += buildJsonObject { put("role", "user"); put("content", task) }
        repeat(MAX_SUBAGENT_STEPS) {
            val snapshot = _state.value
            val reply = modelClient.complete(key, snapshot.baseUrl, snapshot.model, history, READ_ONLY_TOOLS)
            history += reply.message
            if (reply.toolCalls.isEmpty()) return reply.content ?: "子代理已结束，但没有返回文字。"
            reply.toolCalls.forEach { call ->
                val result = runCatching { execute(call, allowMutation = false) }
                    .getOrElse { "工具执行失败：${it.message}" }
                history += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", call.id)
                    put("content", result)
                }
            }
        }
        return "子代理达到 $MAX_SUBAGENT_STEPS 步上限。"
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "仅允许 HTTP/HTTPS 地址" }
        val request = Request.Builder().url(url).header("User-Agent", "DSH-Mobile-Local/0.12").build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "网页请求失败：HTTP ${response.code}" }
            val body = response.body ?: return@use "网页没有响应正文"
            body.charStream().use { reader ->
                val buffer = CharArray(8_192)
                val output = StringBuilder()
                while (output.length < MAX_WEB_CHARS) {
                    val read = reader.read(buffer, 0, minOf(buffer.size, MAX_WEB_CHARS - output.length))
                    if (read < 0) break
                    output.append(buffer, 0, read)
                }
                "URL: $url\n${output}"
            }
        }
    }

    private fun updatePlan(args: JsonObject): String {
        val items = args["items"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: args.optionalString("plan")?.lines()?.filter { it.isNotBlank() }
            ?: emptyList()
        _state.update { it.copy(plan = items.take(20)) }
        return if (items.isEmpty()) "计划已清空" else "计划已更新，共 ${items.size} 项"
    }

    private fun ensureSystemMessage() {
        if (modelHistory.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") return
        modelHistory.add(
            0,
            buildJsonObject {
                put("role", "system")
                put("content", systemPrompt())
            },
        )
    }

    private fun systemPrompt(): String = """
        你是运行在安卓手机内部的 DeepSeek Harness。你拥有本机工作区、文件、文本搜索、Android shell、网页获取、技能、计划和只读子代理工具。
        当前工作区：${workspace.path}
        所有路径都使用相对工作区路径。先检查现状，再行动；文件写入和 shell 命令必须等待用户批准。不要声称执行了尚未通过工具完成的操作。
        安卓系统限制访问其他应用私有目录，也不会凭空提供 Python、Node、Git 等桌面程序。遇到缺失命令时，说明限制并使用现有工具完成可行部分。
        把重要进度写入计划。结果以清晰中文回复。
    """.trimIndent()

    private fun appendMessage(role: String, content: String, toolName: String? = null) {
        val message = LocalHarnessMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            content = content,
            toolName = toolName,
            createdAt = System.currentTimeMillis(),
        )
        _state.update { it.copy(messages = it.messages + message) }
        persist()
    }

    private suspend fun load() {
        val model = preferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val baseUrl = preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        val stored = runCatching {
            if (sessionFile.isFile) json.decodeFromString(LocalHarnessSession.serializer(), sessionFile.readText())
            else LocalHarnessSession()
        }.getOrDefault(LocalHarnessSession())
        modelHistory.clear()
        modelHistory += stored.modelHistory
        _state.value = LocalHarnessState(
            loading = false,
            configured = apiKeys.get() != null,
            model = model,
            baseUrl = baseUrl,
            workspacePath = workspace.path,
            messages = stored.messages,
        )
    }

    private fun persist() {
        val snapshot = LocalHarnessSession(_state.value.messages, modelHistory.toList())
        persistenceQueue.trySend(snapshot)
    }

    private fun writeSession(snapshot: LocalHarnessSession) {
        runCatching {
            val temporary = File(root, "session.json.tmp")
            temporary.writeText(json.encodeToString(LocalHarnessSession.serializer(), snapshot))
            if (!temporary.renameTo(sessionFile)) {
                sessionFile.writeText(temporary.readText())
                temporary.delete()
            }
        }
    }

    private fun seedWorkspace() {
        val skill = File(workspace.path, ".dsh/skills/workspace-guide/SKILL.md")
        if (!skill.exists()) {
            skill.parentFile?.mkdirs()
            skill.writeText(
                """
                # 工作区指南

                - 所有文件操作限定在当前应用的本机工作区。
                - 修改前先读取原文件，完成后重新读取或搜索关键内容复核。
                - shell 使用安卓 `/system/bin/sh`，只依赖系统现有命令。
                """.trimIndent() + "\n",
            )
        }
    }

    private fun JsonObject.string(key: String): String =
        optionalString(key)?.takeIf { it.isNotBlank() } ?: error("缺少参数：$key")

    private fun JsonObject.optionalString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String, default: Int): Int = this[key]?.jsonPrimitive?.intOrNull ?: default

    private companion object {
        const val KEY_MODEL = "model"
        const val KEY_BASE_URL = "base_url"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val MAX_STEPS = 16
        const val MAX_SUBAGENT_STEPS = 6
        const val MAX_WEB_CHARS = 200_000

        val READ_ONLY_TOOLS = JsonArray(
            LocalToolCatalog.specs.filter { spec ->
                spec.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull in
                    setOf("read_file", "list_files", "search_text", "web_fetch", "list_skills", "read_skill")
            },
        )
    }
}
