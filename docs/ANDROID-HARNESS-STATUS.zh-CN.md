# Android 原生 Harness 当前状态

本文只描述当前主线实现。

## 基线

```text
777: 正式版本随 GitHub Release 标签自动推进；本地构建 fallback 见 .github/release-version
Android: 16+
minSdk: 36
targetSdk: 36
compileSdk: 37

官方本机语义参考:
deepseek-ai/deepseek-harness 0.1.7-rc.2
477b4f420553e8a52c2fbccc464d7561b239c443
Session format reference: V4
```

777 本机模式已经是 Android 原生 Harness，不在 APK 内运行一套官方 Node Harness 作为主引擎。

Node / Python / Git 是 Agent 可调用的工具运行环境。

## 总体结构

业务调用：

```text
Compose UI
→ presentation / Feature API / projection
→ Chat / Work / Automation / Tools Feature
→ Shared Capability / Shared Runtime（按需）
→ stores / registries / policies / gateways
→ harness-core
→ Android runtime / MCP / LSP / device providers
```

进程启动：

```text
DshApplication
→ LocalRuntimeKernel
→ LocalRuntimeBootstrapPort
→ 应用组合根
→ Shared Runtime / Feature 初始化与恢复
```

`harness-core` 负责平台无关 Agent 语义。

Android 模块负责生命周期、进程、设备、UI 和平台能力；`LocalRuntimeKernel` 只负责进程 start-once、生命周期 scope、bootstrap / recovery 触发和初始化失败投影。

## Agent

当前已具备：

- 多 step AgentLoop。
- reasoning / assistant / tool call / tool result 完整模型回合。
- 有界模型请求重试。
- 主代理与子代理统一核心循环。
- 普通子代理、fork 子代理、持久子代理；持久只读子代理具备稳定 Agent 身份、持久 Inbox、历史 Checkpoint 与冷恢复续跑。
- 并行 / 流水线工作流。
- Goal / Todo / Plan。
- 用户提问与审批。
- 取消、停止和受控结束。
- Agent inbox；前台与持久子代理统一使用 `QueuedAgentInput` 语义，满载明确拒绝，不静默丢弃旧消息。
- Agent run checkpoint。

运行归属：

```text
sessionId
→ runId
→ parentRunId
→ agentId
→ step
→ tool call
```

前台、子代理和 Automation 共享同一 run checkpoint 语义。

## Session 与恢复

Session Event Log 是主要事实源。模型历史缓冲区与 Checkpoint 是 EventLog 的有界物化与恢复加速层；新写入的模型历史 Checkpoint 带 `as_of_sequence` 水位，恢复按事件序号重放尾部，旧 V1 Checkpoint 继续可读并在后续安全点重写。

模型请求同时记录可重建证据：

- `request/header` 持有 `request_uid`、路由身份、原始请求不可逆摘要以及对应 Surface 事件序号；
- V2 新增 `request/message-surface`：文本请求可完整重建；多模态请求只持久化脱敏后的模型可见消息，图片原始数据不复制进 EventLog；
- `request/tool-surface` 只在工具 Schema Surface 变化时记录完整 Schema；
- `request/context-surface` 只在 system / developer 模型可见上下文变化时记录脱敏后的上下文切片；
- `reconstructRequest(sessionId, requestUid)` 按 Surface 序号重建消息、工具和 Context，重新计算 digest / envelope fingerprint；V1 标记为 evidence-only，V2 区分 exact verified、redacted verified 与 invalid；
- retry / error / cancelled / completed / `assistant/attempt` 使用同一个 `request_uid` 关联，失败尝试不会伪造正式 assistant 历史；
- `tool/execution-started` 在真实执行 admission 后记录独立 `execution_id` 与 `root_call_id`，与模型声明的 `tool/call` 分离。

持久内容包括：

- 用户消息。
- assistant 消息。
- reasoning。
- tool call / result。
- turn / step 生命周期。
- Goal / Todo / Plan。
- 请求上下文检查点。
- 用户可见 transcript。
- 运行恢复信息。

快照是有界物化，不是第二份完整事实源。

恢复原则：

