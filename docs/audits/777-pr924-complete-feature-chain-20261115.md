# PR #924：架构 3.0 全功能与端到端链路复核

## 代码与证据基线

审计对象为 `sy220284/777`、PR #924，起始 HEAD `586d9e534da8fcc8fd127c2271afe402d1315c8c`。本地分析已读取该 HEAD 对应 GitHub Actions 的全仓 `callgraph.json` 和 `callgraph.md`，并逐段核查相关生产源码与测试。单次静态调用关系扫描覆盖 2,337 个受版本控制的文件，其中生产源码 1,152、测试 719、支撑源码 81、非代码资源 385；识别静态 import 边 5,452 条、词法调用候选 6,558 条。显式 import 强连通环为 0；4 条跨产品 Feature 边指向 Automation 调用 Chat/Work 公开 Port；112 条所谓未解析导入均是 Android 构建生成的 R / BuildConfig。271 个静态无显式引用候选需结合 Android Manifest、依赖注入、反射与 Kotlin 顶层函数判断，不能直接删除。该 HEAD 的 6 项 CI 在调整前已全部通过。

## 功能链路反向审核

| 域 | 入口 → 执行责任 → 完成事实 → UI 反馈 | 主要验收与结论 |
|---|---|---|
| Shell | 启动/导航/通知 → FeatureCatalog / Navigation Port → 当前会话与路由投影 | Shell 仅切页面，恢复由 Session/Feature Owner 执行。检查新会话、模式切换、Back 与冷启动状态；未发现需新建第二套任务状态的证据 |
| Chat | 输入/附件/群聊/重发/主动消息 → Chat Turn Owner → Session EventLog → 消息与人物连续性 | 会话内串行执行、排队和取消原有契约保留。发现模型历史中多模态消息的文本未参与下一轮工具意图识别，修正跨 Chat/Work 使用的用户输入投影 |
| Work | Goal/计划/工具/技能/长文档/归档 → Work Run → ToolRegistry → EventLog/Goal → 运行进度 | 已有工具聚焦与 Code Mode 改造继续保留；新增修复多模态任务意图、长分支来源证据冲突、ZIP 内成员身份与读完后的游标误报 |
| Automation | 用户明确建立计划 → Scheduler/Worker → Chat/Work Port → 持久结算与通知 | 现有代次/恢复/人工授权链路与跨 Feature Port 相符。普通“任务”内容仍不会自动创建定时任务；保留 Android 16 后台任务额度边界 |
| Tools | 能力注册 → 任务预选/精确选择 → 冻结模型工具表 → 子工具授权执行 → 回执 | 新增 `read_chunk` 明确 `complete`/`next_byte`；此前已解除 128 个工具名固定拒绝及 18k 技能截断，保留模型真实上下文预算和逐工具审批 |
| Settings/设备权限 | 按需入口 → Android 实时授权/服务连接探测 → 状态投影 → 缺失权限恢复 | 无障碍权限与通知访问的“授权”和“已连接”分离；系统授权和监听器 rebind 依官方 API。未发现以 UI 文本替代系统实际授权的入口 |
| 共享 Session/Memory/Model | Session Owner/索引、历史与回放；模型路由、资源与用量单一账本 | 跨会话历史搜索此前扫描最多 64 个授权会话却没有 `cursor`，用户无法找后续内容；新增有权限隔离的稳定分页并保证游标不泄露其他项目会话 ID |

## 本次集中修改的根因与实现

