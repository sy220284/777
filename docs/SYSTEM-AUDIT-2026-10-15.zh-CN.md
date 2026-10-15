# 2026-10-15 全仓系统链路与 UI 接入审计

> 后续代码修复与验证见 [审计修复与实现升级](SYSTEM-AUDIT-REMEDIATION-2026-10-15.zh-CN.md)。本报告与机器证据保留原审计时点。

本轮结论：主要产品入口和共享执行底座已经实现；不能据此认定所有功能已完善。最明显的公共短板是**持久事实和运行能力没有完整转化成用户可查询、可纠正、可核验的操作**，集中在工作证据和长期记忆管理。

## 1. 基准、范围与证据边界

- 审计代码：`3fef6399eeeab6161a4d5c5d7aef65ec5228f530`，本地 `work` 分支。开始核查时通过 GitHub REST 确认远端 `main` 为同一 SHA。本地 `origin/main` 仍为 `fd7ca58c`，**未用该过期引用代表最新主线**。
- 原工作区干净；本轮只增加审计报告及证据索引，没有修改产品代码，也没有创建、关闭或合并 PR。
- 规范：`AGENTS.md`、`SYSTEM-AUDIT-GUIDE.zh-CN.md`、`ARCHITECTURE.md`、`SECURITY.md`、`VALIDATION.md`、`DEVELOPMENT.md`、`PROTOCOL.md`、`COMPATIBILITY.md`、`UI-UX.zh-CN.md`、UI 3.0、V6 当前实现台账及 SC-001～SC-018。
- 全仓源码盘点为 **833 个生产 Kotlin/Java 文件、432 个 JVM 测试源码、70 个 Android 仪表测试源码**。盘点不等于逐文件逐行人工审查；本轮人工审查围绕生产入口、公开边界、关键实现、消费者及异常恢复展开。
- 当前 Markdown 功能树为 **435 个叶节点**；HTML 仍为 **447 个**。本轮按当前 Markdown 登记全部范围，不沿用历史报告的“447 项全部存在”说法。逐项范围和重新检查的旧证据见 [机器索引](SYSTEM-AUDIT-2026-10-15.evidence.json)。**范围登记、符号存在、单元测试通过，均不等于每个叶节点已完成端到端验收。**
- 真实模型账户、付费媒体服务、Android 16/17 设备、TalkBack、大字号、跨天调度及长期耗电未执行。本轮结论分为代码确认、JVM 行为验证、规模风险和待设备验证。

### 问题数量

确认的代码/接入/文档问题 **6 个根因**：高 0、中高 4、中 2、低 0；另列 **1 个中优先级规模风险候选**。不把可选 UI 增强、已退役能力或未合并开发项混入缺陷数量。

| 编号 | 优先级 | 问题 | 证据层级 |
|---|---|---|---|
| A01 | 中高 | 完整工作历史、结构化工具状态和精确证据没有 UI 消费端，早期成果入口会消失 | 生产调用链确认；历史分页 JVM 测试通过 |
| A02 | 中高 | 逐项交付要求与用户选择的成果证据关联已实现，但没有 UI 操作 | 生产调用链确认；关联规则单测通过 |
| A03 | 中高 | 记忆管理只读取最近 100 条，搜索及“更多”无法到达更早有效记忆 | 生产调用链确认；未做设备复现 |
| A04 | 中 | 成果当前可用性/版本刷新错误依赖事件序号，文件独立变化不触发更新 | 生产状态传播链确认；未做设备复现 |
| A05 | 中高 | 任务暂停、恢复、删除、立即运行缺少异常回执边界，部分失败静默 | 生产异常路径确认；未做故障设备注入 |
| A07 | 中 | 功能树、证据索引及说明失步，已退出入口仍列为现行能力 | 当前脚本实际失败及独立差异核对 |
| A06 | 中，候选 | 成果刷新在每次事件变化后重新计算全部可见文件哈希，工作量随文件总字节增长 | 复杂度确认；未证明真机卡顿/耗电 |

## 2. 系统地图：四张对账表

### 2.1 入口表

| 入口 | 前后台/触发 | 真实调用边界 | 恢复与最终反馈 |
|---|---|---|---|
| HOME Chat 发送、附件、重生成、编辑、版本切换 | 前台，手动 | Shell actions → `LocalHarnessViewModel` → Chat execution/branch Owner | EventLog、模型历史水位、分支投影 → 会话；有拒绝原因与草稿保护 |
| HOME Work 发送、计划、审批、提问、停止 | 前台，手动 | Work execution、run binding、interaction port | Session-owned run 可在切换会话后继续；重新进入投影；证据消费缺 A01/A02 |
| 群聊 | 前台发送/主动聊天 | Chat group membership/turn executor | 成员身份、共享上下文、逐成员结果及失败投影；不是多条无归属的普通 Chat 请求 |
| 人物图集、编辑、导入导出、多故事线 | 前台，手动 | Chat UI actions → persona/gallery persistence | V4 规范化、持久数据和故事身份；当前 13 字段，不按旧 16 类恢复 |
| 人物日记 | 前台查看/纠正/停用；后台生成 | Chat diary/runtime、memory projection port | revision 校验、来源、派生日记失效待办；来源只跳到会话，见 U08 |
| WORKSPACE | 前台，手动 | session files → workspace/path boundary | 文件列表、预览、分享；当前文件详情已经接通 |
| TASKS | 前台 CRUD/暂停/恢复/立即运行 | TasksViewModel → scheduler/store | StateFlow、任务回执和会话跳转；交互异常缺 A05 |
| WorkManager 定时/手动执行 | 后台，自动/手动入队 | Worker → Automation runtime → Chat/Work execution port | generation + task lease + 结算；通知与排程带代次校验 |
| Webhook `/run` | 后台，本机外部请求 | loopback listener → webhook execution → Automation runtime | 认证、大小限制、执行回执；专门管理 UI 非当前必需入口 |
| TOOLS/技能/MCP/GitHub | 前台配置；运行时工具调用 | tools presentation → tool/plugin Owner；执行进入共享 gate | 安装/导入/配置、连接错误反馈；动态工具按真实注册表暴露 |
| 媒体创作 | Chat/Work 加号面板手动提交 | media ViewModel → `LocalMediaCreationRuntime` → provider → store | 持久回执、结果/未知状态、视频查询/保存重试、会话卡片和作品列表 |
| 设置：模型、账户、记忆、用量、权限、诊断、更新 | 前台，手动/刷新 | settings presentation → 对应能力 Owner | 大部分已有真实消费者；记忆全量管理缺 A03 |
| 启动/进程恢复 | 后台，生命周期 | Kernel → bootstrap composition → 各 Owner recovery | 初始化错误投影、恢复协调、禁止未知副作用重放；设备杀进程场景未复验 |
| 通知回到会话 | 系统事件 | Activity/Shell → session navigation | 已有 sessionId 跳转；真实通知权限/系统返回待设备验证 |

