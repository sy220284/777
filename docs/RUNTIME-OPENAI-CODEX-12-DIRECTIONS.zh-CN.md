# Runtime 3.0 × Codex/OpenAI：12 个方向的统一升级闭环

基线：`9b29e58a99ea4ad77b012fa7a0fc0dd240d80058`（2026-10-13）；当前以 `docs/ARCHITECTURE.md` 与 `AGENTS.md` 为权威。不是另建 Agent Engine / Model Gateway / EventLog。**本 PR 只实现第 1 和第 2 方向的一部分，其余项不能以此文档视作已完成。**

## 一、架构实施边界

| 方向 | 统一事实源与归属 | 可核验终态 | 本 PR |
|---|---|---|---|
| 01 工具可用性/错误治理 | ToolRegistry → LocalToolRouter → LocalToolSchemaProjection / LocalToolExecutionCoordinator | 注册、策略、Schema预算与实际可见工具一致；省略有原因；错误可恢复 | **部分代码已实现**：前台/Work/子代理统一复用 ToolSchemaSelection；Work EventLog 记遗漏 |
| 02 上下文保留与子代理继承 | ModelHistory + LocalWorkCheckpoint + LocalSubagentContextPolicy | 约束/撤销/证据保真；超预算明确提示；父权限不继承 | **部分代码已实现**：长上下文首尾保留与截断通知 |
| 03 完成状态/回复质量 | WorkFeature 状态、Todo、Team、验收事实 | 完成与部分完成由结构化事实判定，避免正则改写用户文本 | 后续 |
| 04 模型请求执行链 | LocalModelGateway / RequestCoordinator / adapter | 请求快照、重试、缓存、流式、证据拥有清晰阶段归属 | 后续 |
| 05 Agent Team 收敛 | AgentTeamRuntime + Reducer + RunCoordinator | 成员、任务、消息、承诺一致，单一状态源 | 后续 |
| 06 交互/诊断统一 | Feature API / UI projection / EventLog / Diagnostics | 进度、成果、失败和可恢复性一致 | 后续，避免与 UI PR #664 交叉 |
| 07 执行中纠偏 | InputQueue + run identity + stale-call fence | 运行中明确纠偏，已开始副作用不得假装撤销 | 现有机制保留，后续收敛 |
| 08 工具并行调度 | ResourceScheduler + ToolCoordinator + WorkspaceReadWriteGate | 按真实读写/外部副作用调度，非仅按名称并发 | 现有机制保留，后续优化 |
| 09 统一模型能力目录 | Model Gateway + Adapter Registry + RunModelSurface | 能力、限制、参数、定价/额度来源有版本与状态 | 后续 |
| 10 多智能体工作区隔离 | Workspace capability + resource admission | 可写成员隔离、证据合并，低配 Android 可退化 | 后续 |
| 11 Runtime 重启/环境代次 | Shared Runtime + RunCoordinator + EventLog | 旧环境句柄失效，未确定副作用不自动重放 | 后续 |
| 12 工具原文存储/生命周期诊断 | ToolOutputStore + diagnostics/event evidence | 大输出可分页/可检索、有配额且异常保真 | 后续 |

## 二、贯通原则

1. **一套契约，多方消费**：Chat / Work / Agent Team / subagent 不建立同名能力的影子 registry、ledger、validator、queue、scheduler、memory、runtime。
2. **保留授权边界**：model-visible tool surface 不是执行批准。所有请求仍需 Registry schema validator、ToolExecutionCoordinator 与 real approval；Plan / readonly / network policy 继续生效。
3. **原始证据优先**：输出缺省、预算不足、版本变更、恢复不确定状态不得编造成成功；诊断只投影已存在 EventLog/执行事实。
4. **Android 合理性**：后台、存储、文件写入、耗电与模型 token 预算按 Android 资源和用户配置约束处理。
5. **不搬桌面专属机制**：不照搬 Rust exec server、V8 Code Mode、桌面 TUI 或另一套插件/记忆系统。

## 三、本批验证及未验证

- 新增单元测试：Model Selection 在工具注销/规划模式/网络设置下显式报告原因，且不泄露被拒工具 Schema；子代理长上下文保留最近纠正并检查 UTF-16 surrogate 安全。
- 保留既有 LocalToolRouterTest / LocalToolExecutionCoordinatorTest / LocalSubagentContextTest 为回归目标。
- 连接器环境只支持源码核验与 Git 对象写入，**尚未在本机执行 Gradle 编译/Android 设备测试，必须等待 CI 的实际状态**。
- 合并须验证架构门禁、全量/定向测试、失败/正常/恢复路径，并在合并前重新比对 main/head，不以 PR 创建视为交付。
