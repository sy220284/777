# UI 3.0-R4 实施台账

执行依据：[R4 完整方案](design/ui3/PLAN-R4.zh-CN.md)。权威规范：[UI 3.0](design/UI-3.0.zh-CN.md)。当前分支 `feat/ui-r4-pluggable` 已组合主线 `3a69089d`，尚未合并。此台账记录实际实现，不能将方案描述、渲染夹具或 CI 通过当作全方案完成。

## 阶段状态

| 阶段 | 当前实现 | 尚未完成 |
|---|---|---|
| S-1 | MCP 未检测健康使用中立标识；Chat/群聊显示真实共享队列；Work 审批使用 Owner 签发的实际等待身份 | 实时 MCP 选中/连接/授权观测与插件故障隔离完整旅程；可读性和辅助技术复现 |
| S0 | 77 项现行 Scene、7 条顶级路由、21 个设置页面与行内更新；4 项退役场景保留历史索引；22 项旧问题有阶段去向 | 全部隐性入口的真实旅程核对，逐动作能力/回执/恢复矩阵完善 |
| S1 | `:ui-contract` 中立协议；Chat/Work 文本/附件发送、编辑、重生成、停止、审批/提问，Chat 分支选择；Tools、Automation、Media、Settings、Usage、Memory 中立端口；Host 草稿、阅读锚点和动态表单共享状态；模型不可变公开值抽离 | 附件完整预览、人物/图库/群聊、Work 计划/成果扩展命令和共享会话全量契约迁移；生产旧回调全面退出 |
| S2 | `:ui-pack-next` 独立 Material 会话包与 V6 共用真实会话命令/交互 Port；三项显式渲染夹具测试 | 当前 Codex 与 App 的真实发送/审批/恢复旅程，不能标记 S2 已验收 |
| S3 | 会话草稿/阅读位置归 Host；Next 独立编译；V6 的 16 个基础组件文件和对应单测已抽至 `:ui-pack-v6` | 全部 V6 页面抽离至独立 Pack、完整 Host 导航/注册切换、同触点视觉修复；本阶段未完成 |
| S4 | 受限 UiDocument 协议、既有 Schema 引擎验证、本地模板与可选模型生成、真实用量分类、Shared Session Sidecar、双包原生表单/按钮、文本回退；真实阶段/队列/审批/错误绑定、原子 revision 准入和逐卡历史回退 | Work 运行内发布、进度/证据/媒体绑定、更新与版本迁移完整闭环、历史检索增量化与表单留存预算 |
| S5 | 既有 CI 门禁继续保留；新包 SDK 脚手架及语义 Token 导入代码已加入 | 整包禁用 V6 后的全场景证明、38 场景、三主题、TalkBack/200% 字体/IME/弱网/收费未知 |

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
