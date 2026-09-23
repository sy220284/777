# Android 原生 Harness M1 实施状态

## 已完成

- 新增独立纯 Kotlin 模块 `:harness-core`，不依赖 Android、Compose、Activity 或 Service。
- 将追加式会话事件日志迁入核心模块，Android 侧保留薄适配层，已有会话文件名保持不变。
- 将后台任务控制器迁入核心模块，收拢任务编号、状态、输出和子代理收件箱职责。
- 建立平台无关的 `AgentLoop`，主代理与子代理均复用同一 turn / step 生命周期。
- 核心循环现已记录 `turn/start`、`step/start`、assistant、tool call/result、`step/end` 与 turn 结束状态。
- 工具调用保留模型原始参数字符串；Android 适配器可提供批量执行器，并行子代理保持输入顺序且失败隔离。
- 步数安全上限改为受控结束状态，不再当作普通异常处理。
- `ToolRegistry`、`PluginRegistry` 与 `CapabilityRegistry` 已进入主运行链；Android runtime、MCP、LSP、device、vision、automation、webhook 均通过插件安装。
- 会话格式已使用版本化存储、原子写入、备份恢复和未来版本拒写；事件日志支持分段保留与进程中断恢复。
- 模型请求重试已抽为共享的 `AgentRequestExecutor`，父代理和子代理统一取消与重试语义。
- 用户主动停止或运行时失败时，Android 运行层会立即结算当前 assistant 已声明但尚未完成的工具调用：
  - 已进入工具执行阶段但没有可靠结果 → `TOOL_OUTCOME_UNKNOWN`；
  - 尚未进入执行阶段 → `TOOL_NOT_STARTED`。
  这样同一进程内直接继续对话时，也不会把悬空 tool call 带入下一轮模型请求。
- 普通子代理和 fork 子代理会继承有界的父级约束上下文，包括用户规则、当前项目相关记忆和会话交接摘要；任务历史仍按子代理模式隔离。
- 官方 Harness 差分验证模块已进入主线，锁定 `0.1.7-alpha.2 / 00102833...`，日常 CI 不自动同步上游。
- 核心单测、官方差分门禁、Android 安装包构建与模拟器运行由 CI 负责验证。

## 当前边界

- `SessionEventLog` 已承担事实日志职责，但 `modelHistory`、`LocalHarnessSession` 快照与 `LocalHarnessState` 仍同时保存部分权威状态；Session 还没有完全收敛为“事件唯一事实源 + 投影”。
- `AgentLoop` 已拥有 turn / step 调度权，但真正发送给模型的主会话历史仍由 Android 层 `modelHistory` 组装；核心与适配层仍存在双状态来源。
- 内置工具已经通过核心 `ToolRegistry` 注册，但 `LocalHarnessEngine` 仍承担较大的 Android 内置工具分派、上下文组装和会话桥接职责。
- 可选工具启用集合目前仍属于 Engine 级运行状态，后续需要下沉为单次 Agent Run / 子代理独立作用域。
- 工具执行失败在部分 Android 适配链路中仍主要以文本返回给模型；后续应保留结构化错误、错误码和可重试信息。
- 历史压缩当前以保留尾部为主，尚未升级为包含目标、约束、关键决定、失败尝试和未完成事项的结构化语义检查点。
- 后台 Job 控制器已核心化，但正在运行的进程/代理本身仍受 Android 进程生命周期限制，尚不能在进程被杀后透明续跑。
- 官方 Harness 继续只作为锁定的语义、协议和测试参考，不作为 APK 运行时依赖。

## M1 后续

1. 将 Session Event 收敛为唯一事实源；快照只作为带 sequence 的加速检查点，启动后回放增量事件恢复模型、UI、Goal、Todo、Plan 等投影。
2. 引入统一 `AgentRunContext`，把会话、上下文快照、工具视图、权限范围、模型路由和取消状态交给核心循环；逐步取消 Android 层平行 `modelHistory` 权威状态。
3. 将可选工具可见性、权限与审批作用域下沉到单个 Agent Run，彻底隔离父代理、并行子代理和工作流分支。
4. 将工具执行结果升级为结构化结果，统一成功/失败、错误码、是否可重试、内容与审计元数据。
5. 将历史裁剪升级为结构化语义压缩检查点，保留长期任务的关键决定与失败经验。
6. 把官方差分测试从裸 `AgentLoop` 扩展到 Android 完整运行链，覆盖上下文注入、审批、取消后继续、Session 恢复、子代理继承和插件工具视图。

每一步独立提交，并保持主线可构建、可安装、可回滚。