### 2.2 所有权与事实源表

| 能力 | 领域/运行 Owner | 权威数据/配置 | 副作用提交者与 UI |
|---|---|---|---|
| Chat/群聊/人物/故事/日记 | Chat 领域与对应 persistence | Chat state、gallery/persona/diary 文档、Session 领域事件 | Chat execution/branch/diary Owner；Chat contribution 消费 |
| Work/Goal/Todo/计划 | Work run binding/execution | Work state、`goal/state` 等事件 | Work tool/turn Owner；当前会话 projection；Goal/Todo 结构化面缺 A02 |
| Automation | scheduler + store + worker settlement | AutomationDocument/WAL、`scheduleGeneration`、receipts | 条件提交与带代次的排程/通知；Tasks StateFlow 消费 |
| Session 所有权 | `LocalSessionRuntimeRegistry` | 按 session 的 lease/mutex | 前台、自动化、维护、删除共用；不是 UI 当前会话变量决定后台归属 |
| Agent run 身份 | `LocalAgentRunCoordinator` 及 run registry | runId、sessionId、route fingerprint、checkpoint | 持有者栅栏；恢复不与存活 Owner 抢任务 |
| 模型/凭据 | gateway、route catalog、credential resolver | profile/credentialRef；Keystore；每 run 冻结快照 | 请求协调/adapter；配置和模型选择器消费 |
| 工具/审批 | registry、execution coordinator、approval preferences | 本 step 暴露面、元数据、执行身份、审批模式 | 统一 admission/gate；执行状态 UI 缺 A01 |
| Memory | MemoryStore/Manager；Chat 解释日记失效 | MemoryDocument、source refs、待确认失效记录 | 同次持久变更记录待办；Chat port 幂等处理；设置管理范围缺 A03 |
| Jobs/团队 | job manager；团队 runtime/reducer | job snapshots/inbox、团队事件与 revision | 持久 job/团队命令 Owner；团队 UI 接通，通用 job 手动控制见 U07 |
| 媒体 | media creation runtime + media store | SQLite 记录、job/result/video、route fingerprint | provider 操作/保存/删除由同一 Owner；会话只读展示 |
| 用量/诊断 | usage tracker、analytics、AppLog | 提供商实际 usage 和持久账本 | 设置用量/诊断投影；不以 prompt 估算当计费总量 |
| 进程生命周期 | `LocalRuntimeKernel` | start-once、scope、bootstrap 状态 | 只触发初始化/恢复与错误投影；具体 Provider 在组合根 |

### 2.3 契约表

| 契约 | 关键身份/字段 | 已追到的消费者 | 对账结论 |
|---|---|---|---|
| `LocalSendResult` | accepted/rejectReason/queue disposition | Composer、send feedback | 已有实际 UI 消费，拒绝不等于发送成功 |
| `LocalConversationSurfaceState` | sessionId、mode、messages、pendingApproval/Question、team | `LocalConversationSurface` | Chat/Work 分别投影；并未包含完整 Goal/Todo/job 列表 |
| `LocalWorkUiState` | goal、todos、jobs、resources、plan | ViewModel → actions | 构造已接线，但当前没有真正收集并渲染该 workState 的调用；不能把传参当消费 |
| `LocalWorkHistoryCursor/PageUi` | logIdentity、generation、beforeSequence、older | runtime、ViewModel、JVM 测试 | 未进入现行 UI actions；A01 |
| `LocalToolActivityUiItem` | callId、executionId、phase、errorCode、事件水位 | runtime、ViewModel | 未到达工具详情渲染；普通时间线只取错误布尔等摘要；A01 |
| `LocalArtifactUiItem` | path/category、sourceCallId、asOfSequence、versionAtCreation/Now、currentlyAvailable | 会话成果卡、文件预览 | 基础消费接通；历史范围 A01、文件独立变化 A04 |
| `LocalRequirementEvidenceLink` | requirementIndex+文本、sourceCallId、originSequence、SHA-256 | runtime、投影和校验测试 | 缺 UI 选择/保存/回查；A02；不等同验收通过 |
| `MemoryRecord` | scope、owner/source、active、updatedAt、pending invalidation | Recall、settings、Chat 日记清理 | Recall 与管理查询范围不对等；A03 |
| `AutomationTask/Receipt` | id、scheduleGeneration、status、sessionId、结果/错误 | Worker、scheduler、Tasks 列表/详情 | 正常链可达；交互错误边界 A05 |
| persona V4 | 10 fact categories + name/identity/franchise；来源/故事阶段 | 编辑、导入导出、runtime projector、preset | 当前实现收口，不恢复已明确删除的 6 类旧字段；功能树仍旧 A07 |
| 媒体记录 | operationId、sessionId、profileId、route fingerprint、phase | 创作面板、会话、作品列表 | 侧录媒体可展示；不会自动作为模型人物对话重放，见 U10 |

### 2.4 生产装配表

