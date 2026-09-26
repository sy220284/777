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
- 官方 Harness 差分验证模块已进入主线，当前锁定 `0.1.7-rc.2 / 477b4f420...`，日常 CI 不自动同步上游。
- 用户可见 transcript 已采用独立 sequence 游标：user / assistant / reasoning / progress / tool / system 消息随 canonical Session Event 持久化，快照只承担物化加速；崩溃后按事件尾增量恢复并按稳定消息 id 去重。
- 长对话压缩已拆为独立的 `LocalHistoryCompactor`：字符预算保护 Android 资源，官方 DeepSeek 路由额外按模型窗口估算 token 压力；主 Agent 每个模型 step 都会重新检查，较早历史生成有界提取式摘要。提供方明确返回上下文超限时，会保留 system 指令、强制缩短较早历史并仅重试一次；普通请求错误不进入该恢复路径。
- 工具结果使用 Unicode/UTF-8 安全头尾保留；超限完整结果可进入 Session 私有 spill，并通过 `tool_output_read` 分页恢复，不再以“节省上下文”为代价永久丢失可恢复内容。
- 普通、持久和自动化子 Agent 的可选工具启用集合已经按单次 Agent Run 隔离；子 Agent 的能力发现不会修改父回合工具视图。
- 自动化任务保存 30 天 / 200 条轻量运行回执，完整结果继续复用原工作会话，避免重复持久化。
- 核心单测、官方差分门禁、Android 安装包构建与模拟器运行由 CI 负责验证。

## 当前边界

- `SessionEventLog` 已承担主要会话事实源职责：Plan / Todo / Goal / 规划模式、模型历史和用户可见 transcript 均可从事件检查点/语义尾重建；会话 JSON 主要保留物化快照、索引元数据和旧版本兼容字段。
- `AgentLoop` 已拥有 turn / step 调度权；Android 层 `modelHistory` 仍负责组装当前请求，但已降级为可从 Session 事件检查点和语义尾部重建的运行时投影，不再作为会话 JSON 中的第二份持久权威状态。
- 内置工具已经通过核心 `ToolRegistry` 注册，但 `LocalHarnessEngine` 仍承担较大的 Android 内置工具分派、上下文组装和会话桥接职责。
- 主前台 Agent 的可选工具集合仍由 `LocalHarnessEngine` 持有，但严格在每个前台回合开始清空；子 Agent 与后台自动化已使用独立 Agent Run 集合。后续统一 `AgentRunContext` 时再把主回合集合一并下沉，当前不存在父子共享集合。
- 工具执行失败在部分 Android 适配链路中仍主要以文本返回给模型；后续应保留结构化错误、错误码和可重试信息。
- 历史压缩已从简单截尾升级为有界提取式摘要，但尚未形成显式的“目标 / 约束 / 关键决定 / 失败尝试 / 未完成事项”结构化检查点。
- 后台 Job 控制器已核心化，但正在运行的进程/代理本身仍受 Android 进程生命周期限制，尚不能在进程被杀后透明续跑。
- 官方 Harness 继续只作为锁定的语义、协议和测试参考，不作为 APK 运行时依赖。

## M1 后续

1. 继续收口剩余会话元数据与索引投影，明确哪些字段属于事件事实、哪些仅是可丢弃的物化缓存；控制状态、模型历史与用户可见 transcript 已完成事件投影收口。
2. 引入统一 `AgentRunContext`，把会话、上下文快照、工具视图、权限范围、模型路由和取消状态交给核心循环；逐步取消 Android 层平行 `modelHistory` 权威状态。
3. 在统一 `AgentRunContext` 时把主前台回合剩余的工具视图、权限与审批状态一并下沉；子代理和后台分支的工具启用集合隔离已完成。
4. 将工具执行结果升级为结构化结果，统一成功/失败、错误码、是否可重试、内容与审计元数据。
5. 将历史裁剪升级为结构化语义压缩检查点，保留长期任务的关键决定与失败经验。
6. 把官方差分测试从裸 `AgentLoop` 扩展到 Android 完整运行链，覆盖上下文注入、审批、取消后继续、Session 恢复、子代理继承和插件工具视图。

每一步独立提交，并保持主线可构建、可安装、可回滚。
