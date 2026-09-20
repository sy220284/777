package com.labteto.dshmobile.local

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OpenAI-compatible DeepSeek transport used by the on-device agent loop. */
@Singleton
class DeepSeekClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    /** Run one model step and preserve its raw assistant message for tool continuation. */
    suspend fun complete(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray = LocalToolCatalog.specs,
    ): LocalModelReply = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            put("stream", false)
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                        ?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
                error("模型请求失败（${response.code}）：${detail ?: body.take(500)}")
            }
            parse(body)
        }
    }

    internal fun parse(body: String): LocalModelReply {
        val root = json.parseToJsonElement(body).jsonObject
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject ?: error("模型响应缺少 choices[0].message")
        val calls = message["tool_calls"]?.jsonArray.orEmpty().map { element ->
            val item = element.jsonObject
            val function = item["function"]?.jsonObject ?: error("工具调用缺少 function")
            val raw = function["arguments"]?.jsonPrimitive?.content ?: "{}"
            LocalToolCall(
                id = item["id"]?.jsonPrimitive?.content ?: error("工具调用缺少 id"),
                name = function["name"]?.jsonPrimitive?.content ?: error("工具调用缺少 name"),
                arguments = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
                    error("工具参数不是合法对象：${it.message}")
                },
                rawArguments = raw,
            )
        }
        return LocalModelReply(
            message = message,
            content = message["content"]?.jsonPrimitive?.contentOrNull,
            reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull,
            toolCalls = calls,
        )
    }

    private fun endpoint(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** Model-facing tools mirroring the official Harness capability families on Android. */
object LocalToolCatalog {
    val specs: JsonArray = buildJsonArray {
        add(tool("read_file", "读取本机工作区内的文本文件", properties(
            "path" to string("相对工作区的路径"),
            "start_line" to integer("起始行，默认 1"),
            "end_line" to integer("结束行，默认读取 400 行"),
        ), listOf("path")))
        add(tool("write_file", "创建或完整替换工作区文件；执行前需要用户批准", properties(
            "path" to string("相对工作区的路径"),
            "content" to string("完整文件内容"),
        ), listOf("path", "content")))
        add(tool("list_files", "列出工作区目录", properties(
            "path" to string("相对路径，默认 ."),
            "depth" to integer("递归深度，1 到 8"),
        )))
        add(tool("search_text", "在工作区文件中搜索文字", properties(
            "query" to string("搜索内容"),
            "path" to string("相对路径，默认 ."),
        ), listOf("query")))
        add(tool("run_shell", "在应用工作区执行 Android 系统 shell；执行前需要用户批准", properties(
            "command" to string("shell 命令"),
            "timeout_seconds" to integer("超时秒数，默认 30，最大 120"),
        ), listOf("command")))
        add(tool("web_fetch", "通过 HTTPS 或 HTTP 获取网页文本", properties(
            "url" to string("完整网址"),
        ), listOf("url")))
        add(tool("update_plan", "更新当前任务计划", properties(
            "items" to buildJsonObject {
                put("type", "array")
                put("description", "按执行顺序排列的计划项")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("items")))
        add(tool("list_skills", "列出工作区 .dsh/skills 下已安装的技能", properties()))
        add(tool("read_skill", "读取一个已安装技能的 SKILL.md", properties(
            "name" to string("技能目录名"),
        ), listOf("name")))
        add(tool("spawn_subagent", "启动一个只读子代理处理独立子任务", properties(
            "task" to string("交给子代理的完整任务"),
        ), listOf("task")))
    }

    private fun tool(name: String, description: String, parameters: JsonObject, required: List<String> = emptyList()) =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", if (required.isEmpty()) parameters else JsonObject(parameters + (
                    "required" to JsonArray(required.map(::JsonPrimitive))
                    )))
            })
        }

    private fun properties(vararg entries: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(entries.toMap()))
        put("additionalProperties", false)
    }

    private fun string(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun integer(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }
}