| 装配点 | 当前事实 | 未据此推导的结论 |
|---|---|---|
| `local/feature/LocalFeatureCatalog.kt` | 6 个 owner（含 Shell）、7 条主路由，逐路由唯一归属 | 7 条路由不等于 7 个功能，更不证明全部低频操作已接入 |
| `ui/screens/local/LocalHarnessScreen.kt` 与各 Feature contribution | HOME、人物图集、日记、工作区、任务、工具、设置真实装配；Back/restore 走 catalog | 没有独立 Project/Run Center 路由；不应按旧图恢复 |
| `local/tools/LocalPluginComposition.kt` | 9 类插件构造、catalog、lifecycle manager、startup install | 插件安装不等于第三方服务账户已授权或工具执行成功 |
| `LocalRuntimeBootstrapComposition.kt` | 技能初始化、日记待办恢复、运行时准备、插件安装、会话恢复、持久 job 恢复 | 设备进程被杀/系统撤权仍需 Android 行为验证 |
| App/设备库 Manifest | 本机执行/Webhook 前台服务、设备服务、权限和 FileProvider 声明 | Manifest 存在不代表用户已授予系统权限 |
| `HarnessAutomationWorker` | 真正 Worker 入口、Hilt entrypoint、task lease/generation | 入队回执不等于本轮已完成；设备长时间调度未验证 |
| 模型 adapter registry | gateway 选路、协议适配、credential 边界 | 本轮未调用真实模型，不声明所有供应商线上可用 |
| `.github/workflows/ci.yml` | scope、静态/架构、JVM、构建、设备、merge gate 分工存在 | 本地检查不代表当前远端完整 CI/发布资格通过 |

## 3. 哪些功能已有实现和入口

这里的“已接通”表示本轮追到生产调用和消费者，并非设备/线上服务已全面验收。

| 功能组 | 当前实现与入口 | 尚缺的完善/验证 |
|---|---|---|
| 会话外壳/历史 | Chat/Work 模式、抽屉、创建/切换/重命名/固定/删除、历史分页、草稿 | 大历史真机帧时、恢复与系统 Back/IME |
| 单聊 | 流式、附件、排队、停止、重生成、消息编辑、版本/分支、回复建议 | 真实供应商、迟到回调及长对话设备复验 |
| 群聊 | 人物选择、成员维护、点名/接话、公告、逐成员状态、失败提示 | 成员部分失败/切会话/后台恢复的设备行为 |
| 人物 | 图集、预设、头像/立绘、V4 编辑/导入导出、核验/补全、多故事线 | 新 13 字段变更后的完整设备操作；旧资料退出按当前契约判断 |
| 日记/关系 | 后处理、相关召回、来源、纠正/停用、修订、与记忆失效协作 | 精确来源消息定位 U08；#726 未合并 |
| Work 主循环 | 工具、计划模式、计划审核、审批/提问、排队与立即调整、停止 | A01/A02；不能仅据最终回答判断交付达标 |
| Agent 团队 | 显式团队发送、成员/任务投影、状态、输出、发消息、单人/全队停止 | 通用 job 不等于团队成员；U07；冲突声明不是文件沙箱 |
| 子代理/后台 job | launch contract、独立/fork 历史、inbox、continuation/checkpoint、恢复 | 长期并发和真实外部工具恢复；手动控制入口 U07 |
| 工作成果/文件 | 成果卡、预览、完整文件、分享、变更提示、工作区浏览 | A01/A04/A06；revision 复制 U09 |
| 定时/主动聊天 | 一次/周期、时间窗/静默、暂停/恢复/编辑/立即运行/删除、receipts、结果跳转 | A05；Android 后台限制、跨天/时区/通知权限 |
| Webhook | 本机 loopback、bearer、请求限制、结果存储、工具启动/停止/轮换/复制令牌 | 无专用面板；属于可选 U11，不是不可达功能 |
| 技能 | 列表、创建/编辑/移除、中文名、SKILL.md/ZIP 导入、preset、对话使用 | 文档选择器/恶意包/真实执行设备复验；旧“无导入 UI”不再成立 |
| MCP/GitHub/LSP | HTTP/stdio 配置和连接、GitHub 凭据、能力发现、LSP 工具 | 需真实 server/token/进程；测试替身不代表连接成功 |
| Web/文件/进程/Git/JSON | Registry 中的真实工具和 runtime provider；模型发现后执行 | 统一审批/路径/网络边界仍需实际服务与设备验证 |
| 视觉/设备 | 图片输入/视觉 route、无障碍/通知/虚拟屏 provider、权限设置 | 真实 Android 系统授权、屏幕共享、设备 UI 和撤权 |
| 模型/账户 | 配置、发现、测试、切换、协议、ChatGPT OAuth/账户、推理/温度、worker route | 真实账户套餐权限和各协议线上完整回合 |
| 媒体 | 图片、视频、配音、转写；provider/kind 目录、持久 job、未知态、保存重试、作品管理 | 自动创作路由/中文音色/上下文一体化 U10；付费调用未执行 |
| 记忆 | 显式/自动捕获、召回、来源关联、编辑、停用、分类、来源定位 | 普通列表全量搜索/分页 A03 |
| 用量/外观/诊断/更新 | 实际 usage 分组与详情、MiniMax 配额、主题/壁纸、权限、诊断、更新检查/下载校验 | 线上配额/安装、视觉无障碍真机；不能把诊断记录当完成证据 |
| 共享存储/运行 | EventLog 分段归档、水位/缓存、snapshot/WAL、owner/run fence、resource lease | 磁盘满/杀进程/大历史的设备与压力验证仍不足 |

## 4. 已实现但没有完整接入 UI 的能力

