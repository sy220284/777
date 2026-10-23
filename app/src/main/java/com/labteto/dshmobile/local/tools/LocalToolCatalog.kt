package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.functionToolSchema
import com.labteto.dshmobile.local.model.WORK_TOOL_RECOVERY_PAGE_BYTES
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
        add(tool("tool_output_read", "读取本机会话仍保留的完整工具结果，或按原文字面量搜索匹配的 UTF-8 偏移", properties(
            "call_id" to string("工具结果返回的原文引用；旧结果兼容原调用编号"),
            "query" to string("可选；非空时按 UTF-8 字面量搜索原文并返回偏移，不必先全量读取"),
            "start_byte" to integerRange("起始 UTF-8 字节偏移，默认 0；优先使用上次结果给出的下一偏移", min = 0),
            "max_bytes" to integerRange("单次可见读取字节数，默认 ${WORK_TOOL_RECOVERY_PAGE_BYTES}，允许 1024–${WORK_TOOL_RECOVERY_PAGE_BYTES}；请按结果返回的下一偏移分页读取", min = 1_024, max = WORK_TOOL_RECOVERY_PAGE_BYTES),
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
            "cursor" to integer("可选；目录扫描未完时返回的下一页游标"),
        )))
        add(tool("glob", "按 glob 模式发现工作区文件", properties(
            "pattern" to string("例如 **/*.kt"),
            "path" to string("相对路径，默认 ."),
            "cursor" to integer("可选；目录扫描未完时返回的下一页游标"),
        ), listOf("pattern")))
        add(tool("grep", "在工作区文件中搜索文字；默认字面量，可指定正则表达式", properties(
            "query" to string("搜索内容；regex=true 时按正则表达式解析"),
            "path" to string("相对路径，默认 ."),
            "regex" to boolean("是否按正则表达式搜索，默认 false"),
            "cursor" to string("可选；上次返回的搜索未完成游标，保持 query/path/regex 不变以继续扫描"),
        ), listOf("query")))
        add(tool("bash", "在应用工作区执行 Android 系统 shell", properties(
            "command" to string("shell 命令"),
            "timeout_seconds" to integer("无 stdout/stderr 的连续空闲超时秒数，不是整个任务时长；每次实际输出都会续期。前台默认 30/最大 120，后台默认 300/最大 900；大型构建、全量扫描建议后台执行并持续输出进度，主动取消不受此值约束"),
            "run_in_background" to boolean("是否转为后台任务，默认 false；普通后台 shell 属当前会话非持久任务，创建、切换或删除会话时会取消"),
        ), listOf("command")))
        add(tool("job_list", "列出本机会话创建的后台任务", properties()))
        add(tool("job_output", "读取后台任务状态和输出", properties(
            "job_id" to string("后台任务编号"),
        ), listOf("job_id")))
        add(tool("job_kill", "停止一个后台任务", properties(
            "job_id" to string("后台任务编号"),
        ), listOf("job_id")))
        add(tool("media_image_options", "列出当前已配置且有真实 API Key 的图像创作服务和精确 offer_id；不会收费", properties()))
        add(tool("media_image_create", "使用明确 offer_id 的已配置媒体 API 提交图片生成或原图编辑；每次收费调用须经原有权限审批；返回真实异步回执编号，不代表已完成", properties(
            "offer_id" to string("必须来自 media_image_options，不能猜测或跨账户替换"),
            "prompt" to string("用户要求的图像内容或基于真实原图的修改指令"),
            "ratio" to string("图片比例，如 1:1 或 16:9；可选，默认 1:1"),
            "edit_source_image_id" to string("可选；当前会话已保存图片的 ID；原图编辑仅限服务商真实支持的 API"),
        ), listOf("offer_id", "prompt")))
        add(tool("media_image_status", "按任务 ID 查询当前会话已提交的真实图片生成回执与成果；不触发新的付费调用", properties(
            "job_id" to string("media_image_create 返回的任务 ID"),
        ), listOf("job_id")))
        add(tool("web_search", "搜索最新公开网页信息；默认使用免密公共搜索，连接失败时可使用已配置的 DeepSeek 备用通道", properties(
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
        add(tool("capability_search", "从实时注册表查看完整能力目录，或按自然语言、能力族、工具名发现并自动加载扩展。已连接 MCP 或新增插件的工具无需修改目录代码；如需总览可搜索『能力目录』。工具执行仍遵守连接、权限和审批要求", properties(
            "query" to string("例如 能力目录、工具与能力检测、GitHub PR、Android 界面、视觉、MCP、LSP、下载或某项具体工具名"),
        ), listOf("query")))
        add(tool("update_plan", "记录任务执行方案的步骤顺序，不记录进行中/已完成状态；进度用 todo_write，长期目标用 create_goal。仅复杂任务需要维护计划，避免重复写同一内容", properties(
            "items" to buildJsonObject {
                put("type", "array")
                put("description", "按执行顺序排列的计划项")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("items")))
        add(tool("exit_plan_mode", "提交完整计划供用户审批；仅在规划模式中调用", properties(
            "plan" to string("完整、可直接执行的计划"),
        ), listOf("plan")))
        add(tool("todo_write", "记录实际执行项及 pending/in_progress/completed 状态；它是进度权威来源，不要与 update_plan 重复维护步骤。按需使用", properties(
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
        add(tool("create_goal", "维护跨多轮任务的目标与验收条件。简单的一次性任务无需额外创建；不要代替 update_plan 或 todo_write", properties(
            "description" to string("目标和完成标准"),
            "artifact_paths" to buildJsonObject { put("type", "array"); put("items", string("按用户要求交付的工作区相对文件路径；讨论任务填空数组")) },
            "verification_commands" to buildJsonObject { put("type", "array"); put("items", string("按用户要求执行的完整 shell 命令原文或 process_exec command 的 JSON 数组原文；通过真实退出码和成果版本核验，不能以打印成功日志替代。无需命令的任务填空数组")) },
        ), listOf("description", "artifact_paths", "verification_commands")))
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
        add(tool("skill", "列出可由模型调用的技能（支持分页和任务关键词），或读取指定技能的 SKILL.md", properties(
            "name" to string("可选；填写后精确读取技能规则"),
            "query" to string("可选；按当前任务内容优先展示相关技能；分页时保持一致"),
            "offset" to integer("可选；继续上次技能目录的 offset，默认 0"),
        )))
        add(tool("skill_create", "将技能直接写入工作区、安装到真实技能目录并读取校验；不会自动证明执行效果", properties(
            "id" to string("英文技能标识"),
            "display_name" to string("技能中文名称"),
            "description" to string("用途，1—240 字"),
            "instructions" to string("完整执行规则，1—12000 字"),
        ), listOf("id", "display_name", "description", "instructions")))
        add(tool("skill_update", "更新已有技能，自动保存旧版本以便回退；参数为完整 SKILL.md", properties(
            "id" to string("已安装技能 ID"),
            "document" to string("新版本的完整 SKILL.md"),
        ), listOf("id", "document")))
        add(tool("skill_rollback", "按备份版本哈希回退技能，回退前也备份当前版本", properties(
            "id" to string("技能 ID"),
            "revision" to string("skill_update 返回的十六位版本哈希"),
        ), listOf("id", "revision")))
        add(tool("extension_record", "把自建技能、工具、连接器、MCP、插件的过程及证据登记到长期工作区；记录不等于验收", properties(
            "kind" to string("skill/tool/connector/mcp/plugin"),
            "id" to string("扩展 ID"),
            "phase" to string("discovered/created/registered/tested/improved/failed"),
            "summary" to string("本次做了什么，最多 1200 字"),
            "evidence_path" to string("工作区证据文件相对路径；tested 时必填"),
        ), listOf("kind", "id", "phase", "summary", "evidence_path")))
        add(tool("extension_inspect", "比较自建扩展工作区记录与当前真实技能、工具、插件注册状态；绝不把登记当实测", properties(
            "kind" to string("skill/tool/connector/mcp/plugin"),
            "id" to string("扩展 ID"),
        ), listOf("kind", "id")))
        add(tool("subagent", "统一的只读子代理入口：可隔离执行或继承当前对话上下文，同一工具块中多个调用可并行；始终禁止写入。需带写入权限的子代理仍须使用独立授权的 subagent_fork", properties(
            "task" to string("交给子代理的完整任务"),
            "instructions" to string("可选；该子代理持续遵循的工作规则，不会扩大工具或权限"),
            "context_mode" to buildJsonObject {
                put("type", "string")
                put("description", "isolated 只传任务（默认）；inherit 前台只读继承父会话历史，不能与后台或虚拟屏共用")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("isolated")); add(JsonPrimitive("inherit"))
                })
            },
            "model" to string("可选；使用 list_subagent_models 返回的 profileId，或无歧义的模型名；留空继承父代理模型"),
            "max_steps" to integer("最大模型/工具循环步数，默认 20，可配置 1 到 512"),
            "virtual_screen" to boolean("是否为子代理分配独立虚拟屏；用于并行操作 Android 界面，默认 false"),
            "output_schema" to jsonSchemaObject("可选；要求子代理最终只返回符合该 JSON Schema 的对象"),
            "allowed_tools" to stringArray("可选；限制子代理本次可见且可执行的工具名；包含当前不可用工具时启动直接失败"),
            "run_in_background" to boolean("是否转为后台任务，默认 false"),
        ), listOf("task")))
        add(tool("subagent_fork", "需要继承父会话当前操作权限时使用的高级代理入口；可能具有写入权限。纯阅读分析请使用 subagent 的 context_mode=inherit", properties(
            "task" to string("需要结合当前上下文处理的任务"),
            "instructions" to string("可选；继承上下文之外，额外约束该子代理持续遵循的工作规则"),
            "output_schema" to jsonSchemaObject("可选；要求子代理最终只返回符合该 JSON Schema 的对象"),
            "allowed_tools" to stringArray("可选；限制 fork 子代理可见且可执行的工具名"),
        ), listOf("task")))
        add(tool("list_subagent_models", "查看本机所有模型配置、凭据就绪状态、协议、模型能力及可用的子代理 profileId；可选向官方服务商查询该账户的模型目录，密钥始终由应用代管且不会输出", properties(
            "include_provider_models" to boolean("是否使用已保存凭据查询指定服务商官方 /models 接口；默认 false，不会在普通清单请求时联网"),
            "profile_id" to string("需要查询的已保存模型 profileId；联网查询时必填"),
        )))
        add(tool("list_agents", "列出当前会话启动的后台代理", properties()))
        add(tool("send_message", "向后台代理追加消息；可继续的持久子代理即使已完成或因进程中断，也会先持久排队并安全恢复同一身份", properties(
            "agent_id" to string("后台代理编号"),
            "message" to string("需要追加的消息"),
        ), listOf("agent_id", "message")))
        add(tool("interrupt_agent", "停止正在运行的后台代理", properties(
            "agent_id" to string("后台代理编号"),
        ), listOf("agent_id")))
        add(tool("workflow", "以持久 Kotlin DAG 调度子任务，恢复已验收节点并继续剩余依赖。默认只读；可写需显式授权且未知副作用不重放。非空/字符串验收仅是窄文本检查，不证明通用交付", properties(
            "tasks" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            },
            "mode" to buildJsonObject {
                put("type", "string")
                put("description", "parallel 全并行、pipeline 顺序，或 graph 自定义有向无环依赖；默认 parallel")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("parallel"))
                    add(JsonPrimitive("pipeline"))
                    add(JsonPrimitive("graph"))
                })
            },
            "dependencies" to buildJsonObject {
                put("type", "array")
                put("description", "graph 模式必填。与 tasks 等长的二维数组，dependencies[i] 为任务 i 依赖的零基任务序号；依赖必须已执行成功才启动，禁止循环和自依赖")
                put("items", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "integer") })
                })
            },
            "allow_mutation" to boolean("仅在当前主代理拥有写授权时，显式授权本图节点使用原有受控写工具；中断且副作用未知时停止，不自动重放"),
            "resume_graph_id" to string("可选；按未结算图编号显式恢复原完整不可变计划，不需要重传 tasks；新用户请求优先，恢复写图还需本次 allow_mutation=true"),
            "node_input_files" to buildJsonObject {
                put("type", "array"); put("description", "与 tasks 等长的逐节点输入文件路径数组；恢复时按节点内容版本校验")
                put("items", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
            },
            "node_output_files" to buildJsonObject {
                put("type", "array"); put("description", "与 tasks 等长的逐节点交付文件路径数组；真实存在才验收；版本变化后可写节点阻塞等待核验")
                put("items", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
            },
            "input_files" to stringArray("可选；实际输入文件路径；内容版本变化使节点与依赖结果失效，可写节点须显式重新规划"),
            "required_files" to stringArray("可选；每节点验收所需的工作区真实文件路径，同时持久内容版本用于恢复失效判断"),
            "required_evidence" to buildJsonObject {
                put("type", "array")
                put("description", "可选；与 tasks 逐项对应的产出中必须出现的可核对原文。不提供时仅检查产出非空。")
                put("items", buildJsonObject { put("type", "string") })
            },
            "model" to string("可选；子任务使用的模型档案编号或模型名。留空时按 Worker 路由策略选择。"),
            "output_schema" to jsonSchemaObject("可选；要求每个 Workflow 子代理最终都返回符合该 JSON Schema 的对象"),
            "allowed_tools" to stringArray("可选；所有 Workflow 子任务共享的工具 allowlist"),
        )))
        add(tool("team_members", "查看当前 Work Agent Team 的 Lead、成员、实时状态、进度与结果数量", properties()))
        add(tool("team_member_status", "查看一个 Team 成员的生命周期、运行状态、进度、当前任务与最近结果", properties(
            "target" to string("teammate 名称"),
        ), listOf("target")))
        add(tool("team_create_member", "只创建稳定 Team 成员身份，不立即启动模型执行；后续用 team_start_member 派活", properties(
            "name" to string("不可变 teammate 名称；仅字母、数字、下划线和短横线"),
            "description" to string("可选；职责说明"),
            "display_name" to string("可选；用户界面展示的中文成员名称"),
            "context" to buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("fresh"))
                    add(JsonPrimitive("fork"))
                })
            },
        ), listOf("name")))
        add(tool("team_start_member", "启动已创建或已停用的 Team 成员；停用成员会复用原身份和 durable mailbox", properties(
            "target" to string("teammate 名称"),
            "task" to string("本次任务"),
            "model" to string("可选；模型档案编号或无歧义模型名"),
            "max_steps" to integer("单次执行预算，默认沿用当前 Work 子代理配置"),
            "allowed_extensions" to stringArray("可选；Lead 核实后授权给本次启动成员的扩展工具名称"),
        ), listOf("target", "task")))
        add(tool("team_spawn", "创建具备持久身份、工作区读写权限和可选扩展授权的团队助手", properties(
            "name" to string("不可变 teammate 名称；仅字母、数字、下划线和短横线"),
            "description" to string("可选；职责说明"),
            "display_name" to string("可选；用户界面展示的中文成员名称"),
            "task" to string("首个任务"),
            "model" to string("可选；模型档案编号或无歧义模型名"),
            "max_steps" to integer("单次执行预算，默认沿用当前 Work 子代理配置"),
            "allowed_extensions" to stringArray("可选；Lead 显式授权的已连接 MCP、LSP、GitHub 等扩展工具名称"),
            "context" to buildJsonObject {
                put("type", "string")
                put("description", "上下文模式；fresh 使用独立新上下文，fork 在创建时把 Lead 当前历史物化为 Child 初始检查点；默认 fresh")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("fresh"))
                    add(JsonPrimitive("fork"))
                })
            },
        ), listOf("name", "task")))
        add(tool("team_send_message", "向指定 teammate 的持久 mailbox 发送消息；空闲成员会安全冷恢复", properties(
            "target" to string("teammate 名称"),
            "message" to string("消息内容"),
        ), listOf("target", "message")))
        add(tool("team_messages", "读取 Team 成员独立结果消息流；返回 durable EventLog sequence 作为结果游标", properties(
            "target" to string("可选；限定 teammate 名称"),
            "after_sequence" to integer("可选；仅返回该 EventLog sequence 之后的结果"),
            "limit" to integer("返回最近消息数，默认 20，最大 100"),
        )))
        add(tool("team_wait_for_message", "等待 Team 成员结果；after_sequence 后已有结果立即返回，否则等待新结果", properties(
            "target" to string("可选；限定 teammate 名称"),
            "after_sequence" to integer("结果游标；默认 -1，可把上一次返回的 sequence 传回以等待下一条"),
            "timeout_ms" to integer("等待毫秒数，1000–60000，默认 10000"),
        )))
        add(tool("team_task_create", "在 Team 共享任务板创建 pending 任务", properties(
            "subject" to string("任务标题"),
            "description" to string("可选；任务说明"),
            "blocked_by" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            },
            "write_scopes" to buildJsonObject {
                put("type", "array")
                put("description", "可选；提示性路径前缀，用于检测并发写范围重叠，不作为文件锁")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("subject")))
        add(tool("team_task_get", "读取 Team 任务（包括 deleted tombstone）", properties(
            "task_id" to string("task-N"),
        ), listOf("task_id")))
        add(tool("team_task_list", "列出 Team 当前未删除任务及 readiness / owner / write-scope 警告", properties()))
        add(tool("team_task_update", "用 compare-and-set revision 更新 Team 任务", properties(
            "task_id" to string("task-N"),
            "expected_revision" to integer("当前任务 revision；不匹配会拒绝写入"),
            "result_id" to string("complete 必填：核验通过的 team_messages 结果 ID；须晚于当前任务 revision"),
            "action" to buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray {
                    add(JsonPrimitive("claim"))
                    add(JsonPrimitive("release"))
                    add(JsonPrimitive("edit"))
                    add(JsonPrimitive("complete"))
                    add(JsonPrimitive("reopen"))
                    add(JsonPrimitive("reassign"))
                    add(JsonPrimitive("delete"))
                    add(JsonPrimitive("set_dependencies"))
                    add(JsonPrimitive("update"))
                })
            },
            "owner" to string("claim / reassign 时使用；active teammate 名称；reassign 留空可释放回 pending"),
            "subject" to string("update 时可选"),
            "description" to string("update 时可选"),
            "blocked_by" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            },
            "write_scopes" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("task_id", "expected_revision", "action")))
        add(tool("team_interrupt", "只中断指定 teammate 当前 Activation；身份、任务归属和 durable mailbox 保留", properties(
            "target" to string("teammate 名称"),
        ), listOf("target")))
        add(tool("team_disable_member", "停用 teammate；保留稳定身份和 mailbox，并自动释放其未完成 Team 任务", properties(
            "target" to string("teammate 名称"),
        ), listOf("target")))
        add(tool("team_dismiss_member", "永久解雇 teammate；终止其 Agent，并自动释放其未完成 Team 任务", properties(
            "target" to string("teammate 名称"),
        ), listOf("target")))
        add(tool("team_stop_all", "一次停止当前 Team 所有正在运行的成员 Activation；保留成员身份与可恢复状态", properties()))
        add(tool("team_wait", "等待 Team 事件或成员运行状态发生变化", properties(
            "timeout_ms" to integer("等待毫秒数，1000–60000，默认 10000"),
        )))

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

    private fun integerRange(description: String, min: Int, max: Int? = null) = buildJsonObject {
        put("type", "integer")
        put("description", description)
        put("minimum", min)
        max?.let { put("maximum", it) }
    }

    private fun boolean(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun stringArray(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject { put("type", "string") })
    }

    private fun jsonSchemaObject(description: String) = buildJsonObject {
        put("type", "object")
        put("description", description)
        put("additionalProperties", true)
    }
}
