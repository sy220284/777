# 777 底座、聊天与工作模式优化执行方案

日期：2026-10-10。状态：实施中，草稿 PR 持续更新；尚未完成全部七个工作包。

当前批次：

| 工作包 | 当前实现 | 尚需推进 |
|---|---|---|
| A 人物与剧情 | 依赖 #604 的人物档案编辑与常驻背景 | 剧情范围、知识边界分离、单聊/群聊/主动聊天一致验证 |
| B 成果操作 | 运行中心及完整历史精确文件预览；原文件引用继续修改；复用已有分享 | 完成设备回归与真实成果链路验收 |
| C 交付验收 | 保留当前完成声明与待办/团队检查 | 任务级交付要求及真实验证证据关联 |
| D 模式承接 | 目标/公开摘要可编辑；新建 CONTINUATION Work，保留父会话与链路；提交核对源会话 | 结构化材料引用和后续恢复/组合验收 |
| E 记忆 | 隐式追问加入待归并用户内容或公开连续性线索，现有主体/作用域过滤保留 | 对话内定位修正及相关派生状态维护 |
| F 能力状态 | 本批尚未改动 | 按任务派生真实配置、连接与权限状态 |
| G 用量 | 后台状态整理挂到实际处理批次的末用户回合 | 嵌套代理归属复核、前台就近汇总展示 |

上述“当前实现”表示分支已写入代码，验证结果以最终 Head 的单测/构建/设备 CI 为准。A/C/E/F/G 的未完成部分不得计作已交付。

## 1. 基准、证据和交付范围

主线固定为 `8a31b9a61021e7827e2d1575cc708428f122f000`，该提交仅在上一基线 `8d1f10e` 上增加 AGENTS 行为规范。活动分支取本地已获取快照：#604 `b9ce2c94d60254496ce9562726376cb80caa12ca`；#605 `085ece81bb1a02c5994165a22d3e314ae2151177`。开发前重新获取最新 head，本文不能替代动态状态检查。

核对方法：盘点全部模块和生产源码目录（本次索引包含780个 Kotlin/Java生产文件），沿相关符号检索，深入读取 Chat、Work、Shared Session、Memory、Usage、工具、文件、UI及对应测试，并对照活动PR真实差异。该数字表示代码范围索引，不表示780个文件已逐行审计或全部功能实机验收。

目标：人物资料真正影响回复；任务有可用成果与可核验证据；聊天与工作顺畅承接；长期记忆、能力状态、实际消耗可理解。基于当前架构扩展，保留原有合法功能。

实施组织：#604/#605各自完成既有授权范围；先在一个草稿优化 PR 中实现独立链路；相关 PR 合并后重新对照剩余缺口，在同一优化 PR 继续完成七个工作包。工作包是同一PR内的实施顺序，不预先拆成多个PR。不把现有草稿分支误记为主线已交付，不擅自合并或发布。

## 2. 已有能力与计划修正

| 内容 | 当前事实 | 执行动作 |
|---|---|---|
| 人物原作背景 | #604已将作品来源和时间线加入常驻提示，补充世界背景与人设编辑 | 复用；优化关键事实优先级与故事级剧情控制 |
| 世界书 | 支持关键词、常驻、优先级和0～3剧透等级；生成调用仍使用默认0 | 贯通当前人物/故事的允许范围，区分玩家可看与人物可知 |
| 成果 | EventLog成果投影、文件可用性、历史分页和分享已存在 | 补文件精确定位、继续修改及验证关联 |
| 任务真实性 | 完成声明检查待办、目标、团队任务、成员和消息 | 补交付证据；避免一律加审批或全量重跑 |
| 模式承接 | Session已有parentSessionId、lineageId、projectId、handoffSummary，已有进入Work弹层 | 扩展为可编辑任务交接，不新建独立会话系统 |
| 记忆纠正 | MemoryStore和设置控制器已有update/forget，来源引用维护已存在 | 补对话内定位、纠正后再召回与派生状态处理 |
| 用量 | 已有会话/任务/动作/代理汇总，按runId/parentRunId关联 | 复核嵌套代理和聊天后台调用归属，增加就近展示 |
| 工具能力 | Registry、PluginManager、权限、可选工具发现已有 | 派生当前任务可用状态，不新建执行或权限系统 |