| 编号 | 能力/现有实现 | 当前断点 | 建议接入位置与边界 |
|---|---|---|---|
| U01 | `historyPageForUi` → `projectLocalWorkHistoryPage`；每页 160 事件，含归档 | ViewModel 方法没有生产 UI 调用；actions 无对应接口 | 会话内“工作历史/较早成果”按需展开，保留游标和失效提示；无需恢复独立运行中心 |
| U02 | `toolActivitiesForUi`、`toolEvidenceForUi`，完整 phase/执行身份/精确事件读取 | 只有普通摘要时间线，没有调用详情消费者 | 点工作步骤打开只读详情，区分声明、运行、失败、取消、结果未知；隐藏默认技术噪音但允许按需核验 |
| U03 | `linkRequirementEvidenceForUi`、`requirementEvidenceForUi` | 没有需求选择、成果选择、提交、回查界面 | 当前任务的低频交付详情；明确“用户选择的引用”，不能把关联操作显示为测试通过 |
| U04 | `LocalWorkUiState.goal/todos/plan` | state 已投影并传入 actions，但没有实际收集渲染；会话只有叙述/审批等 | 需要查看结构化任务时按需展开；不强制恢复旧五段布局；与 U03 成组设计 |
| U05 | `MemoryStore.listActive` 支持更大读取范围、Recall 能访问旧记录 | Facade 固定 100，搜索只筛现有列表 | Memory Owner 提供有作用域的查询/游标/hasMore；继续使用同一记忆库；A03 |
| U06 | `LocalWorkRuntime.artifactsForUi(eventLimit)` 可增大到 4096，完整历史另有 cursor | 当前 UI 只用默认 384 | 优先 U01，不以继续增大最近窗口假装完整历史 |
| U07 | job list/output/kill/send、持久子代理和恢复 | 团队成员有操作 UI，通用 shell/web/非团队 job 没有同等手动面板 | 若产品需要用户独立排障，提供会话内后台任务详情；工具入口本来可达，不能报告成执行功能不存在 |
| U08 | 日记来源有 sessionId、userMessageId、assistantMessageId | 卡片展示消息 ID，但跳转只传 sessionId | 后续补“来源会话+消息”定位、加载旧页、高亮/失效提示；当前只能宣称会话级跳转 |
| U09 | 成果 projection 支持 link/revision，当前卡片可显示 reference | revision 只有文本；HTTPS 有打开操作，HTTP 链接无等价按钮 | 提供复制 revision/链接、受控打开；GitHub revision 无仓库身份时不要猜 URL |
| U10 | 媒体 API、持久结果、Chat/Work 会话卡片已接入 | 普通发送未统一走创作意图路由；sidecar 媒体不是模型历史对话 | 按 #727 的最终实际实现复核；自动创作必须带真实提交/付费/未知结果语义，不是只增加按钮 |
| U11 | Webhook start/stop/status/rotate/copy token 工具完整 | 没有专用设置面板 | 可选增强；保留 loopback/敏感剪贴板边界，不把缺面板认定工具不可用 |

U01/U02/U06 共用 A01；U03/U04 共用 A02。U07～U11 单列接入/完善需求，不额外膨胀确认缺陷数量。

## 5. 确认问题、根因与复核证据

### A01【中高】工作历史及工具证据只到达 ViewModel，长任务成果回查没有闭环

**代码位置**：`local/work/LocalWorkRuntime.kt:49`、`:68`、`:138`、`:165`；`ui/screens/local/LocalHarnessViewModel.kt:270`；`LocalFeatureUiActions.kt:99`；`LocalConversationSurface.kt:470`；`WorkContentBlock.kt:146`；`LocalTranscriptPresentation.kt:258`。

**当前实现/具体问题**：成果 UI 每 2.5 秒检查事件序号，只调用默认 `artifactsForUi(sessionId)`，扫描最近 384 个事件、最多约 48 个成果。`historyPageForUi` 能越过归档分页，`toolEvidenceForUi` 能精确读取选定调用，但全量生产调用搜索后只见 runtime 与 ViewModel 定义/转发，没有 UI 消费。向上滚动旧消息不会加载这套成果历史；成果卡是独立追加的投影。工具时间线展示摘要/失败布尔，未消费完整的结构化 phase/identity/evidence。

**根因与完整链路**：EventLog → 历史/工具/成果 projection → runtime → ViewModel → **缺 actions/详情 renderer** → 用户只能看到近期窗口。横向影响 Work、团队工具证据、长期后台任务、交付排查；不是 EventLog 丢失。

**触发与用户感知**：早期产出之后再产生超过 384 个事件，早期成果卡会退出当前窗口；“文件仍在工作区”不能替代来源事件/版本/调用证据的回查。结果未知与普通失败的详细解释也缺少按需入口。

**测试盲区/证据**：`LocalWorkHistoryProjectionTest.pagesReachEarliestArtifactBeyond4096EventsWithoutDuplicates` 及游标失效测试本轮通过；它们直接调用 projection，不能证明 UI 可达。当前生产引用集合与 actions 定义共同确认断点。

**误报排除**：现行 Work 已有时间线、工具步骤、成果卡、完整文件和分享；不报告成“Work 没 UI”。独立运行中心确已按 V6 退出，不建议恢复旧页面。不是把普通消息分页误认为不存在。

**根治方向/关闭条件**：沿 Work-owned 的窄 UI 契约接入同一 EventLog 的历史与详情；空页仍可继续按 cursor 查询；会话切换/日志重置使旧请求失效；设备上从长任务真实到达早期成果和精确工具证据。复用 SC-001/003/010/011/014/017。

### A02【中高】交付要求、成果来源关联与当前任务 UI 脱节

**代码位置**：`local/work/LocalWorkRuntime.kt:84`、`:120`；`LocalRequirementEvidence.kt`；`local/presentation/LocalWorkUiState.kt:28`；`ui/screens/local/LocalHarnessViewModel.kt:279`；`LocalFeatureUiActions.kt:99`；`LocalHarnessScreen.kt:481`。

