# UI 3.0-R4 实施台账

> **2026-10-15 第二批 S1/S2/S4 推进（PR #705，仍待最新 SHA CI）**：`e8e5cf06` 将编辑消息的 Owner 11 种结果完整投影为独立 `UiMessageEditOutcome`，同时调整 Chat/Work Port/Next 消费路径并加映射单测，避免 Next 在回执层直接依赖旧 Session 枚举；`1dbb0d87` 增补 Next Compose 审批按钮→真实 Work Port→模拟 Owner nonce 的拒绝/不可重复操作回归测试，但**非真实供应商或 V6+Next 完整端到端证明**；`eaf7c4a8` 修复 Next 动态卡片把 Owner 拒绝和失败错误展示成“已提交”的误导反馈，改为区分受理、排队、拒绝、失败、未知结果。当前 **75/173** 动作有源码映射，**173/173** 仍待完整恢复验收，尚缺正式全应用两包 Renderer/Host、动态媒体、Work 自动发布。此前 `#192a4ceb` 的失败已经在历史 CI 中被后续通过覆盖，当前 SHA 不沿用历史通过回执。


> **2026-10-15 S1/S3 继续解耦与 Host 竞态修复（PR #705）**：`976f8e02` 修正 Host 的 A→B→A ABA 迟到回调问题：增加保存的导航提交代次，对同快照不同代次的旧页面导航提案拒绝，增加 JVM 回归测试；`8565f9a3` 将 Next 历史分页接入 `UiMessageHistoryPort`，仅输出 `UiMessageSnapshot`，在 Session Owner 内将旧消息投影为中立角色/身份/时间/内容；V6 仍使用兼容的 `TranscriptUiPort`，不损坏原始历史修复元数据；`805a1740` 增加 5 种角色投影单测。此批不是完整全应用 Host/双包验收，旧类型仍出现在 V6 兼容接口、`UiCommandReceipt.MessageEdited` 等迁移边界，不能宣称全部已隔离。S0 源码映射维持 **75/173**，E2E 恢复证据仍 **0/173**，CI 必须以最新 SHA 独立验证。


> **2026-10-15 审计修复与 S0 下一批推进（PR #705）**：`192c70ee` 将本地动态文档生成改为两阶段：捕获会话身份、消息与审批后释放前台锁，Work 历史/工具结果读取、文档装配、Codec 校验在锁外进行；持久化前重新锁定并验证身份/模式/原消息内容，由原 Session Owner CAS 复核文档版本，不降低付费/审批/来源准入。仍保留最终受锁保护的 Owner 写入以免切会话时提交错位置。场景矩阵本批补 10 个真实 Port/命令映射，**65 → 75/173**，剩余 **98**；所有动作仍是 `PENDING_EVIDENCE`，未跑双包 E2E。另发现 `TranscriptUiPort` 仍泄露 `LocalHarnessMessage` 旧领域类型，后续必须以不破坏 V6 历史修复元数据为前提迁移。最新主线同步为 `73a9abdc` / #721，本批每个新提交的 CI 都要另行核查。


> **2026-10-15 #705 S3 Host 增量与 #719 主线同步（未宣称完整交付）**：`2caf6bda` 新增整包准入后的 Compose Host 场景渲染与系统返回状态提交；`6394593e` 修复向上返回时未使用已绑定回调的问题。当前为**具备准入前提的 Host 组件**，尚没有 V6/Next 完整 78 Scene Renderer，亦未接入生产 AppRoot，S3-02 继续 `CODE_PARTIAL`。`1b3b7d8c` 双父同步主线 `main@2db279be`（#719），10 处交叉改动使用共同基线合并，旧 App 中已迁移的 `ShenyanSections.kt` 保持删除，新版视觉实现写入 `:ui-pack-v6`。本轮最新提交的 CI、真实双包/设备验收必须单独验证，不能沿用旧 CI。

> **2026-10-15 S3-02 导航恢复边界续做（待最新 SHA CI）**：增加 Host 场景导航栈预算、非法覆盖顺序/重复根页面拒绝、回根清栈与返回同页关闭弹层；新增独立版本化导航快照 Codec、恢复准入入口及 JVM 回归源码。Host 恢复始终重新验证当前包与 Scene 目录，不允许过期 UI 包转到 V6。当前只实现安全的导航数据与状态转换，未接入 7 路由 AppRoot、持久化/系统深链或全应用 Scene Renderer；S3-02 继续为 `CODE_PARTIAL`。最新编译/设备结果须以新 SHA CI 为准。

