package com.labteto.dshmobile.local

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
        runInterruptible { http.newCall(request).execute() }.use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                        ?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
                throw LocalModelException(
                    message = "模型请求失败（${response.code}）：${detail ?: body.take(500)}",
                    retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                )
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

class LocalModelException(message: String, val retryable: Boolean) : Exception(message)

/** Model-facing tools mirroring the official Harness capability families on Android. */
object LocalToolCatalog {
    val specs: JsonArray = buildJsonArray {
        add(tool("read", "读取本机工作区内的文本文件", properties(
            "path" to string("相对工作区的路径"),
            "start_line" to integer("起始行，默认 1"),
            "end_line" to integer("结束行，默认读取 400 行"),
        ), listOf("path")))
        add(tool("write", "创建或完整替换工作区文件；执行前需要用户批准", properties(
            "path" to string("相对工作区的路径"),
            "content" to string("完整文件内容"),
        ), listOf("path", "content")))
        add(tool("edit", "唯一字面量替换；修改前必须先读取文件，执行前需要用户批准", properties(
            "path" to string("相对工作区的路径"),
            "old_text" to string("必须只出现一次的原文"),
            "new_text" to string("替换后的文字"),
        ), listOf("path", "old_text", "new_text")))
        add(tool("list_files", "列出工作区目录", properties(
            "path" to string("相对路径，默认 ."),
            "depth" to integer("递归深度，1 到 8"),
        )))
        add(tool("glob", "按 glob 模式发现工作区文件", properties(
            "pattern" to string("例如 **/*.kt"),
            "path" to string("相对路径，默认 ."),
        ), listOf("pattern")))
        add(tool("grep", "在工作区文件中搜索文字", properties(
            "query" to string("搜索内容"),
            "path" to string("相对路径，默认 ."),
        ), listOf("query")))
        add(tool("bash", "在应用工作区执行 Android 系统 shell；执行前需要用户批准", properties(
            "command" to string("shell 命令"),
            "timeout_seconds" to integer("超时秒数，默认 30，最大 120"),
            "run_in_background" to boolean("是否转为后台任务，默认 false"),
        ), listOf("command")))
        add(tool("job_list", "列出本机会话创建的后台任务", properties()))
        add(tool("job_output", "读取后台任务状态和输出", properties(
            "job_id" to string("后台任务编号"),
        ), listOf("job_id")))
        add(tool("job_kill", "停止一个后台任务", properties(
            "job_id" to string("后台任务编号"),
        ), listOf("job_id")))
        add(tool("web_search", "通过 DeepSeek 官方搜索能力查询最新网页信息", properties(
            "queries" to buildJsonObject {
                put("type", "array")
                put("description", "1 到 4 个搜索词")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("queries")))
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
        add(tool("exit_plan_mode", "提交完整计划供用户审批；仅在规划模式中调用", properties(
            "plan" to string("完整、可直接执行的计划"),
        ), listOf("plan")))
        add(tool("todo_write", "替换当前实现任务清单", properties(
            "items" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("content", string("任务内容"))
                        put("status", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("pending")); add(JsonPrimitive("in_progress")); add(JsonPrimitive("completed"))
                            })
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("content")); add(JsonPrimitive("status")) })
                    put("additionalProperties", false)
                })
            },
        ), listOf("items")))
        add(tool("create_goal", "创建或替换当前会话目标", properties(
            "description" to string("目标和完成标准"),
        ), listOf("description")))
        add(tool("get_goal", "读取当前会话目标", properties()))
        add(tool("update_goal", "更新目标状态", properties(
            "status" to string("active、paused、completed 或 blocked"),
            "note" to string("状态说明"),
        ), listOf("status")))
        add(tool("ask_user_question", "暂停当前轮次并向用户询问无法自行确定的选择", properties(
            "question" to string("清晰、可直接回答的问题"),
            "options" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("question")))
        add(tool("skill", "列出技能，或读取指定技能的 SKILL.md", properties(
            "name" to string("可选；留空列出技能，填写后读取技能"),
        )))
        add(tool("subagent", "启动一个只读子代理处理独立子任务", properties(
            "task" to string("交给子代理的完整任务"),
            "model" to string("可选模型路由；留空继承父代理模型"),
            "run_in_background" to boolean("是否转为后台任务，默认 false"),
        ), listOf("task")))
        add(tool("subagent_fork", "继承当前会话上下文并启动子代理", properties(
            "task" to string("需要结合当前上下文处理的任务"),
        ), listOf("task")))
        add(tool("list_subagent_models", "列出安卓本机子代理可使用的模型路由", properties()))
        add(tool("list_agents", "列出当前会话启动的后台代理", properties()))
        add(tool("send_message", "向正在运行的后台代理追加消息", properties(
            "agent_id" to string("后台代理编号"),
            "message" to string("需要追加的消息"),
        ), listOf("agent_id", "message")))
        add(tool("interrupt_agent", "停止正在运行的后台代理", properties(
            "agent_id" to string("后台代理编号"),
        ), listOf("agent_id")))
        add(tool("workflow", "并行执行独立子任务，或按顺序把前一步结果交给下一步", properties(
            "tasks" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            },
            "mode" to buildJsonObject {
                put("type", "string")
                put("description", "parallel 并行或 pipeline 顺序执行，默认 parallel")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("parallel"))
                    add(JsonPrimitive("pipeline"))
                })
            },
        ), listOf("tasks")))
        add(tool("session_event_search", "搜索当前会话的追加式事件日志", properties(
            "query" to string("搜索内容"),
            "session_id" to string("可选；留空使用当前会话"),
        ), listOf("query")))
        add(tool("session_search", "跨本机历史会话搜索事件", properties(
            "query" to string("搜索内容"),
        ), listOf("query")))
        add(tool("session_trace", "读取一个会话最近的事件轨迹", properties(
            "session_id" to string("可选；留空使用当前会话"),
            "limit" to integer("返回条数，默认 40，最大 200"),
        )))
        add(tool("session_event_trace", "读取指定会话事件及其直接邻近事件", properties(
            "session_id" to string("可选；留空使用当前会话"),
            "seq" to integer("事件序号"),
        ), listOf("seq")))
        add(tool("session_event_read", "读取完整事件和可选的前后事件", properties(
            "session_id" to string("可选；留空使用当前会话"),
            "seq" to integer("事件序号"),
            "before" to integer("前置事件数，最多 20"),
            "after" to integer("后置事件数，最多 20"),
        ), listOf("seq")))
        add(tool("present", "把工作区中的成果文件标记为最终交付物", properties(
            "path" to string("成果文件的相对路径"),
        ), listOf("path")))
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

    private fun boolean(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }
}
