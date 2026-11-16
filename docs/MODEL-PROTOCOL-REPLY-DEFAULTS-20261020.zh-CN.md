# 模型默认协议与统一回复通道（2026-11-16）

## 协议选择原则

新建预置模型按官方推荐及 777 已实现的原生适配器选择协议。已保存配置仍由其 `profile.protocol` 决定路由；编辑页允许修改预置协议，变更前可进行连接测试。模型、协议、API 地址与凭据须共同形成路由身份；正在执行的会话不随前台修改漂移。

| 模型 | 新增配置默认协议 | Base URL | 决策依据 |
| --- | --- | --- | --- |
| DeepSeek `deepseek-flash`、`deepseek-v4-pro` | Responses | `https://api.deepseek.com` | 官方原生 Responses，支持 reasoning effort、函数工具、SSE 及图片输入（仅 Flash） |
| MiniMax `MiniMax-M3.1-Flash`、`MiniMax-M3` | Anthropic Messages | `https://api.minimaxi.com/anthropic/v1` | 原生 thinking / text / tool_use，按型号传递 output_config / thinking |
| MiniMax M2.7 / highspeed | 历史配置保留 Chat Completions | `https://api.minimaxi.com/v1` | 不再列入新增预置菜单；旧配置及地域官方接口识别保持不变 |
| Kimi Code `k3.1-pro`、`k3.1-pro-highspeed`、`k3`、`k3-256k`、`kimi-for-coding`、`kimi-for-coding-highspeed` | Responses | `https://api.kimi.com/coding/v1` | 官方原生 Responses；工具、流式输出及思考档位直连 |
| OpenAI API 历史兼容元数据 | Responses | `https://api.openai.com/v1` | 沿用既有 API Key Responses |
| ChatGPT 套餐 | Responses | 帐号绑定官方地址 | 保持独立身份验证和模型目录 |
| Claude 官方历史元数据 | Anthropic Messages | `https://api.anthropic.com/v1` | 保持 x-api-key 与历史模型路线 |
| GLM、Gemini、Qwen 历史元数据 | Chat Completions | 历史各自兼容接口 | 保留已保存配置 |

**协议与多模态关联：** Kimi Code 官方 Responses 当前支持文字与图片，视频输入需原生 Chat Completions；K3.1 Pro 两款型号的原生 Chat 路由也支持视频。777 的能力投影按真实 `profile.protocol` 确定是否显示视频支持；预置默认 Responses，用户可通过预置编辑器明确改用 Chat Completions 处理视频。旧配置不自动改写协议，以免覆盖原来的用户选择和历史请求回放身份。Kimi K3.1 Pro 与 K3 的 1M 上下文需要拥有对应档位；默认 256K，高速旗舰的默认上下文为 1M。

## 协议执行与缓存

- `LocalModelGateway` 根据冻结的 profile protocol 分派到唯一适配器；Responses 使用 `/responses`，Anthropic 使用 `/messages`。与旧 Chat 对话共存时，各自原始响应仅在相同 route fingerprint 下回放。
- DeepSeek Responses 发送 `reasoning.effort`（`none/low/high/max`）、可选 `max_output_tokens`、函数工具及完整历史；官方接口无状态，不使用 `previous_response_id`。其 `store` 字段不生效，自动缓存也不接受 OpenAI 特有的 cache key。
- Kimi Code Responses 发送官方支持的 `reasoning.effort`，K3.1 Pro 始终保持思考开启；服务端固定采样参数不传用户温度。用户是否拥有高速/1M 配额由官方账户判定。
- OpenAI 专用 `reasoning.encrypted_content`、prompt cache options 继续仅用于适用的 OpenAI 路由。ChatGPT 套餐 Responses Lite 的工具封装与请求参数保持独立。
- MiniMax M3.1 / M3 的 Anthropic Messages 默认 `max_tokens = 65536`，支持请求级输出预算、Bearer 鉴权、原生分块思考与工具历史；已保存 MiniMax /v1 Chat 路由保持原协议。

## 已实现的协议扩展与边界（2026-11-16）