> **2026-10-15 S3 导航契约增量（待本提交 CI）**：新增 `ui-host/UiHostSceneNavigation.kt` 的 Host 所有权路由状态机及 App 单测，按当前 Pack manifest 和 Host 的 Scene 目录校验导航、返回、显式深链及恢复，不允许将 hostProvided 系统服务、SLOT、未覆盖页面或上一 UI 包的历史栈伪装成可渲染页面。S3-02 从 CODE_PENDING 调整为 CODE_PARTIAL；**全应用 UI Host、正式 Renderer、真实页面导航接线、跨包持久恢复仍未实现**。上一提交 `53e77262` 的 [CI #38668711715](https://github.com/sy220284/777/actions/runs/38668711715) 基础构建/单测/Android 16/17 通过，但不是 R4 双包交付验收，新增提交另行验证。

> **2026-10-15 主线与 CI 复核（未验收）**：`main@cd0edcff` 的 V6.3 组件/图标与 #718 菜单、弹层、设置触控改动已经合入 #705 的 `192a4ceb`；无待合入的主线祖先提交。依据 [R4 第 13.7 节](design/ui3/PLAN-R4.zh-CN.md#137-2026-10-15按最新主线修订-r4-交付基线pr-705) 更新后续工作及视觉基线，旧 9 路由/38 屏非当前验收。**CI #38589559423 已失败**：x86 AndroidTest 编译有无效 `onNode` 导入、旧 `LocalSkillUiEntry` 类型；ARM64、JVM、静态与架构门禁通过；Android16/17 被共享测试制品阻断。修复源码与重新取得最终 SHA 回执前不能进入完成验收。

> **主线同步后 S0 增量（2026-10-14，PR #705）**：根据真实 Chat/Work/Automation/Media/UiDocument Port 源码补充 21 个已存在命令的静态映射，现为 **65/173**；其余 **108** 项待映射。全部动作的恢复测试和端到端验收仍标 `PENDING_EVIDENCE`，本次不凭接口名称宣称用户旅程已经闭环。

执行依据：[R4 完整方案](design/ui3/PLAN-R4.zh-CN.md)。权威规范：[UI 3.0](design/UI-3.0.zh-CN.md)。当前分支 `feat/ui-r4-pluggable` 已组合主线 `cd0edcff`（PR #705 的 `192a4ceb` 双父合并），截至该审查点落后主线 0 个提交，PR 仍为 Draft。此台账记录实际实现，不能将方案描述、渲染夹具或 CI 通过当作全方案完成。

> **最新分阶段执行记录（2026-10-14）：** [S0–S5 原阶段执行快照；当前映射已更新为 65/173](design/ui3/R4-STAGE-EXECUTION-20261014.zh-CN.md)。该记录区分此前已有能力与本轮实际提交，不将测试源码等同于通过回执。

新增提交已完成增量源码审计：[120 个差异路径与结论](design/ui3/R4-POST-COMMIT-AUDIT-20261014.zh-CN.md)。本轮修正当前定义准入、Next 纵向操作累积、设计生成器旧路径及离线演示继承键错误；未执行验证，不以审计代替 R4 交付。

整个方案的代码缺口统一见 [R4 全量代码任务清单](design/ui3/R4-CODE-TASKS.json)，区分代码已实现但未验证、部分实现、未实现和按用户要求延后的验证；不再只追单一功能。

本轮进一步推进 S1 图库和 S4 结构化结果适配。图库公开投影仅含稳定人物/故事 ID、标题、备注和消息数量，不公开私有 Chat 连续性状态。归档写入沿用原 Store、恢复和排除日志；取消传播，失败不保证没有部分提交。工具结果只从 Work 的实际事件读取，字段不获得工具、导航或审批能力；预算超限沿用原文档回退，不静默裁剪。以上均未编译或运行验证。

本批人物选择/开始故事通过 `PersonaSelectionUiPort` 进入实际 Chat Owner；V6 捕获画面身份，Next 读取真实可用原因。异步人物保存后在原 Shared 前台锁内检查激活身份并提交，过期结果回滚原人物保存，旧激活错误不会污染当前画面。人物导入导出编排迁到 `LocalPersonaTransferOwner`，沿用原图库/日记事务，删除 UI 私有传输协调器及 facade 旧转发。Next 经 Host 的 OpenDocument/受限读取/CreateDocument 完成原 JSON/Markdown/Word 传输；文档写入失败不称成功，进程重建丢失的导出 payload 明确要求重新导出。此批未编译、运行测试或跟进 CI；完整编辑、保存/删除/立绘以及全应用 Host 仍待实现。

本轮收尾图库故事编辑和删除业务切片：新增 `PersonaStoryUiPort` / `PersonaGalleryDeletionUiPort`，原 Chat Owner 处理实际备注/阶段保存、故事/人物删除和原绑定/成员联动；V6 控制器、VM 与 facade 的旧编排退出，Next 原生编辑、阶段选择和确认删除接入同一 Owner。归档 IO 后再次校验捕获激活身份；会话同步读取最新真实阶段，恢复了相同故事/人物时不解绑新引用。立绘清理读取实际已删条目并保护仍被引用的文件。Typed Saved/Deleted 区分同步 APPLIED/NOT_BOUND/DEFERRED，已提交但同步/刷新失败仍通知已保存，不诱导重复删除；未知 IO 失败仍要求刷新确认。此批代码未编译或运行，完整图库保存/人物编辑/立绘设置仍未收尾。

本批进一步收尾图库保存与立绘设置/移除：`PersonaGallerySaveUiPort` 在原 Chat Owner 冻结来源并取得原 Session 维护租约，读取原完整历史及原抑制边界；IO 后检查来源，保存后仅在相同激活中尝试绑定，已保存但绑定延后明确通知。`PersonaPortraitUiPort` 由 Chat 管理图片读取/解码/实际 Store 写入，沿用 20MB 限制（预置安装复用同一常量）。V6 旧 UI 编排删除，Next 原生保存/新故事/立绘导入/确认移除接线；文件选择冻结图库 ID，恢复后丢失目标不重定向。实际替换前路径由原 Store 锁内捕获，失败/取消的临时图片清理保护任何已提交或仍被引用的文件，清理失败有可见回执或日志。本批未编译/测试；人物资料、新建与预置安装、整页与预览移包仍待实现。

## 阶段状态

| 阶段 | 当前实现 | 尚未完成 |
|---|---|---|
| S-1 | MCP 未检测健康使用中立标识；Chat/群聊显示真实共享队列；Work 审批使用 Owner 签发的实际等待身份 | 实时 MCP 选中/连接/授权观测与插件故障隔离完整旅程；可读性和辅助技术复现 |
| S0 | 原 77 Scene 清单及新增证据 Overlay，共 78 Scene；7 路由/21 设置页与旧问题索引；**本轮新增 173 动作 actionProof 台账与 CI 防伪门禁，44 个真实 Port/Command 静态映射** | 其余 129 动作尚未填 Owner，所有动作仍缺完整业务/恢复签收；隐性入口需核对 |
| S1 | 既有中立 Chat/Work/Session/Tools/Media/Automation 等 Port；群聊端口与 V6/Next 接线；Work 团队控制和来源关联端口；Shell 偏好/删除清理；本轮图库 Owner 直接实现读取、重命名和归档消息删除，Next 原生列表/标题编辑 | 人物/图库其余命令、图库删除联动、真实证据核验、系统端口和旧回调全面退出仍未完成 |
| S2 | Next 独立会话切片与既有夹具；**本轮新增 App 组合层真实身份准入→原 Chat/Work 执行 Port 的 Android 测试源码，群聊显示测试** | 测试执行端仍为替身，未获当前 CI/设备回执；真实模型/审批/恢复双包旅程未验收 |
| S3 | 会话草稿/阅读位置归 Host；Next 独立编译；V6 的 16 个基础组件文件和对应单测已抽至 `:ui-pack-v6` | 全部 V6 页面抽离至独立 Pack、完整 Host 导航/注册切换、同触点视觉修复；本阶段未完成 |
| S4 | 受限 UiDocument、本地/可选 AI 生成、双包表单/表格、Sidecar、事实绑定、原子 revision；表单 revision 隔离与旧 SavedState 迁移；本轮真实工具结果本地 JSON/文本布局适配及双包入口 | Work 运行内发布、媒体/证据/真实进度、完整版本/恢复/留存预算仍未完成 |
| S5 | 既有 CI 门禁继续保留；新包 SDK 脚手架及语义 Token 导入代码已加入 | 整包禁用 V6 后的全场景证明、78 个现行场景、三主题、TalkBack/200% 字体/IME/弱网/收费未知 |

**全方案尚未实现完毕。** V6 仍在 App 中，已有独立 `:ui-pack-v6` 基础组件模块，但 V6 页面尚未全部移入；Next 是显式会话切片，缺失页面不会隐藏调用 V6。主线 #698 已退役运行中心、运行历史及后台助手入口，#697 已移除独立更新页，#702 已移除工作项目功能；本分支撤回对应 Project Port/页面迁移和夹具，不恢复这些功能。主线窄屏布局和额度入口移除变更已合入。

## 所有权与动作边界

业务 Owner、Shared Session、资源调度、收费记录及审批策略保持原所有权。消息快照来自现有有界正文；队列来自 Shared Runtime；审批来自 Work 的真实 interaction waiter。Owner 每次等待签发新 nonce，模型工具调用 ID 不作为审批权限。前台激活身份随 rebind 改变，旧画面意图不能重定向到当前会话。

`Accepted` 是实际接纳，`Queued` 是实际排队，均不代表完成或核验。拒绝发送保留草稿。Tools 管理元数据不构成工具执行授权，未知健康状态不显示在线。

Host 草稿与阅读锚点沿用原 12 会话 LRU，V6/Next 共用单一 UI 状态 Owner；SavedStateHandle 支持系统保存状态恢复，不等于强停/崩溃后的业务恢复。阅读锚点使用稳定消息 ID；缺失正文显示明确提示。

## 动态界面

UiDocument 只允许文本、受限事实绑定、行列、折叠、字段、多行字段、选择、开关和按钮。复用 `JsonSchemaValidator`，逐节点/动作验证未知类型、额外属性、标识、引用、预算和来源；不接受脚本、URL 导航、任意工具调用或模型提供的会话身份。表单提交再次验证必填内容、选择范围和布尔值，只校验本动作引用的字段。

AI 辅助生成入口需要用户明确确认额外模型请求，冻结实际模型路由，执行一次无工具请求，不进行隐性付费重试。实际 Provider 用量写入现有 Usage Owner；未报告的用量显示未知。来源失效会取消请求，取消传播至真实模型调用，晚到结果不得发布；取消后不推断服务端费用为零。

定义作为 `ui.document.published` 事件写入既有 Shared Session 日志，共用归档/删除生命周期。来源包含会话、消息、context generation、profile、project 和模式；模型不能赋予审批权限。普通输入动作只准备用户草稿，由正常发送按钮再次确认。导航只接受当前包已装配的 Scene：Next 会话切片明确禁用未实现页面的导航动作。历史定义读取失败明确显示不完整，原消息仍保留。

文档及表单重开/恢复的完整旅程、无法识别消息锚点的损坏记录治理、Work Owner 运行内自动发布、绑定真实进度/证据和资源预算仍需完成，不计入已交付结果。

## 独立包入口

Next 编译依赖只有 `:ui-contract`、`:ui-host` 和 Android/Compose 标准库，不引用 App、V6、业务 Store 或 ModelGateway。仅 Debug 可显式打开：

```sh
adb shell am start -n com.sy220284.dshmobile.debug/com.labteto.dshmobile.MainActivity \
  --es ui_pack next-conversation-slice
```

入口操作同一会话，通过明确关闭按钮或 Android Back 退出切片。`UiPackManifest.checkCompatibility` 会拒绝将其选作全应用 Pack；窗口和无障碍支持声明不是设备验收证据。

## 旧问题去向

| 原问题 | 唯一阶段与当前状态 |
|---|---|
| 1、8、15、17 | 已失效的历史推断，仅保留 S0 核对及 S5 回归 |
| 2、3、9、12、14、19 | S3 V6 内部动效/排版/圆角/文案，尚未全量迁移 |
| 6、7、10、11、22 | S0/S1 风险与能力契约、S3/S5 交互；本批覆盖真实审批身份及回执 |
| 4、5、13 | 已复现才列 S-1，其余 S3/S5；没有将源颜色估算称为设备 AA 失败 |
| 16、18、20、21 | S0 选择高风险旅程、S5 追尾/IME/恢复/弱网收费未知；仍待实际验收 |

## 验证回执

- `3beb5012`：完整 CI [Run 38485866962](https://github.com/sy220284/777/actions/runs/38485866962) 所有任务通过，包括静态/架构/官方夹具、全部 JVM 单测、ARM64 lint/optimized APK、x86 Debug/测试/optimized APK、Android 16/17 仪器化和 merge gate。该回执不覆盖后续源码改动。
- 合入 `d69f6513` 后旧回执不代表当前组合通过。后续动态表单、取消和导航修正：`:ui-contract:test`、`:ui-host:compileDebugKotlin`、`:ui-pack-next:compileDebugKotlin` 本地通过；Schema 越权/来源/大小回归测试已新增，最终 App/设备 CI 需绑定最终 head。
- Next Android 交互测试为明确的端口渲染夹具，覆盖拒绝保留草稿、审批身份和跨包草稿复用，不替代真实模型/工具业务验收。
- 用户指定当前 Codex 为测试模型；临时桥 SSE 探测成功只证明桥可响应，未完成 App/S2 验收。按最新指示停止额外环境排障和实时模型联调，未执行项目单独披露。

功能树通过源码自动同步和独立核对，不将未合并代码写成主线已交付；场景矩阵与退役索引同步维护。未更改或放宽静态门禁、权限策略及测试断言。

## 后续代码批次（待合并）

自动化页面退出调度器及规划服务直接依赖，创建、编辑、删除、暂停、恢复、运行和规划保存提交 `AutomationUiPort`；目标会话和调度 generation 准入由组合根与原 Owner 校验。媒体通过强类型命令提交收费生成、刷新及本地保存/删除，真实 Provider 状态和未知结果仍读原记录。用量读取统一到 `UsageUiPort`，不新增账本。设置/记忆读取中立快照和端口，登录摘要不包含令牌；模型异步选择回执只标提交，不标应用成功。

公开不可变 API 值迁入 `:ui-contract`，FQN 与持久化字段保持兼容；调度、规范化、凭据、OAuth、记忆派生清理与费用计算仍在原 Owner。架构门禁跟随 `LocalUsageMode` 的唯一声明位置检查，不允许根聚合新增其他类型。

V6 基础组件已独立编译，**不等于 V6 全场景迁移完成**。`tools/ui/new-pack.py` 可从受限语义 Token 生成独立 SDK 模块；生成类为抽象 Renderer，Scene 覆盖为空，不能作为已实现 Pack 选择。

动态文档仅绑定真实执行阶段、队列数、当前审批工具和当前错误，不接受模型编造百分比/核验结果。失效来源不读当前事实。历史定义不兼容时逐卡显示可用文本且清空动作；一份当前有界查询缓存随日志实例、reset generation、定义 revision、消息集合、Scene 集合及预算变化失效，避免绑定状态变化重复扫描历史。

本地 `:ui-contract:compileKotlin`、`:ui-pack-v6:compileDebugKotlin`、`:ui-pack-next:compileDebugKotlin`、`:app:compileDebugKotlin` 和 `:app:compileDebugAndroidTestKotlin` 已通过。未执行模拟器联调、验收与运行证明；按用户最新指示只推进剩余代码。

## 当前主线同步与本地优先批次（待合并）

已组合主线 #707～#710：保留 Kimi 接入、材质规则、气泡排版和工作主界面精简；未恢复退役导航或产出面板。设计组件新增测试跟随 `:ui-pack-v6`，App 设置默认值测试保留在 App。

Chat/Work 的编辑、重新生成、停止以及 Chat 分支选择通过带前台激活身份的中立命令提交原 Owner。编辑回执保留 `COMMITTED_NOT_STARTED`，不把历史改写成功误报为回复已开始。普通附件发送与团队发送退出旧绕行路径，采用同一身份准入。附件导入在实际文件导入前后校验身份；V6/Next 共用有界附件草稿和系统保存状态。Next 已新增相关原生操作与 Host 文件选择入口，附件完整预览与长期恢复仍待扩展。

新增零模型调用的纯本地 Builder：可靠类型输入可编排原文清单、回复表单和实际状态/现有审批面板；自由文本只保留原文，不推断事实。两包均可显式选择本地制作，也能在运行中发布本地状态卡。原模型生成改为可选的 AI 辅助设计。来源标明本地或模型；本地制作不写收费请求或伪造 Provider 用量。

所有制作路径重用同一 Codec/预算/来源校验与 Session sidecar。Session 定义 Owner 原子检查旧定义和 revision，再接纳同卡 +1 更新；本地制作与模型请求交错时不允许迟到定义覆盖新版本。能索引到消息但来源、定义或 provenance 损坏的历史卡片独立回退为无动作文本，不能重新获得实时绑定或审批权限。无法识别消息锚点的损坏记录不计入可执行卡片。

这批不代表 S1/S3/S4 完整交付：人物/图库/群聊和全量 Work/Session 接口、完整 V6 页面与 Next 全应用替换、通用 Host 导航、工具 Schema/结构化结果自动适配、媒体/证据/进度节点、公开 patch 工作流与版本迁移仍有剩余实现。验收与运行证明继续按用户要求暂缓。

Work 提问新增真实 waiter 签发的 nonce；模型 callId 复用不能复用旧回答/取消权限。V6 的计划审核与问题弹窗、Next 的问题面板均通过中立命令回答原等待者。此变更没有新增执行 Owner，也未修改审批策略。

当前批次 `:ui-contract:compileTestKotlin`、`:ui-host:compileDebugKotlin`、`:ui-pack-next:compileDebugKotlin`、`:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:compileDebugUnitTestKotlin` 编译通过；架构与 UI 设计边界检查通过。新增回归源码覆盖模型 callId 复用、迟到文档版本、逐卡损坏和附件草稿隔离，尚未运行设备验收或最终 CI，旧 CI 不能证明当前 head。

## Session 与历史分页端口批次（待合并）

`SessionUiPort` 接入真实创建、显式目标切换、模式导航、上下文接续和批量删除。导航回执表示请求进入原导航 inbox，创建回执只表示准入。删除 Owner 返回实际处理 ID 集合；Host 只清理对应偏好与文本/附件草稿，失败回执不假定副作用为零。旧 Project 创建不重新开放。

`TranscriptUiPort` 保留原 Session 范围授权及有界分页，UI 只持有 Owner 签发的不透明游标；投影抑制/恢复状态留在原 pager。消息和媒体块的不可变值迁入协议模块，持久化 FQN/字段保持兼容。读取绑定日志实例和 reset generation；异步 UI 历史加载也绑定本次加载 generation，旧读取不能覆盖编辑后的刷新结果。Next 新增真正的历史分页入口、会话选择及 Chat/Work 模式导航，仍不是全应用替换包。

发现自动 CI 在范围检查阶段被前批新增文件末尾空行阻断，已修正源码，并将差异检查扩展为对比当前主线整个 PR；没有修改门禁。最新必要编译通过；41 项协议及选定 JVM 回归通过（身份/等待者、revision、逐卡损坏、草稿隔离、Session 命令和历史迟到结果）。模拟器/真实业务验收、最新完整 CI、完整 V6/Next 页面与其他剩余实现仍未完成。


## Work 策略、事实读取与文件端口批次（待合并，未验证）

新增 `WorkPolicyUiPort`：计划模式、全局审批策略、针对当前审批启用自动模式/设备回合授权、撤销授权提交原 Work Owner。组合根冻结前台激活身份；针对审批的策略提交由真实等待者在同一锁内检查 nonce，再保存策略并完成等待。模型 callId 不能替代 UI 授权 nonce。V6 生产入口改为提交该端口，Next 增加原生计划开关、授权撤销和明确确认的审批策略入口。

新增 `FilesUiPort`：列表、会话文件与文本/二进制轻量预览仍走原文件 Owner、沙箱与读取预算。`WorkReadUiPort` 读取真实成果、事件分页、工具活动与指定证据，游标仍绑定日志实例/reset generation，文件可用性与版本仍由原 Owner 计算。对应不可变显示值迁入协议模块，业务投影与副作用留在 App。V6 的这些读取退出私有运行时；Next 提供原生文件预览及 Work 成果/事件入口，每次只保留有界历史页，不恢复主线已退役的独立产出页。

按用户最新要求，本批没有运行测试、编译、设备验收或 CI 跟进。前文成功回执仅覆盖 `1bf65caf` 及更早代码，不覆盖本批。整个 R4 尚未完成：人物/图库/群聊、团队与证据写入等剩余端口、完整 V6 页面独立迁出、Next 全应用替换及通用 Host、完整动态 UI 工作流仍待继续实现。


## Next 会话文件与草稿附件轻量预览（待合并，未验证）

选取改动范围较小的文件 UI 缺口：Next 直接调用已有 `FilesUiPort.conversationFiles`，分别展示当前会话真实成果文件与涉及文件，并复用原文件 Owner 预览。草稿附件新增预览入口，文本显示内容与截断提示，二进制显示大小及不支持文本预览的说明；图片/音视频完整预览仍未实现。文件面板的异步结果绑定激活身份和本次请求；关闭、切换或发起新请求后，旧结果不能重开面板或覆盖新预览。读取失败在预览面板内可见，保留附件草稿。按用户要求，本批未测试、未编译、未跟进 CI。


## Next 接续、默认审批与工具活动（待合并，未验证）

继续选取现有端口已经支持、改动范围较小的三个入口：Next 从 `SessionUiPort` 读取原 Owner 接续摘要，允许用户确认/编辑后创建 `CONTINUATION` 会话，提交携带原激活身份和模式；默认审批恢复通过 `WorkPolicyUiPort` 明确确认后应用，不由文档改变权限；Work 面板从 `WorkReadUiPort` 显示真实工具阶段、错误及参数/结果摘要，明确区分完成、失败、取消和结果未知，不把工具完成推断为需求核验通过。Work 面板关闭后失效本次读取，迟到结果不能重新打开。业务 Owner 未新增或替换。本批仍按要求不测试、不编译、不跟进 CI；完整 UI 替换及其他未实现部分不标完成。

## 2026-10-14 本轮接续实施：Host Pack 准入

**本轮新增（与此前作者/提交区分）：** `09822351` 增加 `:ui-host` 的 `UiPackRegistry`，将已安装的 UI Pack 按不可重复的 ID 注册；请求不存在的包直接拒绝，存在但场景/契约/能力/窗口/无障碍/深色模式不兼容的包返回逐项诊断，不自动用 V6 偷偷填补。新增 `UiPackRegistryTest` 回归测试源码，覆盖缺失包、整包 Scene 缺失、受限会话切片和重复 ID；以及 `docs/design/ui3/UI-PACK-REGISTRY-AUDIT-20261014.zh-CN.md` 代码-设计对照。

**此前已有（不是本轮实现）：** `:ui-contract`、`UiPackManifest.checkCompatibility`、Next 会话切片、V6 的 16 个基础组件文件拆分、各 Feature Port、本地 `UiDocument` Builder、Shared Session 逐卡恢复等。维持原台账归属。

**仍未完成：** 此次是独立的**准入机制**，尚未接入生产全局 UI Pack 选择和 UI Host 场景路由；全量 V6 页面独立拆分、Next 全 App 覆盖、S2 双包真实动作验收、S4 扩展与 S5 设备回归均未宣告完成。在线 Figma 未修改（当前连接为 View，且没有指定画板 key/节点）。

**最新已观察到的 CI 阻断（非新代码验证）：** 早于本轮提交的 `2d243b92` 工作流 `38528990824` 中静态门禁和 JVM 单测成功，但 Android 构建/设备链因 `:ui-host:debugRuntimeClasspath` 对 `androidx.emoji2:emoji2:1.2.0` 的 Gradle 依赖元数据校验失败而阻断。暂未核实官方 artifact 的 SHA-256，**不得为通过 CI 关闭依赖安全校验或填写猜测校验和**。最新提交尚无完整 CI/真机回执。


## 2026-10-14 本轮继续：Pack 准入接入真实 Next 入口（本次对话）

**此前已有，非本次贡献：** `UiPackRegistry` 的类定义及静态兼容检查、Next 的 debug 切片入口、既有 Port / Manifest / Source-of-truth，见上节 `09822351`，不重复计功。

**本轮新增代码（可逐提交追溯）：**
- `ea745fa6`：新建 App 组合层 `LocalConversationPackGate`，以 Host 固定的会话必需 Scene 清单和**实际接通的 Chat/Work/Draft/UiDocument/Attachment/Transcript Port**投影可用协议能力；Next 调试切片从硬编码 `NextConversationUiPack.Render` 改为先由 `UiPackRegistry.select` 审核后调用被选中的 Pack `Render`。失败时显示中立错误提示并返回稳定 UI，不隐藏兜底、不进行会话切换。
- `ea745fa6` 和 `d586ba39`：新增/补全入口回归测试源码，覆盖未知 Pack、缺失 UiDocument 能力、Work 审批能力缺失、完全没有连接真实 Port，以及受限切片可被明确准入。
- `6fee3ab8`：`ui-host` 的 Gradle 依赖约束选择仓库原有 `gradle/verification-metadata.xml` 已记录 SHA256 的 `androidx.emoji2:emoji2:1.4.0`，替代从传递依赖解析到的未登记 `1.2.0`；**未关闭验证、未编造散列、未改全局安全门禁**。

**局限与验证状态：** 这是**Debug Next 会话切片**的真实准入接线，不是完整应用的包管理器、全局路由或可切换 V6/Next 全套场景。Owner 仍按原规则二次授权，Host 的“端口已接线”不意味着此刻具有执行权限。当前未收到绑定 `d586ba39` 的最新 CI/设备回执；依赖修复是根据原 CI 日志与仓库已验证版本完成的**有依据改动**，仍需重跑 Android CI。仍不声称 S2/S3/S5 验收通过。

## 2026-10-14 本轮追加：S4 受限表格与 Work 工具事件（仅本轮贡献）

**本轮新增代码（与此前工作分开计算）：**
- [`cc0e6ddc`](https://github.com/sy220284/777/commit/cc0e6ddc08fae94ce813b9fcfead9e5df22cedf0)：同一批 14 文件原子提交，新增受节点/字数预算保护的只读 `UiDocumentNode.Table`、嵌套严格 Schema / Codec 编解码、V6/Next 各自原生渲染、`TableData` 类型化本地 Builder、两包零额外请求的 `FACTS_TABLE` 会话快照入口，并编写协议/Codec/Builder 测试源码。
- [`c97f01b0`](https://github.com/sy220284/777/commit/c97f01b0fef6a95d5f92ce2877da347b713516c1)：新增 Work 模式 `WORK_TOOL_ACTIVITY` 本地表格入口，读取已有 `WorkReadUiPort` 的真实 EventLog 工具活动，最多最近八项，准确展示原阶段（含失败、结果未知、取消），不提供工具执行、收费重试或审批权限；Chat 模式拒绝。更新两个 Pack 的可用入口。
- [`UI-DOCUMENT-TABLE-R4-20261014.zh-CN.md`](design/ui3/UI-DOCUMENT-TABLE-R4-20261014.zh-CN.md)：本轮代码-设计边界、来源、验收限制和贡献归属对照。

**此前已有，不归本轮成果：** `:ui-contract`、原三个本地模板、AI 受控调用、真实用量 Owner、`UiDocumentSessionStore`、Work EventLog 活动投影、`WorkReadUiPort`、V6/Next 动态表单与其他 Feature Port。本轮只在已有可靠类型化数据与共享闭合协议之上加显示能力。

**仍未完成（不得因新增表格而勾选）：** 任意工具 Schema/结构化模型数据自动推断、动态图表/媒体/证据与实时更新、Work 运行内自动发布、公开 patch 工作流/兼容迁移、S1 全量 Port 与旧回调清理、S2 真正端到端双包验证、S3 全 V6 页面拆包与 Next 全应用替换、S5 38 场景及设备/弱网/辅助技术验收。新表格仅是制作时快照，既非实时进度也不证明需求核验。

**验证：** 对照源码和权威 `JsonSchemaValidator` 确认已有嵌套数组语义校验支持；新增回归测试**源码**已经提交，但目前没有绑定本轮最终 head 的成功 CI/Android 16/17 设备回执。不得写为验收通过，不推进 Draft 合并。

## 2026-10-14 本轮追加：S1/S2 群聊成员可执行接口

**本轮新增 [`36a07cfd`](https://github.com/sy220284/777/commit/36a07cfd451cd31eccac3012015294230542ee30)：** 增加 `ChatGroupUiPort` 中立读写契约、App `LocalChatGroupUiAdapter`（Shared Session 激活身份准入、旧 Chat Owner 成员协调器、最多 6 人边界）、Next 原生成员管理弹层。更新 Pack Manifest/Host 能力准入、两类测试源码、S0 群聊成员动作端口证据映射。源码路径：[群聊 Port 专项说明](design/ui3/R4-GROUP-PORT-20261014.zh-CN.md)。

**此前已有，不能计入本轮：** Chat 组员协调器、PersonaGallery 主档、V6 群聊成员入口、现有 Chat/Work Port、Next 会话切片。

**验收界限：** 此功能仅覆盖群聊成员配置动作，真实执行者仍是 Chat Owner。群聊公告、图库/人物编辑等仍有大量未迁移动作；S2 真正两包的模型/设备完整验收与 S3 全应用独立移包均未完成。最新 CI 以 `36a07cfd` 之后的最终 head 为准，未获得成功回执不得宣布通过。


## 后续提交源码审计与修正（基线 9b9f30bd，未运行验证）

重新读取远端后续提交，对照 S1/S3/S4 清单发现并修正：V6 成员保存仍在点击时查询当前身份，现改为使用当前 UI 帧捕获身份，群聊成员/公告弹窗随激活身份改变清除；Next 群聊管理改为订阅真实群聊快照，成员变化可更新；原成员 Owner 异步排队后才取得维护租约并持久化，因此 Port 回执改为 `Queued`，两包不把排队误认成已保存。原 Owner 仍负责后续提交和可见错误。成员协调器现冻结已有 Shared RunHandle 激活标识，持久化与投影提交在共享锁内再次核对；切出再切回同一会话不能使旧排队编辑恢复有效。失败诊断保留，但旧激活错误不覆盖新画面。群聊命令异常转为失败回执，取消继续传播。追加核查发现旧 revision 的迟到表单编辑可删除新版草稿，Host 现拒绝这种回退更新；保留既有 SavedState 格式。

本批只做源码审计与修正，未运行测试、编译或 CI。前部阶段状态已统一现行主线、15/163 静态映射与 77 Scene 口径；历史批次保留为历史记录，不代表当前完成度。


## 群聊模式与新会话端口（未验证）

继续处理 S1-03：`ChatGroupUiCommand` 补入模式切换与创建群聊会话，携带捕获激活身份；实际模式导航、新会话和成员业务仍归原 Chat/Session Owner。图库选项在单聊模式也可查询，成员数量约束由原 Owner 常量投影到快照。V6 新建群聊与退出模式改走中立端口，创建弹窗绑定激活身份；Next 新增进入/退出模式及图库成员选择后新建群聊入口。模式切换表示导航请求已排队，新会话 Accepted 不表示后续群聊执行完成。群公告、图库删除触发成员移除等剩余操作尚未迁移，不宣称群聊 Port 全量完成。

S0 矩阵登记新建群聊的实际命令路径，所有相关动作仍为待验收。前部计数以矩阵为准。按用户指示未运行编译、测试或 CI。


## 群公告保存及单成员移除端口（待合并，未验证）

继续补 S1-03：`SaveAnnouncement` / `RemoveMember` 携带捕获的激活身份，组合根在共享前台锁内准入并交原 Chat Owner。公告的单一事实写入函数先提交域事件再发布投影；派生快照交原 Session 队列，不把缓存延迟当事实未保存。V6 群公告保存改走同一端口；Next 新增原生公告编辑/清空和成员移除，IO 执行、取消传播，失败在弹窗内保留输入并可重试。公告上限仍是原 2000 字符，由 Chat 常量投影给 Next，不新增收费请求或保存源。

成员移除仍由原协调器维护成员、游标、活动说话者、域日志与模型历史。回执只在权威事实已提交时接纳，维护租约失败/成员失效不伪报成功。图库删除后的旧 UI 联动尚未迁出，人物/图库端口和其他剩余工作仍未完成。S0 增加实际已存在的模式/单成员动作及公告命令映射，映射数按当前矩阵计算，全部保持待验收。按用户要求，本批未测试、未编译、未跟进 CI。


## 全方案主干推进：Work 团队/证据、Shell 与 Host 恢复（待合并，未验证）

Work 团队正式接入中立 `WorkTeamUiPort`，捕获前台激活身份及成员真实 Job ID；原团队命令锁内重查目标，团队停止校验原成员集合。V6 原生产调用退出当前会话取值式私有桥；Next 新增团队启动、成员消息、成员/团队停止与实际输出。原私有 Agent UI Port 及 Runtime 转发退出生产代码，原团队/Job Owner、队列和计费保持不变。接纳不表示成员已完成或需求已核验。

`WorkEvidenceUiPort` 读取实际需求、来源和文件版本，用户关联操作由原 Work Owner 检查真实工具事件/来源/版本后写入现有日志。文件扫描/散列不持有前台绑定锁，写入前重新核对激活身份、需求文本和日志实例/reset。两包展示“用户选择来源，不等于核验通过”；本批未建立伪造的 VERIFIED 判定。V6 独立模块新增实际证据 Overlay 与本模块资源，App 仅传入中立 Port；这是业务界面移包增量，不代表 V6 整体已迁出。

Host 的 `ConversationShellUiPort` 接入重命名与显式置顶；原偏好 Store 仍是唯一保存者，V6/Next 共用。两包删除统一经过 Host 生命周期组合：只有 Session Owner 确认处理的 ID 才清理文本/附件、置顶/标题、阅读锚点及动态表单；失败不假设业务副作用为零。Next 增加独立目标确认及真实删除回执，搜索/消息定位和全局导航仍待补齐。

全方案仍需人物/图库完整端口、全 App Host/Scene 接口、V6 完整移包、Next 全必要场景、动态媒体/核验/事件更新/自动发布/结构化适配/留存预算。当前完整代码清单记录这些剩余项，不将端口或 Overlay 增量称为整套可替换。测试、编译、设备证明与 CI 继续按用户要求延后。