## 3. 所有权及实现边界

| 能力 | 权威归属 | 写入与消费 |
|---|---|---|
| 原作档案、剧情进度、人物知识 | ChatFeature | Chat领域命令写入；单聊、群聊、主动聊天和状态归并读取同一投影 |
| 工作目标、交付要求、验证解释 | WorkFeature | 现有Run内更新；运行中心与最终回复消费 |
| 文件字节、预览和分享 | Files能力 | 复用LocalWorkspace及Session文件入口，Work只传文件引用 |
| 成果和检查证据 | 唯一Session EventLog | 从真实工具结果派生；不新建成果数据库 |
| 会话创建、关联、恢复 | Shared Session | 通过SessionLifecyclePort创建和恢复，业务摘要由Feature解释 |
| 长期记忆记录 | MemoryStore | 复用更新、停用、来源维护；Chat自行维护日记和人物状态 |
| 工具可用与执行授权 | 现有Tool/Plugin/Model/Device所有者 | UI消费只读状态；每次真实执行仍经原权限边界 |
| 模型消耗 | TokenUsageDatabase及既有Usage体系 | 返回usage进入唯一账本，任务卡读取派生汇总 |

组合根只装配依赖；Kernel不增加领域逻辑；Feature之间通过提供方公开Port调用；UI不直接操作Store。下文新类型名称属于建议，落地时以最小可复用契约为准。

## 4. 工作包A：人物核心背景与剧情范围

用户效果：普通话题也保持身份，当前剧情进度能控制资料使用，不同故事不互相污染。

定位：`local/chat/CharacterRuntimeProjector.kt`、`ChatTurnRunner.kt`、`CharacterLoreEngine.kt`、`ChatContextState.kt`、`PersonaGalleryModels.kt`、`LocalChatSessionDomainCodec.kt`、`LocalChatSessionRestorer.kt`、`LocalGroupChatTurnExecutor.kt`、`ChatInteractionPersonaPrompt.kt`，以及#604人物编辑入口。

实施：

1. 接入#604新增常驻来源/时间线；按重要性组装身份、当前阶段、关键知识边界。检查现有320/520 Token投影顺序，防止公共说明挤掉人物关键事实。保持共享上下文预算机制，不用提高全局上限解决选取错误。
2. 在Chat已有会话领域状态内增加按稳定人物subject key区分的剧情范围配置，建议包含当前阶段文本和允许剧透等级。人物主档案保留默认阶段；当前故事配置覆盖默认值。群聊逐成员读取自己的有效范围。
3. 复用Session领域序列化和图集故事保存/恢复链。图库故事中的保存状态属于现有快照关系，不建立第二个独立可写进度库。分支回滚、复制人物、导入导出同步检查。
4. 将有效范围显式传入世界书筛选，消除默认0固定路径。未设置时继续安全使用0；仅用户明确调整或有可确认剧情证据时推进，不能从关系数值推断原作进度。
5. 允许剧透与人物知道某事实分别解释：等级只决定资料可被考虑；人物实际知识仍受时间线与knowledgeBoundary约束。不能把高级条目全部直接作为人物亲历。
6. UI在人物/故事设置提供当前阶段、允许范围和作用对象；更改剧情范围不清空共同经历、关系或用户设置。关键词匹配失败时优先结合当前场景和待续话题，不每轮全量世界书。

验收：未提作品名也保持身份；0级不读取高级条目；提高范围后相关条目可用；不同故事/群成员隔离；回滚、重启和往返导出保留配置；无新增常规轮次模型调用。

## 5. 工作包B：成果精确打开与继续修改

用户效果：点击指定成果直接进入该文件，能够分享、再次修改和查看来源。

定位：`local/work/LocalWorkArtifactProjection.kt`、`LocalWorkRuntime.kt`、`LocalWorkHistoryProjection.kt`、`local/session/LocalSessionFilesRuntime.kt`、`local/files/LocalWorkspace.kt`、`ui/screens/local/LocalRunCenterScreen.kt`、`LocalWorkHistorySheet.kt`、`LocalWorkUiContribution.kt`、`LocalWorkspaceFilesDialog.kt`。

实施：