- **DeepSeek Responses**：完整解析原生 `custom_tool_call`，其中 `apply_patch` 支持 `*** Begin Patch` 文本；本地统一转换为 Git diff，再经过路径校验、`git apply --check`、工作区写入权限及 Git 应用，工具续轮按原始 `call_id` 回传 `custom_tool_call_output`。其他普通函数工具继续使用 `function_call`。
- **MiniMax 国内 Messages**：M3.1 Flash / M3 视频文件使用本轮临时 Base64 构造 `type=video`、`source.type=base64`，限 MP4/AVI/MOV/MKV；以 44MB 直接传输上限留足 64MB JSON 请求体空间。工作区历史只保存文件引用，完整文件不进入持续对话记录。旧视频、跨模型历史按协议投影处理。
- **Responses 结构化输出**：网关已接通官方 DeepSeek 的 `text.format`（`json_object` 或 `json_schema`）；不将未验证的相同格式强行下发到其他兼容厂商。
- **协议安全边界**：MiniMax 官方身份需同时匹配 HTTPS、允许的 API 域名及路径和端口；不会因为自定义服务器复用厂商模型名而继承官方鉴权、视频与额度能力。
- **本轮只交付国内接口**：新模型预置固定 DeepSeek `api.deepseek.com`、MiniMax `api.minimaxi.com`、Kimi `api.kimi.com`；既有用户保存的历史兼容路由继续保留，不为海外地址新增预置或能力承诺。
- **SDK 选择**：继续采用 OkHttp + Kotlinx Serialization 的统一模型适配器，复用单一准入、取消、历史续轮、事件日志与权限机制。官方 Java SDK 可作为后续协议对照验证工具；目前引入额外 SDK 会增加移动端体积、复杂度及第三方兼容分支，尚无必要。

## 协议切换与全链路契约收敛（2026-11-16）

- **切换协议时同时调整请求地址**：MiniMax M3 / M3.1 Flash 的 Chat Completions 使用当前相同官方主机的 `/v1/chat/completions`，Anthropic Messages 使用 `/anthropic/v1/messages`。设置页展示的请求地址来自模型模块的同一规则；不支持的官方协议不出现在可选列表。其他厂商和自定义代理不进行隐式地址重写。
- **同一账号保留原配置与凭据**：MiniMax 在同一 HTTPS 官方域名、同一模型下切换这两种路径，可沿用配置 ID 和安全保存的密钥；跨主机、跨地区、代理或不同型号不能借此继承凭据。旧会话携带的路由指纹仍按模型、协议、地址和凭据变化隔离历史回放。
- **Kimi 会员上下文**：K3 / K3.1 Pro 的编辑界面只提供 256K 默认与 1M 会员两个选项，服务端账户权益仍由 Kimi 验证。用户输入、保存以及运行上下文推导共享同一批可选档位。
- **输入能力真实投影**：设置页显示当前选定协议实际可传输的模态；MiniMax M3 Anthropic Messages 和 Kimi 支持视频的 Chat 模型显示视频已接通，Kimi Responses 提示切换 Chat，不再统一显示“音视频理解暂未开放”。
- **结构化输出**：运行能力的 `structuredOutput` / `strictSchema` 仅对已接通官方 DeepSeek Responses `text.format` 的 API Key 路由声明可用；网关按冻结能力验证。其他协议可以通过现有提示词和本地结果校验完成结构化工作，但不声明已有同名的原生请求支持。
- **标准 HTTPS 端口**：MiniMax 标准 `https://host:443` 与省略端口的同源接口在额度和媒体功能识别上一致；跨服务和非标准端口保留严格的服务边界。
- **验收测试**：覆盖模型预置选项、协议切换地址、同源账号与密钥连续性、真实格式 Chat 请求、跨域拒绝、Kimi 档位、视频标识及结构化输出准入。

## UI 与回复契约

同一 `LocalModelDelta(content, reasoning)` 与 `LocalModelReply` 服务 Chat、Work 和子智能体。正文进入聊天/工作正文，思考进入思考区域；工具调用通过 Session EventLog 保留身份、结果和续轮，图片/视频/文件只暴露真正能传输的能力。预置编辑页提供协议选项及连接测试，若偏离推荐值给予明确提示。

## 验证边界

覆盖默认模型枚举、历史 MiniMax 区域兼容、Kimi 视频协议差异、思考档位与上下文档位、Responses `max_output_tokens`、请求头/地址、工具续轮、图像编码以及 ChatGPT 套餐既有契约。CI 或真实服务端验证应以实际回执为准；无真实服务商密钥不宣称已经完成线上联调。

官方参考：
- [DeepSeek Responses](https://api-docs.deepseek.com/guides/responses_api/)
- [DeepSeek 思考参数](https://api-docs.deepseek.com/guides/thinking_mode/)
- [Kimi Code 模型及接口](https://www.kimi.com/code/docs/kimi-code/models.html)
- [Kimi Code Codex 接入与视频限制](https://www.kimi.com/code/docs/third-party-tools/codex.html)
- [MiniMax Anthropic 接口](https://platform.minimax.io/docs/api-reference/text-anthropic-api)
