package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.functionToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Model-facing tools mirroring the official Harness capability families on Android. */
object LocalToolCatalog {
    val specs: JsonArray = buildJsonArray {
        add(tool("read", "读取本机工作区内的文本文件", properties(
            "path" to string("相对工作区的路径"),
            "start_line" to integer("起始行，默认 1"),
            "end_line" to integer("结束行，默认读取 400 行"),
        ), listOf("path")))
        add(tool("tool_output_read", "按 UTF-8 字节区间读取此前因上下文预算而省略的完整工具结果", properties(
            "call_id" to string("原工具调用编号"),
            "start_byte" to integer("起始 UTF-8 字节偏移，默认 0；优先使用上次结果给出的下一偏移"),
            "max_bytes" to integer("单次读取字节数，默认 4096，允许 1024–49152；单页实际交付不超过当前可见额度，请按返回的下一偏移继续读取"),
        ), listOf("call_id")))
        add(tool("write", "创建或完整替换工作区文件", properties(
            "path" to string("相对工作区的路径"),
            "content" to string("完整文件内容"),
        ), listOf("path", "content")))
        add(tool("edit", "唯一字面量替换；修改前必须先用 read 读取包含待替换内容的目标区域，write 创建或覆盖不算读取", properties(
            "path" to string("相对工作区的路径"),
            "old_text" to string("必须只出现一次的原文"),
            "new_text" to string("替换后的文字"),
        ), listOf("path", "old_text", "new_text")))
        add(tool("apply_patch", "应用标准 unified diff 补丁到工作区；先执行 git apply --check，成功后原子式应用", properties(
            "patch" to string("完整 unified diff 文本"),
        ), listOf("patch")))
        add(tool("file_inspect", "检查工作区文件元数据；图片返回宽高和可用的常见 EXIF，不解码整张图片", properties(
            "path" to string("相对工作区的文件路径"),
        ), listOf("path")))
        add(tool("list_files", "列出工作区目录", properties(
            "path" to string("相对路径，默认 ."),
            "depth" to integer("递归深度，1 到 8"),
        )))
        add(tool("glob", "按 glob 模式发现工作区文件", properties(
            "pattern" to string("例如 **/*.kt"),
            "path" to string("相对路径，默认 ."),
        ), listOf("pattern")))
        add(tool("grep", "在工作区文件中搜索文字；默认字面量，可指定正则表达式", properties(
            "query" to string("搜索内容；regex=true 时按正则表达式解析"),
            "path" to string("相对路径，默认 ."),
            "regex" to boolean("是否按正则表达式搜索，默认 false"),
        ), listOf("query")))
        add(tool("bash", "在应用工作区执行 Android 系统 shell", properties(
            "command" to string("shell 命令"),
            "timeout_seconds" to integer("进程执行超时秒数；前台默认 30/最大 120，后台默认 300/最大 900；会话切换等主动取消不受该值约束"),
            "run_in_background" to boolean("是否转为后台任务，默认 false；普通后台 shell 属当前会话非持久任务，创建、切换或删除会话时会取消"),
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
        add(tool("web_fetch", "通过 HTTPS 或 HTTP 获取网页内容；大响应会自动完整落盘并返回工作区路径", properties(
            "url" to string("完整网址"),
            "max_bytes" to integer("最多读取字节数，默认 4194304，最大 4194304"),
            "format" to buildJsonObject {
                put("type", "string")
                put("description", "text 提取可读文本，raw 保留原始响应；默认 text")
                put("enum", buildJsonArray { add(JsonPrimitive("text")); add(JsonPrimitive("raw")) })
            },
            "run_in_background" to boolean("是否转为后台抓取任务；后台模式使用更长网络时限，默认 false"),
        ), listOf("url")))
        add(tool("http_request", "向公网 HTTP/HTTPS API 发起受限请求；支持 GET/HEAD/POST/PUT/PATCH/DELETE；GET/HEAD 对瞬时传输失败自动最多重试 3 次并返回白名单限流响应头；认证类请求头禁止写入工具参数", properties(
            "method" to string("GET、HEAD、POST、PUT、PATCH 或 DELETE"),
            "url" to string("完整公网 HTTP/HTTPS 地址"),
            "headers" to buildJsonObject {
                put("type", "object")
                put("description", "可选；仅允许 Accept、Content-Type、If-None-Match、If-Modified-Since")
                put("additionalProperties", buildJsonObject { put("type", "string") })
            },
            "body" to string("可选；请求体，最大 1 MiB"),
            "max_bytes" to integer("最多读取响应字节数，最大 4194304"),
        ), listOf("method", "url")))
        add(tool("download_file", "把公网 HTTP/HTTPS 文件流式下载到工作区并计算 SHA-256", properties(
            "url" to string("完整公网 HTTP/HTTPS 地址"),
            "path" to string("工作区相对目标路径"),
            "max_bytes" to integer("最大下载字节数，默认 20971520，最高 104857600"),
        ), listOf("url", "path")))
        add(tool("json_query", "从工作区 JSON 文件读取一个字段/数组片段，无需 jq；支持 a.b[0].c 形式", properties(
            "path" to string("JSON 文件的工作区相对路径"),
            "query" to string("字段路径，例如 items[0].name；留空返回根节点摘要"),
        ), listOf("path")))
        add(tool("network_diagnose", "诊断域名解析、系统代理、VPN/TUN、安全策略并最多连续 3 次探测 HTTP/TLS 连通性；单次 EOF/超时不会直接判定为阻断", properties(
            "url" to string("要诊断的网址或域名"),
        ), listOf("url")))
        add(tool("environment_info", "查看安卓本机 Harness 的可用环境能力与限制", properties()))
        add(tool("capability_search", "按需发现并启用当前回合的扩展工具；需要联网、下载、目标/待办、记忆管理、会话追踪、Android、视觉、GitHub、MCP、LSP、自动化或 Webhook 能力时先调用", properties(
            "query" to string("能力关键词，例如 联网搜索、下载、记忆、会话轨迹、GitHub PR、Android 界面、视觉、MCP、LSP、自动化"),
        ), listOf("query")))
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
        add(tool("subagent", "启动一个只读子代理处理独立子任务；同一工具块中的多个子代理可并行且互不级联取消", properties(
            "task" to string("交给子代理的完整任务"),
            "model" to string("可选；使用 list_subagent_models 返回的 profileId，或无歧义的模型名；留空继承父代理模型"),
            "max_steps" to integer("最大模型/工具循环步数，默认 20，可配置 1 到 512"),
            "virtual_screen" to boolean("是否为子代理分配独立虚拟屏；用于并行操作 Android 界面，默认 false"),
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
        add(tool("workflow", "并行执行只读子任务，或按顺序传递结果；逐项验收，失败最多重新指派一次", properties(
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
            "required_evidence" to buildJsonObject {
                put("type", "array")
                put("description", "可选；与 tasks 逐项对应的产出中必须出现的可核对原文。不提供时仅检查产出非空。")
                put("items", buildJsonObject { put("type", "string") })
            },
            "model" to string("可选；子任务使用的模型档案编号或模型名。留空时按 Worker 路由策略选择。"),
        ), listOf("tasks")))
        add(tool("session_event_search", "分页搜索当前会话的追加式事件日志；单页结果受上下文安全上限约束", properties(
            "query" to string("搜索内容"),
            "session_id" to string("可选；留空使用当前会话"),
            "limit" to integer("单页最多返回条数，默认 50，最大 100"),
            "after_sequence" to integer("可选分页游标；继续上一页时传结果末尾提示的事件序号"),
        ), listOf("query")))
        add(tool("session_search", "跨本机历史会话搜索事件", properties(
            "query" to string("搜索内容"),
        ), listOf("query")))
        add(tool("memory_search", "搜索当前会话允许作用域内的长期记忆", properties(
            "query" to string("搜索内容"),
        ), listOf("query")))
        add(tool("memory_list", "列出当前会话允许查看的最近长期记忆，仅在用户明确要求查看记忆时使用", properties()))
        add(tool("memory_remember", "保存一条长期记忆；只用于明确长期规则、偏好、项目决定或用户明确要求记住的内容", properties(
            "content" to string("需要长期保存的精炼内容"),
            "scope" to buildJsonObject {
                put("type", "string")
                put("description", "global 全局、project 当前项目、lineage 当前连续任务")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("global"))
                    add(JsonPrimitive("project"))
                    add(JsonPrimitive("lineage"))
                })
            },
            "kind" to buildJsonObject {
                put("type", "string")
                put("description", "rule / preference / fact / decision / constraint / state / summary")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("rule"))
                    add(JsonPrimitive("preference"))
                    add(JsonPrimitive("fact"))
                    add(JsonPrimitive("decision"))
                    add(JsonPrimitive("constraint"))
                    add(JsonPrimitive("state"))
                    add(JsonPrimitive("summary"))
                })
            },
        ), listOf("content", "scope")))
        add(tool("memory_update", "按稳定记忆 id 更新当前作用域内的一条长期记忆；执行前需要用户批准", properties(
            "id" to string("memory_search 或 memory_list 返回的完整 id，也可使用唯一前缀"),
            "content" to string("可选；新的记忆内容"),
            "kind" to buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("rule")); add(JsonPrimitive("preference")); add(JsonPrimitive("fact"))
                    add(JsonPrimitive("decision")); add(JsonPrimitive("constraint")); add(JsonPrimitive("state")); add(JsonPrimitive("summary"))
                })
            },
            "importance" to integer("可选；0 到 100"),
            "pinned" to boolean("可选；是否固定提高召回优先级"),
        ), listOf("id")))
        add(tool("memory_forget", "按稳定记忆 id 停用当前作用域内的一条长期记忆；执行前需要用户批准", properties(
            "id" to string("memory_search 或 memory_list 返回的完整 id，也可使用唯一前缀"),
        ), listOf("id")))
        add(tool("session_trace", "读取一个会话最近的事件轨迹", properties(
            "session_id" to string("可选；留空使用当前会话"),
            "limit" to integer("返回条数，默认 40，最大 200"),
        )))
        add(tool("session_event_trace", "读取指定会话事件及其直接邻近事件", properties(
            "session_id" to string("可选；留空使用当前会话"),
            "seq" to integer("事件序号"),
        ), listOf("seq")))
        add(tool("session_event_read", "按字符分页读取事件和可选的前后事件", properties(
            "session_id" to string("可选；留空使用当前会话"),
            "seq" to integer("事件序号"),
            "before" to integer("前置事件数，最多 20"),
            "after" to integer("后置事件数，最多 20"),
            "offset_chars" to integer("可选；按上一页提示继续读取的字符偏移，默认 0"),
        ), listOf("seq")))
        add(tool("present", "把工作区中的成果文件标记为最终交付物", properties(
            "path" to string("成果文件的相对路径"),
        ), listOf("path")))
    }

    private fun tool(name: String, description: String, parameters: JsonObject, required: List<String> = emptyList()) =
        functionToolSchema(
            name = name,
            description = description,
            parameterSchema = if (required.isEmpty()) {
                parameters
            } else {
                JsonObject(parameters + ("required" to JsonArray(required.map(::JsonPrimitive))))
            },
        )

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