1. 将成果动作从无参数onOpenResults拆出带引用的打开动作，传入sessionId、相对路径和来源事件序号；统一文件页接收目标并复用loadPreview。
2. 打开时使用现有规范路径与存在性核对。文件已删除、权限失效、格式不支持分别反馈；历史记录保留。迟到预览不能打开到另一会话。
3. 继续修改将选定成果引用加入当前工作输入，优先继续原工作会话；原会话忙碌时服从现有排队，失败保留草稿。不把文件全文无条件注入上下文。
4. 复用分享入口、文件预览和完整历史分页；链接用安全外链，修订记录继续按自身类型处理。
5. 不建设完整版本管理平台。需要区分前后文件版本时利用已有文件身份/内容指纹与事件记录，证据关联到实际版本。

验收：两个同名不同目录成果精确打开；早期成果从完整历史可打开；删除/移动后显示失效；修改后检查旧证据有效性；分享实际选中文件；会话切换和返回不串对象。

## 6. 工作包C：按实际任务交付与检查

用户效果：知道完成了哪些要求、检查了什么、哪些仍无法确认，已有成果随时可用。

定位：`local/work/LocalWorkModels.kt`、`LocalWorkState.kt`、`LocalStructuredWorkStateProjection.kt`、`LocalWorkHistoryCheckpoint.kt`、`LocalWorkSessionDomainCodec.kt`、`LocalWorkCompletionClaimGuard.kt`、`LocalWorkToolResultRuntime.kt`、`LocalWorkHistorySummaryStrategy.kt`、共享Tool/Session结果契约及运行中心。

实施：

1. Work在已有目标/计划语义中维护本次交付要求与必要检查项，区分要求、已完成操作和验证证据。能派生的信息不再持久化第二份状态。
2. 以成功tool/result和真实执行身份作为检查证据，关联检查对象、结果、来源事件和适用文件版本；退出码、可读性、测试结果分别按工具实际契约解释。
3. 模型评价可展示为分析意见，不能伪造自动验证成功。文件存在只能证明存在；可打开只能证明可打开；测试通过只能证明覆盖的行为。
4. 扩展现有完成声明机制，仅针对用户要求仍未闭环或证据冲突提示部分完成。适用检查集随任务变化，内容讨论不强制文件检查，局部代码修改不默认全仓重跑。
5. 缺少必要环境时显示未验证及原因，继续交付已有成果。仅客观关键条件阻止宣布完整完成，不普遍阻止正常执行。
6. 将交付要求及检查状态纳入现有Work压缩和恢复语义；重启后从权威事件重建，不能以缓存丢失发布全部通过。

验收：假成功工具、未结束团队、缺失文件不能被标完整交付；无文件的研究任务正常完成；修改文件后旧检查不再代表新版本；部分完成保留成果；检查未运行显示未验证。

## 7. 工作包D：聊天承接为工作任务

用户效果：讨论后的目标、材料和要求可直接进入工作，不用重新解释。

定位：`ui/screens/local/LocalWorkCapabilitySheet.kt`、`LocalHarnessScreen.kt`、`LocalHarnessViewModel.kt`、`local/session/LocalSessionLifecyclePort.kt`、`LocalSessionModels.kt`、`LocalSessionDomainCommand.kt`、`local/LocalSessionLifecycleCoordinator.kt`、`local/chat/LocalChatSessionLifecyclePlanner.kt`、`local/work/LocalWorkSessionLifecyclePlanner.kt`、`harness-core/.../ConversationHandoff.kt`。

实施：

1. 扩展已有进入Work弹层，提供目标、约束、选中消息和附件的交接草稿。默认按近期讨论提取，允许补选较早消息，用户可以编辑后开始。
2. Work拥有接收任务契约，Chat提供选中对话只读投影；Shared Session只负责创建与关联。复用parentSessionId/handoffSummary；显式决定projectId和lineageId，不盲目沿用整条人物故事记忆。
3. 区分同模式继续与跨模式任务交接。跨模式默认创建有来源关联的工作会话，排除人物私有状态；已有记忆kind过滤继续生效。
4. 附件携带真实安全引用，沿现有文件入口验证访问；长材料保留来源可按需读取，摘要省略应可见，不只使用现有最近6条摘要作为完整交接。
5. Session创建接受与实际完成分开反馈。创建完成后再进入发送准入；重复点击不得启动两项任务，失败保留交接稿，重启可回到已创建会话。
6. 完成后原聊天显示可打开的成果引用或返回入口，由已有会话关联消费结果；不自动把工作工具轨迹或私有数据写入角色日记。原会话删除后不影响工作成果使用。