- 已开始但结果未知的工具：`TOOL_OUTCOME_UNKNOWN`。
- 尚未执行的工具：`TOOL_NOT_STARTED`。
- 未知副作用不会在重启后自动重放。
- durable Agent inbox 在启动 / 会话切换时恢复。
- 持久只读子代理消息先落盘再确认；进程中断后从最近完整 Child History Checkpoint、全局 Step 水位与动态软预算继续，已进入检查点但尚未确认的 Inbox 消息不会重复注入。
- 只有显式 `continuable=true` 的持久子代理在一次 Activation 结束后进入 `dormant`；无新消息时不占运行槽也不自动唤醒，收到新消息后重新激活同一 Child Agent 身份；旧版 `completed + continuable` 快照读取时兼容归一为 `dormant`。
- `interrupted` 的可恢复任务与 `dormant` 的 continuable 子代理不会被普通历史裁剪静默删除；保留上限被持久对象占满时显式拒绝新任务，需先终止不再使用的持久代理。
- 最终 assistant 已持久化但 Job 终态尚未提交时，恢复直接按终态 Checkpoint 结算；已认领但残留在 Inbox 的消息只做确认，不触发重复模型请求。
- Session Projection 使用共享注册语义；投影拥有稳定名称、`stateVersion` 和 `asOfSequence`，Feature 持有自己的强类型句柄。
- 外部进程被 Android 杀死后不能伪装成透明续跑。

## 上下文与长对话

Work 压缩已经额外生成 typed `LocalWorkCheckpoint`，结构字段包括：

- 当前目标。
- 关键约束。
- 已确认决定。
- 已失败尝试。
- 未完成事项。
- 阶段进展。
- 重要产物。
- 已涉及工具。

该检查点随压缩后的模型历史持久化；安全进程恢复会从最近 `ModelHistoryCheckpoint` 读取它并注入续跑提示，减少多次压缩 / 进程重启后的任务漂移。

当前使用双层治理：

1. Android 资源 / 字符预算。
2. 已知模型的 token 上下文预算。

主 Agent 每个模型 step 都会重新检查上下文压力。

已实现：

- 有界提取式历史摘要。
- 压缩事件记录压缩前 EventLog 水位、来源 Checkpoint、压缩前后消息量和摘要指纹；后续 Checkpoint 记录压缩提交后的事件水位。
- context overflow 专用恢复。
- system 指令保留。
- Unicode / UTF-8 安全裁剪。
- 超长工具结果 Session 私有 spill。
- `tool_output_read` 分页恢复。
- transcript 分页与有界运行窗口。

## 工具系统

Tool / Plugin / Capability Registry 已进入主运行链。

内置能力包括：

- 文件读写 / 编辑。
- glob / grep。
- shell / managed process。
- 持久管道终端。
- Node / Python / Git。
- Web Search / Fetch / HTTP。
- MCP HTTP / stdio。
- LSP。
- Skill。
- Goal / Todo / Plan。
- Subagent / Workflow。
- Session query。
- Android device。
- Vision / VirtualDisplay。
- Automation / Webhook。

平台能力由 `LocalPluginCompositionFactory` 组合。

PluginRegistry / PluginManager 由运行时插件体系持有，产品 Feature 通过 Tool / Plugin 共享契约消费，不由 `LocalRuntimeKernel` 或 UI 持有。

## MCP 与 LSP

### MCP

支持：

- HTTP。
- stdio。
- 动态工具注册。
- 高权限工具审批。
- disconnect 时停止新调用、等待 in-flight、注销工具后关闭 transport。

### LSP

支持：

- initialize。
- definition。
- references。
- hover。
- implementation。
- document / workspace symbols。
- diagnostics。
- rename 预览。

语言服务器是可选能力。不存在合适 server 时，Agent 继续使用 read / grep / build / test。

## Android 设备能力

当前 provider 包括：

- Accessibility。
- Notification。
- VirtualDisplay。
- Android screenshot / UI action。
- 设备相关工具。

子代理虚拟屏有独立资源归属，不能操作别的 Agent 的虚拟屏。

## Web 安全

Web fetch / request 当前包含：

- 公网目标校验。
- DNS 固定。
- 重定向重新校验。
- 私网 / loopback / link-local / multicast / reserved 地址拒绝。
- 请求 / 响应体上限。
- 超时。
- 安全方法有限重试。
- 状态修改 HTTP 方法不自动跟随重定向。

## Chat

聊天模式运行在同一 Agent 内核上。

当前能力：