**当前实现/具体问题**：后端已验证当前 session/WORK 模式、需求下标与文本、选定来源事件、callId、所选文件 SHA-256，并写 `work/requirement-evidence`。有读取和当前版本核验。UI actions 没有 link/read 方法；goal/todos 的完整 workState 虽然被传入，但没有实际渲染调用。因此用户不能从现行界面完成“这一条需求选择哪一份真实成果”的操作。

**根因/影响链**：Todo/Goal → 真实成果事件 → 选择并核验版本 → 关联持久提交 → 回查/变更提示，这条链断在用户选择和消费；同时拖累 Work 交付可解释性、长任务验收和任务恢复后核验。

**用户感知/长期风险**：能看到文件，不等于能知道它对应哪个要求、关联时是哪一版本。任务和成果增加后只能依赖文字叙述理解覆盖范围。

**测试盲区/证据**：`LocalRequirementEvidenceProjectionTest`、`LocalWorkArtifactVersionTest` 本轮通过；直接规则测试不能验证用户入口。现行所有 UI 引用搜索没有 link/read 调用。证据为当前 SHA 的静态接线与 JVM 规则行为。

**误报排除**：审批/问题/计划审核已接入，不是整个计划系统没有 UI。`localWorkDeliveryEvidence` 的自动执行证据与人工选择引用各有语义；不能用人工关联代替真实测试/构建成功，也不要求恢复独立产出页。

**根治方向/关闭条件**：Work Port 提供结构化需求/成果读取和带回执的关联命令，在会话低频详情接入；覆盖文件在选择后变化、日志 reset、切会话、写盘失败、再次提交、重启读取。复用 SC-003/004/010/011/017/018。

### A03【中高】旧记忆仍可能影响模型，但常规管理 UI 无法查询和纠正

**代码位置**：`local/presentation/LocalSettingsDataFacade.kt:36`；`ui/screens/settings/MemorySettingsController.kt:28`；`MemorySettingsSections.kt:218`、`:224`、`:390`；`local/memory/MemoryStore.kt:319`、`:432`。

**当前实现/具体问题**：Facade 对普通管理固定 `limit = 100`，Store 按 updatedAt 降序截取。UI 搜索先取这 100 条再按正文过滤；“显示更多”只把本地 visibleLimit 从 20 增加。没有 Store 查询参数、游标、hasMore 或剩余总数。Recall 则在同一作用域的 active records 中找候选，不限制于管理页的最近 100 条。

**根因/影响链**：MemoryStore 全部有效事实 → Recall 可以继续使用；MemoryStore → Facade 截断 → Settings 本地搜索 → **旧事实没有管理入口**。这是召回能力与用户纠正能力不对等。影响 Chat/Work 的长期偏好/事实、来源纠正、派生日记清理。

**触发/用户感知**：同一可见作用域超过 100 条，搜索第 101 条旧事实显示无匹配；它仍可能被后续模型上下文召回。列表里“过滤数/records.size”只是当前批次，不是全库规模。

**测试盲区/证据**：MemoryStore 的搜索、来源过滤、版本/失效等单测通过；未发现覆盖“>100 条 → 设置搜索旧记录 → 编辑/停用 → 后续不再召回”的 UI 验收。本轮确定性证据是查询顺序和截断位置，未声称已在设备注入 101 条。

**误报排除**：按某条来源消息定位记忆已有单独接口，可以找到对应来源的记录；但来源缺失/旧记录/用户不知道原消息时不能替代普通全库管理。没有要求取消 scope/角色隔离，也不主张一次把所有记录加载到 UI。

**根治方向/关闭条件**：Memory Owner 提供作用域内的管理查询和分页状态；查询必须在数据源过滤后分页，不能只扩大前端 visibleLimit。用 101+ 条记录验证旧事实可定位、编辑/停用、版本冲突、重启和派生日记失效协作。复用 SC-003/004/006/010/011/013。

### A04【中】文件当前状态的刷新仅绑定 EventLog 序号

**代码位置**：`ui/screens/local/LocalConversationSurface.kt:474`；`local/work/LocalWorkRuntime.kt:54`；`LocalGeneratedMediaMessage.kt:627`、`:641`、`:691`。

**当前实现/具体问题**：轮询在 `current != lastSequence` 时才查询成果并重算当前哈希/存在性。成果卡的 file/preview 依赖 artifact.versionNow/currentlyAvailable 等键。若 shell/其他进程在运行期间修改或删除文件，而还没写入新事件，轮询不会触发查询，可能持续展示旧的可用性、预览和版本。

**根因/影响链**：工作区文件系统是当前字节事实源；EventLog 是执行历史事实源。两者变化版本被错误等同。外部文件变化 → 无事件变化 → artifact snapshot 不变 → 卡片旧状态。横向影响后台进程、长运行 shell、文件分享和版本提示。

**测试盲区/证据**：当前 availability/version 单测重新调用投影即可得到新状态，本轮通过；没有覆盖“页面保持打开、无新事件、文件自行变化”。本轮证据为确定的刷新条件；真机陈旧预览未复现。

**误报排除**：新的工具完成事件会刷新；离开重入通常也会重读；打开文件详情有重新读取/失败反馈。不是声称已删除文件一定仍能成功打开，更不是认定持久事件被错误改写。

**根治方向/关闭条件**：分别对待历史事件水位与当前文件事实；按页面恢复、用户刷新、实际打开/分享或受限文件观察触发校验。历史版本保留，只更新当前访问状态；不要为修此问题无条件高频全文件哈希。复用 SC-003/006/007/008/017。

### A05【中高】任务管理的异常未完整返回 UI，写盘和调度在点击回调内同步执行

**代码位置**：`ui/screens/tasks/TasksViewModel.kt:56`、`:58`、`:68`、`:70`；`TasksScreen.kt:281`；`automation/HarnessAutomationScheduler.kt:348`、`:365`、`:393`；`HarnessAutomation.kt:389`、`:430`；`local/persistence/JournalDocumentFile.kt:65`。