验收：交接包含用户选中旧消息；附件可读；人物私有记忆不被无条件复制；连续点击只有一项任务；创建/发送失败保留输入；来源删除、跨会话及重启后链接行为明确。

## 8. 工作包E：记忆召回与对话内纠正

定位：`local/chat/ChatContextAssembler.kt`中的ChatMemorySelector、`ChatDiaryRecallPolicy.kt`、`LocalChatMemoryRuntime.kt`、`local/memory/MemoryStore.kt`、`MemoryManager.kt`、`MemoryConflictResolver.kt`、`MemorySourceMerge.kt`、`ui/screens/settings/MemorySettingsController.kt`及Chat日记/上下文归并入口。

实施：

1. 首先形成匿名真实风格样例：显式回忆、代词指代、约定、共同物件、换词表达、切话题和相似无关事件。记录应召回记录与不应召回记录。
2. 保留已有关键词检索及两阶段排序，补当前连续性线索与别名候选。优先修漏召回和误召回，不直接全量搜索或增加额外模型调用。
3. 对话内提供定位记忆来源和纠正入口，调用原有update/forget；不凭单个否定词删除全部历史。用户明确纠正优先处理，保留来源和必要历史追溯。
4. 检查记录修正后的派生人物事实、日记摘要、待归并回合和历史压缩是否仍重复旧事实，由各Owner负责更新或失效；新用户纠正覆盖派生旧结论，原始对话事实不篡改。
5. 验证图集/人物/故事/群聊受众隔离。只有现有检索在样例上仍不足且能证明收益时，再决定语义检索扩展；这是实现选择关口，不提前部署向量数据库。

验收：换词找旧事；无关相似话题不误召回；纠正与停用后不再当有效事实；重启与分支回滚语义正确；长规模记录检索耗时与额外消耗有实测。

## 9. 工作包F：当前任务的能力状态

定位：`harness-core/.../tools/ToolRegistry.kt`、`plugin/PluginRegistry.kt`、`PluginManager.kt`、`local/tools/LocalToolPolicy.kt`、`LocalToolCapabilityIntent.kt`、模型能力与设备权限现有投影、`ui/screens/tools/ToolsScreen.kt`、`PluginInventoryBrowser.kt`、`LocalComposerCapabilityControls.kt`。

实施：从已有真实状态派生“可直接使用、需要连接、需要系统权限、当前环境不支持、状态尚未确认”。具体原因及下一步来自现有Owner；检查只覆盖任务实际依赖，不每次扫描全部工具或发网络探测。可用与已授权分别展示，列表状态不提前承诺下一次调用必成功。断连/撤权/更换模型后更新投影；执行时仍使用原权限与能力校验。失败回到配置入口，保留原任务。

验收：工具已注册但账号未连接正确显示；设备权限拒绝后可恢复；状态变化不会误启任务；未准备好的一项不阻断无关任务；计划模式和只读代理能力保持原边界。

## 10. 工作包G：任务消耗就近展示

定位：`local/TokenUsageAnalytics.kt`、`local/usage/TokenUsageDatabase.kt`、`LocalTokenUsageContextBridge.kt`、`local/chat/LocalChatContextRefreshCoordinator.kt`、回复修订计量、Work主/子代理计量、`ui/screens/settings/UsageCalculationPage.kt`与运行中心。

实施：

1. 保留现有任务groupDetail与requestId去重；核对主任务、团队、嵌套子代理和续轮的归属是否完整，发现实际缺口再扩展关联字段。
2. 聊天回复、修订、状态刷新按原始turn及冻结模型路由归属；异步完成不能读取当前前台会话错误记账。
3. 就近展示本轮/本任务已知总用量及主回复、后台整理、助手分解，链接原用量详情。迟到usage增量更新，不重复加账。
4. 失败/取消/断流且无usage的请求只提示数据缺失，不记为0消费或推测扣费；多供应商价格不可直接当统一套餐扣费。
5. 保留用户已配置的模型表现、温度和推理上限。任务方式快捷项只作为后续可选界面，不在本次默认覆盖参数或添加新限制。

