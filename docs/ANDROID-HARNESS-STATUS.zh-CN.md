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

```text
Compose UI
→ presentation projection
→ Chat / Work / Session / Model / Tools / Automation runtimes
→ Feature API / Shared Capability
→ LocalRuntimeKernel（仅进程启动、恢复与维护）
→ coordinators / stores / repositories
→ harness-core
→ Android runtime / MCP / LSP / device providers
```

`harness-core` 负责平台无关 Agent 语义。

Android 模块负责生命周期、进程、设备、UI 和平台能力。

## Agent

当前已具备：

- 多 step AgentLoop。
- reasoning / assistant / tool call / tool result 完整模型回合。
- 有界模型请求重试。
- 主代理与子代理统一核心循环。
- 普通子代理、fork 子代理、持久子代理。
- 并行 / 流水线工作流。
- Goal / Todo / Plan。
- 用户提问与审批。
- 取消、停止和受控结束。
- Agent inbox。
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

Session Event Log 是主要事实源。

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

Engine 不直接持有 PluginRegistry。

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