**当前实现/具体问题**：cancel/pause/resume/runNow 从 Compose onClick 直接进入同步方法；pause/resume 忽略返回 false 后只 refresh。Store 的 journal/fsync、损坏恢复和 WorkManager 提交均可能抛异常，上述方法没有捕获并产生失败状态。相比之下，同一 ViewModel 的 update/create 有失败处理，其他页面也有明确错误反馈。

**根因/影响链**：用户点击 → TasksViewModel → scheduler → 持久化/WorkManager → 异常越过 UI 回调；缺少统一的受理/失败回执边界。任务代次并发拒绝也没有向用户解释。影响前台任务管理；Worker 执行结算的正确性不能兜住这个入口。

**触发/用户感知**：磁盘故障、损坏文档、排程失败、任务在点击前被更新时，可能抛出未处理异常或没有任何失败提示。主线程同步 fsync 也增加设备 IO 较慢时的交互风险；本轮未量化卡顿。

**测试盲区/证据**：AutomationStore recovery 与调度/代次相关规则单测通过，不代表 Compose 按钮的异常处理成立；没有对应点击 → IO 失败 → 保留任务 → 错误/重试回执的设备证据。代码可达性和异常传播已确认，设备崩溃未注入。

**误报排除**：不是说 scheduler 没有 generation 或后台结算无保护；入队与持久化均有实现。不能简单重试整个操作，因为排程可能已受理而反馈失败。

**根治方向/关闭条件**：由 Automation UI 边界返回结构化操作结果，在 IO 调度中执行，拒绝/失败可见，保留原列表及可重试上下文；分别处理持久提交成功但入队失败。覆盖 false、异常、并发代次变化和实际后台执行状态。复用 SC-001/002/003/010/012/017。

### A07【中】当前功能台账不能可靠回答“实现了多少”

**代码/文档位置**：`docs/FEATURE-TREE.zh-CN.md`、`.html`、`FEATURE-REVIEW.auto.json`、`FEATURE-SOURCE-INDEX.auto.json`、历史 `FEATURE-REALITY-AUDIT`、`VALIDATION.md`；`.github/scripts/check-feature-tree-sync.py:58`；`check-ci-repository-integrity.py:433`。

**当前实现/具体问题**：Markdown 528 个非根节点/435 叶；HTML 544 个非根节点/447 叶。HTML 仍含 Project 及独立运行中心，Markdown 已调整；源码目前 7 条 route，旧索引写 9 条；旧统计 842 个生产文件，当前是 833；验收索引为 122 项，说明仍有 128/409 等旧口径。当前人物树还写 16 类，主线已统一 13 字段。

**根因/影响链**：代码/入口变化 → 人工维护多份派生文档未同步 → 产品树/HTML/证据等级/验收数字不一致 → 用户和后续审计误把旧声明当现行能力。手动树校验本轮实际退出 1；其 workflow 已明确退役，该脚本是人工复核器，不是当前必需 CI lane；因此 CI 通过不能排除此问题。

**误报排除**：不把手动检查失败叫作当前必需 CI 失败；不把从树移除的 Project 判成“应补 UI”。不用文件或测试引用存在直接证明独立功能数，也不因无单测删除功能。

**根治方向/关闭条件**：按当前调用链人工核准变更，再同步树两视图、源码索引、证据台账和说明；不机械运行旧生成器把退休功能加回来。当前源码指纹、相同层级、唯一身份、计数、有效测试引用和手动校验同时一致。复用 SC-004/005/011/014/015/016。

### A06【中，规模风险候选】成果哈希的刷新成本与字节量成正比

**代码位置**：`local/work/LocalWorkRuntime.kt:54`；`LocalWorkArtifactVersion.kt:35`；`ui/screens/local/LocalConversationSurface.kt:474`；`LocalWorkDeliveryEvidence.kt:69`。

成果列表每次重算都会对每个可见文件完整读取 SHA-256；事件变化可能只是模型/工具状态，与文件内容变化无关。一次工作量为 O(扫描事件数 + 可见文件总字节)，不是仅 O(40～48 个列表项)。IO dispatcher 防止直接阻塞主线程，但不会消除读放大。大量文件/大成果/高事件频率时可能增加延迟、耗电和文件 IO 竞争。

已确认复杂度；**未进行大文件真机 benchmark，不计入 6 个确认缺陷，也不宣称已造成 ANR**。小文件状态下可能影响很小。

建议由文件/成果 Owner 区分概览刷新与严格提交校验：概览复用明确失效的版本观察，实际证据关联/验收时再严格哈希；不能把 mtime 当不可碰撞的提交证据，也不能静默拒绝合法大文件。A04/A06 成组设计，避免修陈旧状态后把读放大加倍。复用 SC-005/006/013/017。

## 6. 横向与纵向专项结论

### 6.1 横向复用

| 维度 | 当前实际共用/差异 | 判定与限制 |
|---|---|---|
| Session ownership | Chat、Work、Automation、维护和删除使用共享 lease；Work 可有会话绑定后台 run | 已追到生产实现；不是每种模式复制 session lock；Android 多入口恢复仍待验证 |
| Model/credential | gateway + route/profile + coroutine run context；子代理持久 resume 带 profile/fingerprint | 正常请求和恢复按身份检查；不能把同一 model 名称当同一账户 |
| 工具暴露和执行 | Tool Registry、每 step 可见集合、scope/计划模式、审批、execution-started | 共用执行边界；Chat 的对话域与 Work 的可变工具面差异有明确用途，不应强行拉平 |
| 审批配置 | `LocalApprovalPreferences` 提供 DEFAULT/MANUAL/AUTO，Work 与设置消费 | 事实源已集中；系统授权与工具审批相互独立 |
| 子代理/团队 | shared jobs/inbox、capabilities、schema、fork seed、checkpoint；团队另解释 roster/task/revision | 能力复用成立；write_scopes 是冲突协调，不是 OS 文件隔离 |
| Automation | Chat/Work execution ports，Worker task lease、generation、条件结算 | 自动入口执行存在；UI 错误回执不对称 A05；跨天系统行为未验证 |
| EventLog/persistence | 单一事件事实、分页/归档、snapshot 水位、checkpoint/备份/WAL | JVM 可验证行为通过；持久化正确不能自动证明消费 UI 完整 A01/A02 |
| Memory/Diary | Memory 保存事实和失效待办；Chat port 执行派生日记清理并确认 | 已有补偿链，不报告旧“修改记忆不影响日记”；A03 是管理可达范围问题 |
| 媒体 | Chat/Work 共用 media runtime/store；结果按 session 归属 | 真实媒体操作已接入；不自动成为人物/模型对话记忆，是当前边界，自动协作见 U10 |
| Remote Harness | 当前主路由/装配已退出远程控制 | 不适用；不要依据旧审计要求补 Relay/远程页面 |