验收：一次请求仅计一次；子任务归主任务且总额一致；异步聊天整理不串会话；未知usage不伪造数字；历史详情和就近展示同源。

## 11. 同一PR内部实施与验收顺序

| 步骤 | 产出 | 前置条件 |
|---|---|---|
| 0 | 重新获取main/#604/#605，确认合并和CI，核对最终差异 | 当前活动开发完成；不复制草稿实现 |
| 1 | 锁定剧情范围、成果动作、交付要求、交接输入的最小契约与唯一Owner | 读取最新架构及实际装配 |
| 2 | A：人物背景和剧情范围全链路 | #604人设编辑基础 |
| 3 | B/C：成果定位、交付与检查证据 | 单一EventLog、原文件入口 |
| 4 | D：跨模式任务交接 | #605操作准入、B/C成果能力 |
| 5 | E：召回与纠正闭环 | A剧情范围及现有Memory写入口 |
| 6 | F/G：能力投影和任务用量展示 | 现有Registry及Usage归属复核 |
| 7 | 全部组合回归、文档/功能树/直接证据索引同步 | 最终实现冻结 |

验证：沿用docs/VALIDATION.md当前风险分流。本计划涉及跨Feature/Session/恢复底层，组合变更按L2执行架构、静态、核心/Android模块单测、ARM64/x86构建及Android16/17相关设备测试，最终Head检查绑定。无新增代码时不伪造测试结果。

优先扩展现有CharacterLifeRuntimeV3Test、CharacterLoreEngineTest、MemoryStoreTest、ChatMemoryBindingTest、LocalWorkArtifactProjectionTest、LocalWorkArtifactAvailabilityTest、LocalWorkCompletionClaimGuardTest、LocalWorkHistoryProjectionTest、ConversationHandoffTest、LocalSessionLifecyclePolicyTest、TokenUsageAnalyticsTest；对应Compose/Android测试验证真实点击、返回、保存与重启。新增测试以真实行为、状态和恢复场景为依据，不通过新增字面串门禁自证实现必要性。

最小横向矩阵：单聊/群聊/主动聊天、前台/后台/自动化、主代理/团队/子代理、独立/项目/接续会话、重生成/编辑/回滚；只对每工作包实际相关路径执行。纵向覆盖输入→接单→执行→落盘→恢复→用户结果。

真实模型质量另做短聊/长聊、同义指代、人物原设、知识越界、剧情阶段及记忆纠正样例。确定性测试证明契约，真实对话证明效果；套餐和物理设备专项不冒充仓库CI覆盖。性能记录实际请求构成、额外调用、首次反馈时间、检索耗时和长文件打开耗时；没有测量前不承诺百分比收益。

## 12. 范围与完成条件

本轮保留原有活人感、行为调节、模型表现、排队、审批、项目规则、团队只读权限及未知副作用恢复保护；不恢复远程控制，不新建记忆/会话/成果/权限/计费平行系统，不新增云服务，不自动改写用户模型参数。

完成须同时满足：七工作包用户路径成立；相关历史数据能读取和恢复；每项新写入有唯一Owner；成果和检查指向真实对象；正常/失败/取消/恢复均有反馈；现有合法功能回归通过；最终组合CI通过；经授权合并后复查main。尚缺实机或真实账户证据时逐项注明，不能宣称全体验验收。

建议PR标题：完善人物剧情一致性、工作成果交付与聊天任务承接。描述覆盖最终七工作包及真实验证结果，使用中文。

## 13. 权威链接

- [当前基准主线](https://github.com/sy220284/777/tree/8a31b9a61021e7827e2d1575cc708428f122f000)
- [人设卡PR #604](https://github.com/sy220284/777/pull/604)
- [操作一致性PR #605](https://github.com/sy220284/777/pull/605)
- [架构](https://github.com/sy220284/777/blob/8a31b9a61021e7827e2d1575cc708428f122f000/docs/ARCHITECTURE.md)
- [验证规则](https://github.com/sy220284/777/blob/8a31b9a61021e7827e2d1575cc708428f122f000/docs/VALIDATION.md)
- [功能归属](https://github.com/sy220284/777/blob/8a31b9a61021e7827e2d1575cc708428f122f000/docs/FEATURE-OWNERSHIP.zh-CN.md)

本文为执行准备，不表示代码已实现、PR已创建、CI已通过或应用已发布。
