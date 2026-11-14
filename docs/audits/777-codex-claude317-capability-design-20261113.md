# 777 × Codex × Claude 317：同功能优劣复核与 Android 交互优化

> 核查日期：2026-11-13；重点核对代码入口、协议、运行时状态及持久化路径。
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
| 多智能体工作区 | Claude 317 有团队/协调逻辑；Codex 有代理控制、隔离工作区、团队审核 | `GitWorkspaceRuntime.kt` 真实 worktree 与三方合并，`LocalTeamVerificationController.kt` 审核来源检查 | — |
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

- **工程实现方向**：项目完整生命周期、审查目标、提交前检查、修改快照与回滚，以及人工确认入口。
- **P0：可靠执行**：Team/TaskGraph 跨 backend 统一生命周期，任务取消与恢复、工作区强隔离、可信远端环境状态、设备冷启动与 SIGKILL 故障注入。
- **P1：统一扩展**：技能+角色+工具+Hook+MCP 的依赖/权限/来源/升级/恢复协议，插件宿主过程隔离与崩溃恢复，确保 UI 选择与真实注册一致。
- **P1：模型与配置**：多供应商独立凭据目录、真实额度/费率/有效时间、多层策略合并与锁定原因，避免因模型切换产生旧授权残留。
- **P1：安全与审计**：独立审核、执行后回执、统一网络代理与凭据保护、跨 MCP/HTTP/本地进程的相同权限规则。
- **P2：多模态**：实时双向语音，复杂视频参考输入、音乐生成、专业字幕/转写结果，继续复用现有媒体 Job 与成果档案。

资料来源：`openai/codex`、Codex ZIP、Claude 317 提取包及 777 对应源码；本文件整理能力设计与现状。


## 2026-11-13 追加：五套编程/多模态 CLI 能力与原生 UI 复核

本轮还对照了用户提出的 CLI 体系：Codex CLI、Claude Code CLI、Gemini CLI、OpenCode CLI、MiniMax CLI。相应上游可执行文件均不能因为 777 内置 Node/Python/Git 就视作已安装。这里把 CLI 产品入口和内置 Agent 引擎分开验收，不为每套 CLI 复制会话、权限或工具系统。

| 功能体系 | Codex / Claude 317 与其他 CLI 借鉴点 | 777 现有实现更优之处 | 777 下一步实际验收点 | UI 归属 |
|---|---|---|---|---|
| 运行入口 | 独立 shell/headless CLI、结构化输入输出和退出码 | 777 原生 Android 任务可在 UI 查看会话、恢复及设备权限 | 独立 777 命令行、标准化 JSON/JSONL 结果，五套外部 CLI 逐一核 ABI/运行时/授权，失败有确切回执 | 工作区入口与工具详情 |
| 项目与审查 | Codex 对未提交、分支、指定提交、首次提交、合并提交目标更完整 | 777 已有人工接纳版本栅栏、审查持久 EventLog 与原生差异 sheet | 本轮新增 git_diff_commit+真实工作区按钮，覆盖 root commit、普通/合并提交、无远端场景 | 工作 → 工作区 → 代码审查 |
| 权限安全 | Codex 沙箱、Claude Hooks、外部 CLI 授权模式 | 777 现有工具审批、插件注册代次核验、未知副作用门禁适合 Android | 同一安全策略覆盖 Git/Shell/MCP/外部进程；重启/取消后的授权失效及真机测试 | 工作中的审批卡；高级设置显示原因 |
| 扩展与生态 | Claude 命令+技能+Hook 配置；Codex 市场；Gemini 扩展；OpenCode 插件 | 777 已有原生 MCP、Skill、ToolRegistry 和独立插件管理链 | 安装→来源验真→注册→真实调用→升级/回滚→崩溃恢复，逐一保留回执 | 工具 → 技能/插件 → 详情 |
| 模型与费用 | Codex 模型能力目录、Claude 账户授权、OpenCode 多供应商 | 777 可独立路由模型并通过原生设备账号展示 | 每供应商独立连接/能力检测、真实额度费率+时间、拒绝原因 | 设置 → 模型 |
| 多模态与设备 | MiniMax CLI 服务工具，Claude 语音/终端交互 | 777 原生 Android 无障碍、通知、虚拟屏、媒体任务具备更自然的本机入口 | 操作成功回执；按需授权与修复；多模态任务对账、取消/恢复 | 工作及媒体当前任务 |
| 多 Agent 与长任务 | Codex 子进程/工作区分离，Claude Team、长任务反馈 | 777 持久任务板、恢复水位、单一事件记录 | 每个独立写目录的强边界、跨进程权限/撤销、完整任务完成证据 | 当前任务状态与结果，后台管理隔离 |

**专项生产改动（PR #895）**：
- 在 `GitRemoteDiff` 注册可发现、只读的 `git_diff_commit`，输入仓库相对目录和十六进制提交哈希，转换为固定的 Git 对象 ID，首提使用空树、合并提交用第一父提交作为差异基线。
- 保留 `repository` 工作区规范化边界、Git 参数数组调用、不联网、128 KiB 限制、完整性回执，拒绝选项注入；没有固定对象的旧审查报告不允许借用。
- 通过 `GitWorkspaceRuntime → LocalToolCompositionRoot → LocalToolsRuntime → LocalToolsUiFacade → LocalHarnessViewModel → LocalWorkspaceFilesDialog` 接入 Android 工作页面；同一 sheet 上切换本地/远端/指定提交目标，无需进入开发配置表单。
- `LocalIndependentCodeReviewUiFacade.currentDiff` 支持 `commit/<SHA>`，人工接纳前重取真实提交差异并核对审查摘要，复用独立安全审核和 EventLog。
- 增加 `GitRemoteDiffTest.reviewImmutableCommitSupportsRootAndDoesNotReadWorkingTree`，测试根提交、普通提交、缩短 SHA 解析、未提交/未跟踪文件保密、Git index 未变、无网络以及拒绝非法提交字符串。

**交互设计验证**：
- Android UI 2.4 原则：首屏显示当前项目和下一项关键操作，进入代码审查后二级选择目标、三级差异预览/审查接纳；需要身份/敏感权限时就地出现解释及系统授权入口。减少一进页面大量字段。
- 可交互演示 HTML 和两张截图由本轮制作，独立验证了三主入口、指定提交输入、差异预览及审查操作；所有模拟状态均显式标注“演示”。
- Figma 新文件：https://www.figma.com/design/58aL1rx9pPm7ykjix96XPa 。文件成功创建；Starter 账户的 MCP 限额阻断后续画布写入。
- 用户可操作的生产 Android 界面已写入 PR；不把交互原型当作 APK 实测。

**证据与边界**：Codex 来源 `/codex/codex-main-2.zip` 与可核验快照 `codex_verified_local_snapshot_2026-11-13.zip`；Claude 317 来源 `/反编译/Claude-Code-2.1.317-Linux-x64-完整反编译包.tar.zst`，包中独立核验记录版本与文件完整性已确认。借鉴协议与交互设计，不复制 Claude 反编译的专有代码。后续能力优先在原所有权链中增强，而非增加重复的 Agent 内核或重复 UI 配置页。