### 6.2 状态、恢复、并发、安全

- `LocalAgentRunCoordinator` 保存 run/route 身份和 checkpoint，并校验当前 Owner；`LocalSessionRuntimeRegistry` 维护等待者与单一 session owner。前台和后台不是依赖“当前会话”猜归属。相关 JVM 契约通过，不等于已做真实杀进程验证。
- `SessionRecovery` 区分工具声明、真实 execution-started、未开始/结果未知；未知副作用不自动重放。当前 UI 的按需详细展示仍缺 A01，**没有据此认定执行层会重放副作用**。
- DurableFileCommit 包含 temp 写入/fsync/原子替换/目录同步；RecoveringDocumentFile 保留 recovery-required；JournalDocumentFile 备份可靠后才清 WAL。复核了正常路径、主/备份损坏与待恢复语义；不以“写入函数返回”替代所有领域的完成结果。
- Automation 排程代次单调、删除留 watermark、Worker task lease、结算与通知按代次检查。A05 针对交互层的异常处理，未重复报告已经修复的旧代次问题。
- Web 目标解析/固定地址、重定向重新校验、非安全方法的重定向策略、有界响应已在生产安全边界；Webhook 为 loopback+bearer；OAuth callback 为随机 loopback，并由账户域完成校验；更新校验包名/签名/哈希/版本。静态安全门禁与相关单测通过，**不是渗透测试或所有线上端点安全认证**。
- Shell 权限受 Android 应用权限与统一审批控制，工作区路径工具不是 OS 沙箱；团队 write_scopes 也不是文件白名单。属于已明确的产品边界，不列为新漏洞。
- 规模方面已检查 EventLog 分段/分页、投影窗口、Memory 2000 条预算、Automation receipts 30 天/200 条、流式预览等现有边界。A06 暴露“返回数量有界但字节工作量未被消除”；未执行 1k/10k/50k 事件及大文件/大量会话的完整压力矩阵。

### 6.3 配置应如何共享

| 配置/定义 | 正确 Owner/处理 | 本轮结论 |
|---|---|---|
| 全局审批模式 | Shared Interaction preferences，UI 只配置/读取 | 已集中，不新增第二份 |
| 模型 route/profile、凭据、推理及温度上限 | Model Owner；运行中冻结，新 Run 使用新配置 | 保留身份快照，不误报为重复配置 |
| Chat/Work/自动聊天历史摘要与超时 | 各领域解释，底层中立机制复用 | 生命周期/语义不同，不因数值相同强行合并 |
| 成果窗口、历史页长、文件刷新策略 | Work/文件 Owner，UI 请求消费 | A01/A04/A06 应在正确边界统一，避免 UI 与 runtime 各建缓存/定时规则 |
| 记忆管理查询页长、hasMore、scope/query | Memory 管理查询契约 | 固定 100 与 UI 本地 20 的分工不完整；A03，不能只统一为同一常数 |
| 工具 phase/errorCode/sideEffect | Shared Tool contract | UI 应消费现有语义，不再自己用文字判断结果未知或安全可重试 |
| 人物字段、功能路由、叶节点计数 | 当前领域 schema/catalog；文档作为派生视图 | A07；不要把旧报告当另一个配置事实源 |
| Provider 特定媒体模型/接口 | Media catalog/adapters | 协议能力不同，继续独立；本轮未验证外部最新服务契约，不凭名字宣称可用 |

## 7. 验证与证据真实性

本轮从已提供的 Artifact 工具链运行离线 Gradle，没有从包管理仓库补开发依赖。首次启动受本机 gpgv 搜索路径及沙箱本地网络接口限制；加载现有工具链并允许本地网络后成功，不能把前两次环境失败归为产品缺陷。

### 7.1 当前 SHA 实际执行

```sh
source /workspace/777-dev/activate.sh
DEV777_TOOLCHAIN_STATE_DIR=/tmp/777-audit-toolchain \
  bash tools/dev/ai-toolchain.sh gradle \
  :core:test :harness-core:test :harness-runtime-android:test \
  :harness-interop:test :harness-device-android:testDebugUnitTest \
  :app:testDebugUnitTest :reference-validation:test
```

Gradle：**BUILD SUCCESSFUL，3 分 53 秒**。读取本次生成的 JUnit XML 得到：

| 模块 | tests | failures/errors/skipped |
|---|---:|---|
| core | 27 | 0/0/0 |
| harness-core | 216 | 0/0/0 |
| harness-runtime-android | 15 | 0/0/0 |
| harness-interop | 57 | 0/0/0 |
| harness-device-android | 10 | 0/0/0 |
| app | 2446 | 0/0/0 |
| reference-validation | 3 | 0/0/0 |
| 合计 | **2774** | **0/0/0** |

架构、性能不变量、Android 安全、UI 中文/设计系统/颜色、通知、Kotlin 风险、构建基线、测试质量、验收索引、CI 完整性共 **12 项通过**；人工 `check-feature-tree-sync.py` **失败**，错误为 Markdown/HTML 功能节点不一致。验收索引核验输出 **122/435，零完全签收叶节点**。该数字是现有登记状态，不意味着 313 项都没实现，也不意味着 122 项已经端到端通过。