- 人物主档案。
- Gallery / Persona。
- 多故事线。
- 单聊 / 群聊。
- 人物关系状态。
- Scene 连续性。
- 共同经历。
- 长期关系记忆。
- 人物变化。
- 回复建议。
- 重生成 / 回复版本 / 修改重发。
- 定时互动。

### 记忆隔离

人物关系记忆使用稳定 subject key。

优先级：

```text
Gallery identity
→ Persona identity
→ explicit fallback
```

复制角色不会默认共享关系记忆。

### 人物行为调节

`CharacterBehaviorTuning` 当前控制：

- 亲密距离。
- 状态延续。
- 主动性。
- 表达开放度。
- 人物变化。
- 情绪余韵。
- 互动新鲜度。
- 原设遵循。
- 关系节奏。
- 关系阶段锁定。

这些参数只改变表现和变化速度。

它们不能直接改写：

- 信任事实。
- 共同经历。
- 历史关系。
- 已发生事件。

## Work

工作模式当前支持：

- 计划模式。
- 审批。
- Ask User。
- 主 / 子代理。
- 后台任务。
- 自动任务。
- MCP / LSP。
- Web / Vision。
- Android device。
- 运行中心。
- 任务恢复。

Chat / Work 共用底层 Agent / Tools / Permissions / Context。

差异只存在于产品 prompt、状态投影和 UI。

## 发送链

`LocalSendCoordinator` 统一本机发送策略。

结果只有：

```text
STARTED
QUEUED
REJECTED
```

明确区分：

- 正在 loading。
- Session transition。
- 队列已满。
- 配置缺失。
- 正常入队。

被拒绝的发送不会把用户输入静默清空。

## Token 可观测

请求级账本记录：

- requestId。
- sessionId。
- turnId。
- runId。
- parentRunId。
- agentId。
- action。
- model。
- input / output。
- cache hit / miss。
- reasoning usage。
- 时间。
- 估算费用。

支持：

- Chat / Work 分开统计。
- 7 / 30 / 90 天。
- 会话维度。
- 任务维度。
- 主代理 / 子代理。
- action 明细。
- 请求日志。

本地 Token 总量只采用成功模型响应实际返回的 API usage。失败、取消、断流或未返回 usage 的请求只保留诊断信息，不由客户端推断供应商最终计费。

提示词、人物状态、记忆、历史、工具定义只做输入构成分析，不重复加入总 Token。

## 性能边界

当前关键约束：

- Session 历史分页。
- transcript 有界窗口。
- streaming preview 独立 StateFlow。
- aggregate state 不随每个 token 重建。
- model history 使用专用 buffer。
- 日志 / spill / automation receipt / Token ledger 有上限。
- 热点文件执行架构 ratchet。
- 热路径执行性能门禁。

## Android 平台

当前支持：

- Android 16。
- Android 17 CI。
- arm64-v8a 默认发行。
- x86_64 模拟器构建。

构建同时校验：

- 16 KB page size。
- ELF LOAD alignment。
- APK 内 native library alignment。
- optimized APK 安装 / 启动。

## 官方差分验证

验证分为两层：

- `ReferenceConformanceTest` 只消费锁定上游生成的 official golden，不把本地预期伪装成官方结果。
- `NativeRuntimeBehaviorContractTest` 覆盖 Android / 本机专属的恢复、审批和生命周期行为。

本机 Harness 语义参考由：

```text
upstream/deepseek-harness.lock.json
```

锁定。

日常 CI 不自动追上游最新 commit。

参考版本更新必须经过：

```text
更新 lock
→ 刷新官方 fixture
→ reference-validation
→ 差异审计
→ Android 回归
```

## 当前平台边界

当前不会伪装提供桌面等价能力：

- 任意第三方 Node 动态插件 / 热重载。
- PowerShell / Windows 工具。
- 完整桌面 Chromium / Stagehand。
- Office 桌面转换运行时。
- Android 无法保证的秒级长期调度。
- 应用进程被杀后任意外部进程透明续跑。

这些能力必须显式不支持或提供 Android 等价实现。

## 当前质量门禁

当前主线要求同时通过：

- 架构门禁。
- 性能门禁。
- UI 门禁。
- Kotlin 风险门禁。
- 单元测试。
- 官方 Harness 一致性。
- Lint。
- optimized APK。
- Android 16。
- Android 17。
- merge-gate。

详见 [VALIDATION.md](VALIDATION.md)。
