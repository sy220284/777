# 模型默认协议与统一回复通道（2026-10-20）

## 协议选择原则

预设模型使用经官方文档确认、且当前 777 适配器真正支持的协议。模型名称不能单独决定用户已经保存的路由协议；**协议、地址和凭据绑定必须整体一致**。

| 预设 | 新建路由协议 | 新建路由 Base URL | 说明 |
| --- | --- | --- | --- |
| DeepSeek Flash / V4 Pro | Chat Completions | `https://api.deepseek.com` | 保持已验证的图片／工具历史适配 |
| MiniMax M3.1 Flash / M3 | Anthropic Messages | `https://api.minimaxi.com/anthropic/v1` | 原生 thinking/text/tool_use 分块，工具续轮回放 |
| MiniMax M2.7 / highspeed | Chat Completions | `https://api.minimaxi.com/v1` | 不切换到当前仅对 M3 系列确认支持的 Anthropic 接口 |
| Kimi Code K3 / coding | Chat Completions | `https://api.kimi.com/coding/v1` | 保留订阅路由及原有图像／视频输入链 |
| OpenAI GPT API（历史兼容元数据） | Responses | `https://api.openai.com/v1` | 保持原 Responses 模型策略 |
| ChatGPT 套餐 | Responses | 帐号绑定官方端点 | 独立鉴权与模型目录 |
| Claude 官方（历史兼容元数据） | Anthropic Messages | `https://api.anthropic.com/v1` | 继续使用 x-api-key |
| GLM / Gemini / Qwen（历史兼容元数据） | Chat Completions | 各自既有兼容 URL | 保持旧路线 |

- **已有用户配置不迁移**：`/v1` 的 MiniMax M3 仍使用 Chat Completions；新建 `/anthropic/v1` 才使用 Anthropic Messages。模型查找匹配地区官方域名时也按地址反推对应协议。自定义代理不继承官方能力。
- **无效温度控制**：MiniMax M3.1/M3 在官方协议的 temperature 被忽略，所以能力表禁用温度、设置页隐藏滑块、请求组装不发这个参数；M2 现有参数行为保留。
- **长任务输出**：Anthropic Messages 必须携带 `max_tokens`，思考也计入此值；MiniMax M3.1/M3 的预设默认请求上限设置为 65536，避免落入通用适配器 8192 的短回复默认值。实际可用上限仍取决于厂商账户。
- **鉴权匹配**：MiniMax Anthropic 兼容地址请求使用 `Authorization: Bearer`，Claude 官方继续 `x-api-key`。

## 回复显示统一契约

Chat Completions、Responses、Anthropic Messages 适配器统一输出 `LocalModelDelta(content, reasoning)` 与最终 `LocalModelReply`。新版本的 `LocalModelRequestCoordinator` 将两条增量分别送入同一个 `LocalStreamingPreviewStore`，按同一请求所有权、屏幕更新节流和最大预览长度管理：

- 正文 `content` → 聊天 Markdown 正文／工作模式正文预览，事件日志确认后由正式消息替换；
- 思考 `reasoning` → 聊天的 `ChatThinkingRow`／工作模式的“思考中”状态，避免把思考塞进正文；
- 工具调用 → 保留原始工具身份、执行结果与对应来源，交给已有工作时间线；
- 图片、音视频与文件成果 → 继续由已有多模态渲染与来源关联链处理，不在模型协议层制造新组件。

MiniMax Chat Completions 旧路由继续分离前导 `<think>`；Anthropic 的 thinking/text/tool_use 使用原生结构化事件；Responses 的 reasoning/output_text 使用事件快照。原始协议 replay 不被展示层改写，确保工具续轮语义不丢失。

## 验收边界

自动化回归覆盖：新旧 MiniMax 协议路由、无效温度、OpenAI/Claude/DeepSeek 等默认协议、不同鉴权请求头、思考／正文双通道与旧请求隔离。CI 执行结果以实际 GitHub 检查为准；无真实厂商密钥时不能宣称已通过在线服务联调。

官方参考：
- [MiniMax Anthropic SDK](https://platform.minimax.io/docs/api-reference/text-anthropic-api)
- [MiniMax OpenAI SDK](https://platform.minimax.io/docs/api-reference/text-openai-api)
- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)