1. **跨会话搜索的“前 64 条即结束”**：原 `session_search` 只检查每轮前 64 个允许的会话，提示缩小搜索却没有操作入口。新增可续查 `cursor`，按稳定会话 ID 遍历全部**当前项目/会话链**中的授权会话；`next_cursor` 只包含授权会话 ID。摘要使用一次索引而不在每个条目中重复线性查找。测试覆盖 130 个会话、后段命中和跨项目隔离。
2. **多模态任务意图丢失**：原 `LocalToolCapabilityIntent` 只读取字符串 `content`，对“文字 + 附件”的 `JsonArray` 会错误退回上一个任务，可能预选旧 GitHub/MCP 工具。统一读取 `text/input_text` 块；仅媒体消息也保留新任务边界。测试涵盖当前新任务排除旧 GitHub，以及多模态“继续”任务。
3. **调查证据错误合并**：来源显示名将 Git 引用截断为 48 字符、整段显示截断为 160 字符；此前还以该显示名当作证据身份。增加基于原始仓库/完整引用/路径/URL/归档成员等字段的来源键，界面预览与身份分离；已有事件记录可通过旧显示名继续读取。测试覆盖共享长前缀的不同 Git 分支。
4. **文件末尾仍反复提示继续**：`read_chunk` 无论是否已到 EOF 都打印 `next_byte`；调查投影又将任何 `next_cursor`（包括 `none`）视作未完成。现在字节页明确标注 `complete=true/false`，从同一次文件句柄读取总长与字节页，末尾页（包括恰好填满的最后一页）直接标记 `complete=true`；调查只在存在有效游标且未完成时提示续读。归档清单/成员阅读也纳入调查覆盖，归档成员与普通文件分开计入进度。测试覆盖归档清单跨页与结束、分块读准确 EOF、归档成员结束，以及文件大小恰好是页长整数倍的情况。

## 集中验收范围与全仓结论

- **静态扫描覆盖率**：已核对仓库权威文档与 2,337 个版本控制文件的编译依赖候选及模块分布；CI 产物的实际 checkout 提交为 `e7b2aeae7c572e8841c24df771c9ac7519bbd13d`（PR 触发的合并测试提交），与 PR 源分支 SHA 不同。该图提供架构边界和调用关系候选，运行行为以源码/回归结果校正。
- **Feature/Shared 所有权**：Chat、Work、Automation 通过各自 Port 协作；Tools/Session/Model 事实源不另设副本；Shell 仅负责导航与展示。四个跨 Feature 引用均为提供方的对外边界，静态 import 环为 0。实际并发和恢复状态另以 JVM/设备回归为依据。
- **完整操作链**：发送/队列/取消、目标/待办/计划/工具、权限/审批、自动任务/Worker、会话/记忆/历史、MCP/扩展工具、模型请求/结果、文件/归档/编辑、UI/恢复均按“入口→授权→单一 Owner→持久事实→前台状态→失败恢复”检查；本轮直接修改的四个根因均增加跨入口测试。
- **原有保护边界**：会话范围隔离、文件工作区范围、外部操作审批、身份冻结与未知副作用对账保留；普通合法任务通过明确分页和实时事实校验继续完成。
- **回归口径**：工具契约与 JVM/Android UI/SQLite 六类 CI 必须对应本次最终 HEAD；不同设备厂商权限及第三方真实服务的运行结果以该设备或账号实际授权为准。

## 真实保护与用户体验权衡

应保留真实身份、权限、审批、模型请求冻结表、持久 EventLog、Session 租约、未知副作用的人工对账和 Android 官方前台/后台调度规则。待移除的是影响合法操作的隐藏常量、无后续动作的错误提示、用显示文本作为事实身份的做法、静默截断以及重复状态所有者。本轮修改不增加定时任务、子代理或云端数据存储。

## 验收口径

本次修改在工作区中统一准备、审计并集中提交到 PR 分支。已在本地对全仓调用关系审计产物进行完整性/边界断言，结果通过。新增和扩展的真实应用 JVM 回归覆盖上述四类根因。合入本次集中变更后，以**最终 HEAD** 的架构扫描、app 与 harness-core JVM 单测、Android 编译、Chat/Work Android 设备交互、市场插件和本地会话迁移 6 类 CI 回执为准；CI 通过也不替代特定设备的真实厂商权限和第三方模型服务测试。