另实际执行架构边界、性能不变量、CI 范围分类器的 `--self-test`，三项均通过，未以脚本存在代替门禁反向样例运行。

`:app:lintDebug` **BUILD SUCCESSFUL，4 分 32 秒**。SARIF 有 **859 条 warning、0 条 error**，其中 UnusedResources 752、UseKtx 41、AutoboxingStateCreation 14、窗口尺寸 API 建议 10、ModifierParameter 10。它们是工程清理/设备复验线索，不是 859 个已复现功能故障。网络配置的明文与用户 CA 提示已回查：应用允许用户配置的 HTTP/MCP，模型凭据端点另有 HTTPS-or-loopback 校验；本轮未证明存在实际凭据外泄路径，不能仅凭 lint 规则名定为漏洞。旧远程 Harness 注释仍有清理价值。

本轮没有 APK/设备证据，不从编译、lint 和单测推导真机通过。

### 7.2 本轮没有证明的行为

没有 Android 模拟器与 16/17 system image，未运行仪表测试、真实设备 UI、TalkBack/字体放大/复杂壁纸、权限/通知/摄屏、杀进程恢复、跨天调度、长期耗电；未运行付费模型/媒体、真实 MCP/GitHub/LSP 服务矩阵、官方 fixture 联网刷新、完整 APK/ABI/签名/发布资格验证。

对应缺口保持“待验证”，不是失败，也不是通过。当前没有发现证据足够的新高危安全/跨账户串线/数据丢失问题，不等于这些领域已获全面安全保证。

## 8. 并行 PR、退役与误报处理

开始核查时远端 open PR：

| PR | 状态/Head | 与本轮关联 |
|---|---|---|
| #705 | Draft，`71b63c93b42abe64684cfc9cf834e88d0ffaf5ab` | UI 3.0 ports/pack 迁移，涉及 Work evidence adapter/runtime、媒体和会话 UI；主线未完成 UI 3.0 不作为已交付能力 |
| #726 | Open，`317f9bfe4f1c26976e35da1c9dda2ce812510dfc` | 人物手记/可信记忆/日记界面；复核 A03/U08 时关注交叉，不能从标题推断修复 |
| #727 | Draft，`e63b39c62c1046ab63b037197171f0f3eddb9fea` | 自动创作路由、中文音色、媒体时间线；U10 与此直接相关；未合并不计主线实现 |

已读取 PR 文件元数据识别交叉；#705 第一页已达 100 文件，文件清单不完整。**没有审完三个 PR 的完整 diff，也没有验证 main+三 PR 组合**，因此不声明冲突已解决或候选缺口已由 PR 修复。#705/#727 已显示共享媒体/runtime/会话文件交叉，后续需按最终 Head 做语义整合。

**输出前复核（08:00 UTC）**：远端 main 仍为本轮审计 SHA，主线结论保持有效。三个 PR 的 Head 已分别推进至 #705 `85add5a3`、#726 `1e9c5a0d`、#727 `0ae4ad2b`；上表保留开始核查时快照，之前读取的文件清单只证明当时交叉范围，不能代表新 Head 已审。它们仍未合并，本报告没有将其功能计为主线交付。精确 SHA 和时间见机器索引 `final_remote_recheck`。

已排除的历史/设计误报：Project 页面及远程控制退出；独立运行中心退出；技能文件导入现已接通；团队输出已有 IO 读取、错误/重试反馈；工具连接已有安全错误分类；成果当前可用性、版本和逐项打开/分享已经实现。上述旧问题不重复列为本轮新缺陷。

UI 3.0 是应实现的规范；当前仍为 V6 feature contribution，不能宣称中立 Scene/Port、两套独立 UI Pack、反向替换验收已在主线完成。也不据此要求本轮审计擅自实施 UI 重构。

## 9. 治理顺序、系统上限与完成条件

1. **先处理用户纠正与错误回执**：A03 的旧记忆管理和 A05 的任务操作失败。它们影响用户控制事实与后台工作的能力，优先于纯性能美化；先确定 Memory/Automation 的查询/命令契约。
2. **把 Work 证据一次接通**：A01/A02 共用 Work-owned presentation/port；历史 cursor、工具详情、结构化需求和成果来源关联一组设计。保留会话式体验，不能只增加一个无行为的按钮或扩大最近窗口。
3. **同步解决文件事实与成本**：A04/A06 使用文件 Owner 的明确刷新/失效规则；概览与严格证据提交分开，避免无界高频哈希或把文件 stat 当验收证据。
4. **校正范围后再更新派生文档**：A07 要在当前真实代码、产品退役和新接口稳定后同步，避免旧生成结果重新引入 Project/运行中心。文档更新可以并行准备，但不能成为功能完成证明。
5. **专项复验**：每组改动按 VALIDATION 的真实风险覆盖 JVM、静态/架构及设备；A01/A02 需长历史导航和切换失效，A03 需 101+ 记忆，A04/A06 需事件不变的文件变化及大文件，A05 需持久化/调度部分失败。

最限制系统上限的公共短板是**用户可达的事实消费边界**：数据越多，最近窗口与局部搜索越无法满足回查和纠正。修复 Work evidence Port 和 Memory 管理查询可同时改善多个功能；没有证据表明需要另建 EventLog、记忆数据库或新的全局管理器。

共享结论复用 SC-001～SC-018 中的适用项；具体引用见各问题。所有新发现均可由现有“完整入口、状态消费、字段契约、自动/恢复、异步身份、证据等级”解释，**本轮不新增、不合并、不废弃共享结论条目**，避免复制已有规律。

本报告完成的是当前 SHA 的系统链路代码审计、缺口复核和本地可执行验证；**没有把全部 435 个功能逐条设备/真实服务验收判为完成**。各叶节点未具备的独立行为证据保留在附属索引中，不以总测试数替代。
