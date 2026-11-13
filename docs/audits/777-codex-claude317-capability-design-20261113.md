# 777 × Codex × Claude 317：同功能优劣复核与 Android 交互优化

> 核查日期：2026-11-13。核查方法为代码入口/协议语义/调用与持久记录核验；**源码存在、CI 通过和 Android 实机完成是三个独立等级**。
> 
> Codex 资料库：`/codex/codex-main-2.zip`（ZIP 中央目录损坏，按单文件头与 CRC 恢复有效源码；不把残缺文件当做已核实）；官方同步对照：`openai/codex@c899e21cda29fdd422898ffa54893f67ece34039`（2026-11-13）。
>
> Claude 317 资料库：`/反编译/Claude-Code-2.1.317-Linux-x64-完整反编译包.tar.zst`，包内独立复核记录标注 `2.1.317-linux-x64`、`2,924` 个嵌入条目、`2,691` 个可读 JS 模块，原始可执行文件 SHA-256 `eb224419e344575faa4a1f39b290b649425a10cd5ee2ef1d003480cd9ae57eb0`。反编译的常量/代码路径可证明行为线索，不据此推定所有路径默认开启；不复制专有代码。
>
> 777 基线：`main@02544aa4b8d96fbd0e0aac5d7bf1a3e9f581df6a`，并审查待合并 [PR #895](https://github.com/sy220284/777/pull/895) 的当前文件。开发同时存在其他提交，本报告标记具体文件和代码层级，不把报告发布日期当作功能最终验收日期。

## 一、逐项择优：已有功能也需要深入比

| 能力 | Codex/Claude 参考 | 777 实际代码依据 | 现阶段择优决策 |
|---|---|---|---|
| 代码审查入口 | Codex `app-server/turn_processor.rs` 提供 uncommitted/base-branch/commit/custom，`core/tasks/review.rs` 为独立 Review Task | `LocalIndependentCodeReviewCoordinator.kt` 已实现独立无工具模型调用、事件回执；`LocalWorkspaceFilesDialog.kt` 有远端差异、人工接纳 | **Codex 目标覆盖更好**，**777 移动端证据锁定+人工记录更适合复用**；补本地未提交差异，保留现有报告、验收记录 |
| 审查恢复 | Codex Review 输出归属 session task/event，退出 Review Mode 有特定状态 | 777 审查结果已有持久 EventLog，但最初重新进入弹层不读取它 | 以单一 EventLog 补 `latestForDigest`，通过差异 SHA-256 找回历史报告、人工接纳状态；仅只读恢复，无新模型计费 |
| 代码改动范围 | Codex 支持未提交、基线、指定提交；777 原仅 `git_diff_remote` | `GitRemoteDiff.kt`、`GitWorkspaceRuntime.kt`、`LocalToolsUiFacade.kt` | 新增 `git_diff_uncommitted` 和“检查本地修改”，复用现有只读 git 命令与工作区路径约束；显式说明未跟踪文件未包含 |
| 工具安全前置 | Claude 317 有 Hook 失败策略和授权提醒线索；Codex `tools/sandboxing.rs` 有执行隔离 | `ToolHookRegistry.kt` 快照、`ToolRegistry.kt` 前置 Hook 5 秒上限、异常拒绝、审批后 generation 校验 | **777 现有移动端栅栏更值得保留**；新增 Hook 不可旁路旧审批。后置失败保留真实工具副作用回执 |
| 多智能体工作区 | Claude 317 有团队/协调逻辑；Codex 有代理控制、隔离工作区、团队审核 | `GitWorkspaceRuntime.kt` 真实 worktree 与三方合并，`LocalTeamVerificationController.kt` 审核来源检查 | 保留 777 独立 worktree 与 Lead 验收，接下来补全写权限强约束及跨后端身份/取消/任务图合并的真实设备证明 |
| MCP 扩展 | Codex 有 MCP 动态能力，Claude 317 有协议交互/Hook 扩展 | `McpToolBridgePlugin.kt`、`McpElicitationRoundTrips.kt`、`McpPluginWorkerService.kt` | 777 已有后台 worker 隔离、来源可追溯、多轮追问的分支代码，继续修复 array/enum 输入、旧协议和崩溃恢复；不复制第二套宿主 |
| 技能分发 | Claude Code 强于命令/角色/Hook/技能联合封装 | `LocalSkillManager.kt`、`LocalSkillPackageUpdater.kt`、Tools UI；GitHub 精选目录已接入 | 统一扩展包的签名/来源/依赖/权限/升级/回滚/分享，安装后按实际注册能力显示入口 |
| 长任务恢复 | Codex session rollout/checkpoint、Claude 317 任务继续与错误控制 | `LocalSessionEventLog.kt`、`LocalPersistentJobRecoveryCoordinator.kt`、`LocalWorkIntegrationDecision.kt` | **777 单一事实源和未知副作用门禁是现有优势**；继续检验 SIGKILL、重启后审批代次、未完成动作、目标完成证据 |
| 多模型路由 | Codex 按模型能力/安全配置；Claude 317 的账号授权带提示 | `LocalModelGateway`、`LocalModelCredentialResolver`、`list_subagent_models` | 保留模型路由+凭据独立，补统一账户能力、官方额度和费率、配置禁用原因；单次用户授权后低频配置自动隐藏 |
| 多模态工作流 | CLI 的长链路偏脚本与工具选择；777 已有媒体创作、持久任务、结果卡片 | `LocalMediaCreationRuntime.kt`、`LocalMediaCatalog.kt`、MiniMax native client | **777 原生媒体运行时更符合 Android**；拓展高阶参考素材、音乐、实时语音时沿用同一任务/结果/付费账本 |
| 权限及诊断 | Codex/Claude 有终端自诊和运行流程提示 | `LocalToolPolicy.kt`、`LocalRuntimeProjection.kt`、诊断设置界面 | 后台检测、首次按需解释/申请、失败显示“已授权/已前往设置/授权无效”，工具状态由真实 Provider 回执驱动 |
| UI 信息架构 | Codex CLI 的命令适合桌面；Claude Code 的状态反馈值得转译 | 当前 777 `LocalWorkUiContribution`、`LocalWorkspaceFilesDialog`、`ToolsScreen`、`SettingsScreen` | 保持 Android Compose 原生、最多三级导航；面向任务展示操作、审批、结果，不向一般用户暴露内部工具表单 |

## 二、本次实际接线（待以最新 HEAD CI 确认）

1. 在现有审查持久记录加 `latestForDigest(ref,digest)` 和只读 `findForDiff`；重开同一 Git 差异自动显示已保存报告和“已人工接纳”，旧版本不误接纳新报告。
2. 工作区新增“检查本地修改”，可在没有 remote 分支的 Git 仓库中读取已跟踪文件的**暂存+未暂存**差异；保留“对比远端分支”。
3. 新工具 `git_diff_uncommitted` 沿用 `GitRemoteDiff` 的工作区 canonical 边界、禁止任意 shell、`--no-ext-diff --no-textconv`、128KiB 差异上限，且只读不执行 fetch/add/commit。
4. 对本地审查的“人工接纳”也从当前工作区重取差异并核对摘要；HEAD 或内容变化后原审查失效。
5. JVM 回归覆盖：报告跨事件日志重建、已接纳状态、同一差异的最新审查优先、另一个 ref 无法复用、无远端分支的本地 diff、未跟踪内容不泄露、不修改暂存区。

**当前代码边界：**本地 diff 审查仅覆盖 Git 已跟踪文件，未跟踪新增文件和二进制变更仍需专门审查路径。读到差异并不能证明构建/测试成功；审查报告只表明模型观点，“人工接纳”只证明用户确认看过结果，二者都不越权放行 Goal。

## 三、具体 Android UI 与交互规则（UI 2.4 × 777 现有 Sy 组件）

| 一级入口 | 二级界面 | 三级弹层或卡片 | 用户主要操作 | 真实状态与错误 |
|---|---|---|---|---|
| Work | 工作区/文件 | Git 差异与审查 Sheet | 检查本地修改、对比远端、独立审查、人工接纳 | 加载/无差异/截断/历史报告/待确认/已接纳/差异已变化 |
| Work | 当前任务 | 执行时间线/审核卡 | 查看步骤、审批、继续/取消、成果与测试回执 | 已请求/运行中/等待用户/未知副作用/可恢复/已完成 |
| Work | 项目（待实现） | 项目详情 | 创建导入、关联会话/工作区、项目策略 | 来源版本/上次活动/未归属文件/迁移冲突 |
| 工具 | 技能/插件 | 能力详情 | 搜索、安装、授权、升级、恢复、分享 | 已安装/已注册/已连接/权限就绪/调用通过，五态分离 |
| 设置 | 模型与额度 | 模型详情 | 选模型、测试官方能力、查看费率及周期额度 | 已配置/已登录/有 API 权限/实际调用成功/额度时间戳 |
| 设置 | 设备权限/诊断 | 单项修复弹层 | 开启系统权限、回到 App 自动复核、导出脱敏报告 | 明确“已打开系统设置”和“实际授权生效”的差别 |

交互准则：

- 一级/二级/三级最多三层；低频诊断与后台配置归设置，日常动作归当前任务。
- 默认展示**选择对象 → 执行动作 → 结果**，高级参数需明确展开；不让用户先填进程、路径和工具 Schema。
- 所有异步请求显示确切任务归属与取消效果；切换会话或回退时防止旧请求覆盖新页面。
- 审查 UI 不会仅凭模型结论启用“测试通过”“提交成功”的状态。空结果与错误直接说明原因及下一步。
- 320/360/393/412 dp、横屏、键盘、TalkBack、大字号、浅/暗色及实际点击交互都属于正式验收，不以静态 Compose 源码代替。

## 四、仍须补齐的完整能力与验收

本报告专注“发现既有功能仍做优劣比较”。按原 PR #895 的 29 项台账继续开展，不能用一次审查改动替代所有项目：

- **P0：工程闭环**：项目完整生命周期、未跟踪文件/任意提交/自定义审查目标、真实测试证据、提交前验证、修改快照与回滚，Goal 验证与人工放行。
- **P0：可靠执行**：Team/TaskGraph 跨 backend 统一生命周期，任务取消与恢复、工作区强隔离、可信远端环境状态、设备冷启动与 SIGKILL 故障注入。
- **P1：统一扩展**：技能+角色+工具+Hook+MCP 的依赖/权限/来源/升级/恢复协议，插件宿主过程隔离与崩溃恢复，确保 UI 选择与真实注册一致。
- **P1：模型与配置**：多供应商独立凭据目录、真实额度/费率/有效时间、多层策略合并与锁定原因，避免因模型切换产生旧授权残留。
- **P1：安全与审计**：独立审核、执行后回执、统一网络代理与凭据保护、跨 MCP/HTTP/本地进程的相同权限规则。
- **P2：多模态**：实时双向语音，复杂视频参考输入、音乐生成、专业字幕/转写结果，继续复用现有媒体 Job 与成果档案。
- **最终验收**：逐条生产入口及 UI 操作成功、Android 16/17 模拟器/实机、完整 CI、异常恢复；缺乏真实账户的联网能力保留“未实测”，不等同已全部完成。

资料来源：`openai/codex` 最新源码；资料库 Codex ZIP、Claude 317 提取包（行为归纳不复制代码）；777 main 与 PR #895 的真实生产文件。此文是本次源码依据、设计收敛与验收清单，不是所有后续能力已经交付的声明。