依据：仓库 `docs/ARCHITECTURE.md`、`docs/SYSTEM-AUDIT-GUIDE.zh-CN.md`、`docs/UI-UX.zh-CN.md`；Android [后台任务限制](https://developer.android.com/develop/background-work/services/fgs/changes) 与 [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)；Kotlin [Coroutine cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html)。

## 本轮继续优化：文件分页、调查续接及 Work 工具焦点（2026-11-15）

在原先全仓调用关系与六项 CI 基线基础上，发现并统一修正三组实际跨模块障碍：

- `glob/list_files/grep` 已提供可继续调用的 `cursor`，Work 调查上下文却只解析 `next_cursor`，导致有未读页面时不产生后续待办。文件工具在原参数提示后新增 `next_cursor`；Work 只从分页提示读取游标，末页清除继续建议。测试直接把真实工作区的跨页输出送入 Work 证据投影。
- `list_files` 对显式深度静默截断为 8，`glob/grep` 的递归遍历上限固定为 32；改为支持真实深层目录，保留沙箱、分页、单页时间、输出大小控制。测试覆盖 36 层目录和已有 400 项以上分页。
- 自然语言“继续在本地完成”被当作新任务，清除 Work 中用户主动 `select:` 的精确工具选择；同时 GitHub 意图只认少数固定“继续”短语。共享中立续接判断，使原任务继续保留工具选择，明确新任务则恢复自动发现。保留每次真实工具审批与模型工具冻结检查。

另将已受“最近五个来源、最多四条发现”约束的调查摘要取消最后 2400 字符的无提示截断，避免摘要中后部发现被裁掉。此节记录代码层变更；整体通过状态以本组新提交 HEAD 的六项 CI 和真实运行回执为准。

同源解析补充：归档工具的无下一页标记 `next_cursor=none` 后接英文分号或方括号时，也应视为完成；修正游标值词法边界并加入两种结尾回归。本地 Kotlin 规则验证覆盖 8 组游标及误匹配样例，全部通过。

同源审计补充：Work 的 GitHub 来源身份此前只使用仓库/路径/分支，`fetch_pr` 和 `fetch_pr_file_patch` 的 `pr_number` 未进入事实键；同仓库不同 PR（甚至不同 PR 中同路径补丁）会合并为同一份来源。现以仓库 + PR 编号建立独立资源身份，完整来源键同时纳入 `pr_number` / `issue_number`，界面展示可区分 `owner/repo#PR924` 与 `owner/repo#PR925`。测试覆盖 PR 文档、同路径补丁、调查进度与身份去重。

CI 回归复核：此前一次执行的 3569 项 JVM 测试中有 2 项失败，已定位到 Work 的 `prepare()` 在保留精确工具选择后，又调用共享工具预激活重排并清除 `exactFocus`；现在对仍处于用户精确选择状态的 Work 任务直接沿用原模型工具集，仅新任务触发重新预激活。归档游标字符边界测试从带状态事件记录的综合用例中独立，直接测试纯解析行为，消除回归测试对无关事件写入顺序的依赖。


## 基于 PR #924 完整快照的纵向复核与结构性修复（2026-11-15）

### 固定证据与验收方式

- 本轮以 PR 的 `84b364b47dd926f8bb78ecdd7f64f985a3598bb2` 提交为原始快照，借助仅执行一次的 GitHub Actions `git ls-files` 导出、在本地展开，完整覆盖 **2,339 个受跟踪文件**。导出流程已清理，避免后续持续运行。
- 本地实际执行 `tools/audit/scan-callgraph.py`、`.github/scripts/check-local-architecture-boundaries.py`、`.github/scripts/check-local-performance-invariants.py`、`git diff --check`。首次静态扫描统计：生产源码 **1,152**、测试源码 **719**、显式导入 **5,454**、调用候选 **6,564**；显式 import 环 **0**，四条跨 Feature 引用走 Automation 调用 Chat/Work 发布的 Port；边界与性能规则 **PASS**。静态扫描仅证明依赖边界，不把调用候选当作执行成功。
- PR 分支并行提交会触发 workflow `cancel-in-progress`，因此固定 `fb8cb6c3b2d2505f81f50a15211574445d70deeb` 另起独立验收 run **43708938236**，含全部应用和核心 JVM 单测、Android 调试和测试包编译，以及 Android 36 上 Chat/Work、SQLite 会话迁移、插件市场/凭据/OAuth/Worker 四项验证任务。此验收作业无“新提交即取消”设置，临时流程创建后已删除，后续状态以实际工作流回执为准。

### 功能链路与所有权复核

| 产品域 | 已按源码复查的完整路径 | 变更或保留结论 |
| --- | --- | --- |
| Shell/Settings | 路由/权限入口 → Feature Port → Android 授权状态与服务连接事实 → 界面状态 | 启动路由、用户授权及监听连接保持各自的单一事实源；真实系统许可与用户明确同意仍作为执行边界 |
| Chat | 用户输入、附件、角色/群聊 → Turn Owner → Model/Tool → Session EventLog → 消息、任务与回显 | 多模态意图沿用完整文本；身份/历史数据和消息完成事实由 Session 管理，避免根据显示文案重建状态 |
| Work | 创建目标/细纲/待办 → ToolRegistry → 工具审批与执行 → Goal/Session EventLog → 检查点、进度和结果 | 修复长目标截断、文件搜索不完整、跨 PR 证据混同、长任务冷恢复输入丢失、检查点重复存储、完成结果截断、恢复错误静默替换等问题 |
| Automation | 用户明确确认计划 → 解析事件与执行提示 → Scheduler/Worker → 持久结果/回执 | 完整保留用户事件要求和模型生成的执行提示；保留明确授权、系统实际调度边界和重放副作用核验；未创建任何新的定时任务 |
| Tools/扩展 | 注册契约 → 工具发现、模型可见表 → 实际参数验证与授权 → 原始工具结果 → Work 证据 | GitHub PR、PR patch、Diff、Issue、Commit、Blob、Git 文件读取得到不同的事实身份；访问权限/参数契约仍由原注册表执行 |
| Shared Files/Session/Model | 本地工作区/历史检索 → 完整遍历、关联排序、游标续查 → 保存/读取 → UI 进度 | 消除工作区搜索固定五万扫描、五秒与一万匹配的误判；明确限量查询使用有界堆；改为搜索快照+页游标，并阻断过期查询覆盖新结果 |

### 根因、修改点和防回归

1. **来源身份漏维度**：`local/work/LocalWorkInvestigationRuntime.kt` 的来源分类此前缺 GitHub Diff、Commit、PR Patch、Issue、Blob 等实际读取工具，对身份缺少 Commit SHA / Blob SHA / 比较基线等字段。补充来源键和界面上可区分的标签，保持已有 `#PR924` 展示形式；`LocalWorkInvestigationRuntimeTest` 复查不同 PR 的同路径补丁不会合并。
2. **长任务冷恢复丢内容、日志反复膨胀**：`local/runtime/LocalAgentRunCoordinator.kt` 的原始工作说明只保存前 8,000 字符，并在每次检查点重复写入。调整为 `agent/run-input` 一次性保存完整任务/记忆输入，版本 3 的运行检查点仅保存实际进度；历史版本按原记录恢复，缺失新版本输入时明确报告 `RECOVERY_INPUT_MISSING`，防止猜测下一步。`LocalAgentRunCoordinatorTest` 覆盖超过原阈值的任务、跨进程恢复、缺失输入。
3. **后台完成结果与恢复上下文被替换**：完成文本此前 `take(20_000)`，丢掉后半成果；恢复上下文创建失败还会退回通用指令继续。现在终局回执保存完整结果，恢复上下文异常报告 `RECOVERY_CONTEXT_FAILED` 并保留原工作，测试包括长结果与故障注入。
4. **文件浏览器搜索称“已完成”却遗漏结果**：`local/files/LocalWorkspace.kt` 原文件名搜索最多扫描 50,000 项、5 秒，并将最多 10,000 条结果作为最终全量。改为遍历可访问工作区真实文件，再统一相关性排序和稳定分页；明确限量列表使用 `PriorityQueue` 保留 Top-K，避免全量留存；使用同查询世代 ticket 避免旧扫描覆盖刷新结果、允许取消挂起查询。新增 `LocalWorkspaceTest` 10,024 文件、128 条一页的完整结果/唯一性/末页回归。
5. **目标与定时事件规则静默截断**：`LocalWorkProgressCoordinator.createGoal`、`resolveWorkGoalUpdate` 取消 2,000 字符丢失；`AutomationPlanningService` 在传给模型之前与解析模型输出之后都不截断事件内容。对长目标、事件提示和持久回放增加断言，用户要求与执行规则保持字面一致。

**边界原则**：改动针对合法任务的可达性、完整性、事实一致性、执行效率；文件工作区范围、模型真实上下文预算、设备/插件授权、跨项目隔离、外部写入审批与未确定副作用的人工核验仍保留。验收应核对原始源码提交、真实单测与设备回执；不得把静态调用图、工具返回的部分页面或原工作流被取消的状态写成运行通过。


### 调度语义补查：消除误执行时间（追加）

在 `AutomationPlanningService.parseAutomationPlan` 中，发现 ONCE/DAILY/WEEKLY/MONTHLY/INTERVAL 的 `firstRunAtMillis` 缺失时会自动变成当前时间加一分钟，过去时刻也会被 `coerceAtLeast` 强制挪到一分钟后；INTERVAL 的间隔缺失、SILENCE 的沉默时长缺失均可能未经用户选择而设为 60 分钟，WINDOW 的大整数转 `Int` 还可能溢出成合法分钟数。此类隐式纠正会改变任务的真实执行时刻和频率。

现按安排类型逐项验证：固定日历触发必须有明确且有效的未来时间；间隔与沉默触发时长必须有明确的分钟数并符合支持范围；时间窗先校验原始 Long 的 0–1439 范围再转换为 Int。触发条件不完整时返回具体错误，保持上游计划原文供用户修正，避免凭空创建错误时间的事件。回归包括五种缺首次运行时间/过期时间、间隔缺失或不足、沉默时长缺失或不足、窗口超大整数溢出和正常窗口。


### 检查点识别与真实用户意图（本轮补查）

此前工具预选通过检查用户文本是否含有 `<work-checkpoint>`、`<compacted-summary>`、`<chat-continuity>` 决定跳过消息。用户提供代码、日志或文档时正常引用这些标签，会被误认作系统历史压缩块，导致最新需求丢失、旧工具意图重新生效。现改为直接消费已有 `LocalContextCheckpointEnvelope` 的可信来源字段（包含旧版来源标记），仅跳过实际由系统生成的检查点。保持原始用户文字及多模态内容完整；新增可信 Work/Chat 检查点、旧 GitHub 任务与真实用户标签引用的对照测试。来源归属仍为 Context/Model 既有契约，不新增自制标记表或额外运行状态。

### 最终独立快照验收及临时工作流清理

固定快照 `2d9fd9b4916c4b45d5c2b13c9ae0eb8c941927bf` 的独立验收工作流 [43711158185](https://github.com/sy220284/777/actions/runs/43711158185) 已返回四组 `completed/success`：
- 完整核心库、MCP、设备能力及 App JVM 单测，Android 测试包和调试 APK；
- Android 36 SQLite 会话迁移与搜索；
- Android 36 Chat/Work、设备权限及 Agent 团队交互；
- Android 36 扩展市场、凭据与授权回调交互。

其中 Hilt `WorkReadUiPort` 重复绑定已在 `b0f00409` 消除：保留 Work Feature 中唯一 `@Binds`，删除组合根重复的 `@Provides`。固定快照通过证明此前 Dagger 编译失败已修复。本节记录的独立快照验收不覆盖后续对 Automation UI 的提交；最终 PR 交付仍以清理临时工作流后当前 HEAD 的六项标准 GitHub Actions 完成结果为准。

用于本次证据补采的临时工作流已从 PR 分支退出，避免将一次性验证路径留作常驻生产配置。
