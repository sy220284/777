# 777 × Codex × Claude 2.1.317：模型上下文与工具预算源码对齐

> 日期：2026-11-14。核验对象为当前 777 主线及本 PR，Codex 资料库校验源码快照，Claude Code 2.1.317 反编译包。以真实代码和 GitHub Actions 结果为准。

## 对照来源及证据层级

- Codex：资料库 `/codex/codex-main-2.zip` 中央目录受损；使用此前已校验的 `/codex_verified_local_snapshot_2026-11-13.zip`，并与公开仓库 `openai/codex` 的 `codex-rs/core/src/context/world_state/model.rs`、`top_level_tools.rs`、`tools.rs`、`codex-rs/tools/src/tool_search.rs` 核对。
- Claude：资料库 `/反编译/Claude-Code-2.1.317-Linux-x64-完整反编译包.tar.zst`，`chunk-thp9mgjc.js:33998–34088` 可见 ToolSearch 精确选择、待就绪 MCP 刷新与工具选择结果处理。反编译只作为特定代码分支的行为证据；客户端内部代码不复制。
- Claude 官方协议：[Prompt Caching](https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching)、[Tool Search](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)。API 功能与 Claude Code CLI 的内部流程区分核验。
- 777：`app/src/main/java/com/labteto/dshmobile/local/` 的 `context`、`model`、`tools`、`files`、`work`，以及 `harness-core` 的工具注册与审批流程。

## 逐项择优结果

| 场景 | 证实的现有实现与外部设计 | 决策 | 本次生产落点 |
|---|---|---|---|
| 按模型与任务分层指令 | 777 已有 `PromptLayerComposer`、`ContextComposer`，Codex `ModelInstructionsState` 对模型指令状态作快照与增量更新 | 保留 777 分层结构；修复真实注入时稳定指令错误标记 TURN 的根因 | `TurnContextPlacement.kt`，SESSION 与 TURN 分源 |
| Claude 缓存前缀 | 777 有哈希连续性诊断，但 Anthropic 原生请求一直使用普通 system 字符串；Claude 官方协议支持 `cache_control` | 吸收官方原生缓存断点；仅用于官方主机与足够大的稳定前缀 | `AnthropicMessagesClient.kt`；第三方 Anthropic 兼容地址继续原生字符串 |
| 动态工具目录 | 777 `capability_search` 已支持 `select:` 精确多选、实时 ToolRegistry 和下一轮暴露；Claude ToolSearch 支持选择及 MCP 就绪刷新，Codex 使用 deferred schema | 保留 777 的单一 ToolRegistry/授权/注册代次，不复制第二套工具宿主；吸收预算分配优势 | `LocalToolSchemaProjection.kt` |
| 模型工具定义 | Codex `TopLevelToolsState` 支持声明变动的增量更新；777 核心 Schema 常驻、可选 Schema 有 16k 默认与 24k 最大预算 | 先计算核心真实开销，按选中工具需求扩充可选定义，同时受模型窗口硬限约束 | `LocalToolPromptBudgetPolicy.kt`、`LocalToolRouter.kt` |
| 总上下文窗口 | 777 预设与用户设置经 `LocalKimiPolicy.contextWindow` 处理；原预算对小窗口存在强制最低输入容量 | 用户明确收紧容量有效，模型官方预设仍为上界；输入、预留输出、安全余量守恒 | `LocalKimiPolicy.kt`、`LocalContextBudget.kt` |
| 大文件与工具阅读 | 777 已有 `read/glob/grep/read_chunk/archive_read`，行内续读、工具原文持久保存与按需恢复；Claude Read/Grep/Glob 分工 | 保留既有本地存储与恢复路径；统一实际模型可见字节、字符及省略标记的截断预算 | `LocalTextRetention.kt` |
| 长上下文压缩 | 777 `LocalHistoryCompactor`、旧工具结果降级、结构化 Work 状态，Codex `compact` 与世界状态快照 | 沿用单一事件事实源与持久恢复；强制保护原有 system 和最近工具调用完整性 | 沿用 `LocalHistoryCompactor.kt` 与 `LocalToolHistoryProjection.kt` |
| 真实计费用量 | 777 已记录服务商实际 usage 与缓存命中并以重复样本校准软预算 | 保留真实账本；硬限制不依赖少量采样的估算折减 | 沿用 `LocalPromptPressure.kt` 与 `TokenUsageAnalytics` |

## 本次联动修正：任务指令污染缓存前缀

`LocalWorkRuntimeContext.kt` 以前根据当前请求的关键词向整段“稳定运行时规则”追加模型管理、媒体、工具、历史、代码和用量提示，导致每次切换任务就重写稳定前缀。现在由 `workRuntimeContextParts` 明确产出固定会话规则和本轮专项指南两份内容，`LocalWorkTurnPromptContext.kt` 再统一送入 `TurnContextPlacement.kt`：固定规则为 SESSION；任务指南、召回及技能为 TURN。生产请求的注入顺序与模型序列化标签均有回归验证。

Claude Prompt Caching 官方文档列出按型号变化的最低缓存长度（512/1024/2048/4096 Token）。`AnthropicMessagesClient.kt` 依据已知型号门槛与现有模型 Token 估算决定官方缓存断点；不将固定 4096 字节误当全部型号的通用门槛。未知型号使用保守的 4096 Token 门槛；是否真的命中以真实 usage 的 `cache_creation_input_tokens` 与 `cache_read_input_tokens` 判断。

## 明确取消的不合理策略

1. 已知模型设置了更小上下文窗口，却由预设值直接覆盖用户设置。
2. 小窗口下输入的所谓最低预算可以超过减去输出预留与安全余量的物理剩余。
3. 只预算可选工具，忽略每轮常驻核心工具定义的真实开销。
4. Team 模式强行使用 16k 可选工具预算，不检查当轮历史压力。
5. 工具结果省略标记按 UTF-16 字符长度预留，却依赖 UTF-8 字节数计算 Token。
6. 将稳定项目指令和本轮召回信息都标为 TURN，损害跨轮上下文继承与缓存断点。

## 保留原方案的原因

- 777 工具调用审批、冻结 Schema、工作区权限和来源版本验证是独立于提示词的强约束，不能用从 Claude 提取的 ToolSearch 文字指令取代。
- 777 的文件精确分页和 `tool_output_read` 私有全文恢复已经解决长文本截断后无法追溯的问题；没有必要复制第二套文件读取或存储方案。
- Claude CLI 反编译的前端状态机与 Android 运行权限不同；借鉴选择/回执语义，保持 Android 原生流程。

## 验收矩阵

- 模型：已知模型可收紧窗口，已知容量不被意外提高，未知模型仍有运营硬上限；小窗口下输入 + 输出预留 + 安全余量不超过总容量。
- 注入：稳定 Work 指令为 SESSION，本轮记忆/技能为 TURN；Claude 官方 payload 只有稳定断点得到 cache_control，代理路由继续原来的 system 格式。
- 工具：0 到数万 Token 的核心工具开销均进入预算；可选工具自动缩容，按需可增至 24k，超容量不能装入。
- 阅读：中文/emoji 长工具结果在字节/字符/Token 三重限制下不溢出，不把不完整输出当全量；原文仍按引用分段恢复。
- 工程：全套 `app:testDebugUnitTest`、`app:compileDebugAndroidTestKotlin`、`app:assembleDebug`，并检查共享 Harness 与 MCP 关联测试。原生设备和付费 Claude API 缓存命中属于现场验收，须使用真实回执判断。

本报告记录证据、取舍和回归要求；PR 是否完成由最新提交的真实构建与测试结论判定。
