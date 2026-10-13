# UI 3.0-R4 实施台账

执行依据：[用户提供的 R4 完整方案](design/ui3/PLAN-R4.zh-CN.md)。UI 权威以 [UI 3.0](design/UI-3.0.zh-CN.md) 为准。方案里的交付状态是设计时的历史记录，当前代码和验收状态以本台账为准。首批实现从 `origin/main @ 131fcead` 建立 `feat/ui-r4-pluggable` 分支；已组合更新到 `origin/main @ f2aeac62`（含 #692–#701 的相关主线更新）；尚未合并。

## 阶段和真实边界

| 阶段 | 已落代码/资料 | 出口状态 |
|---|---|---|
| S-1 | MCP 未检测健康状态使用中立标识和文案；Chat/群聊投影真实前台队列；取消/未知计数补丁随 #698 页面退役撤回 | 编译、单测及设备回归记录见下方，不把静态确认算设备通过 |
| S0 | 76 项现行源码场景索引、全部 8 条顶级路由及 3 项已退役历史索引、21 个设置导航目标及行内检查更新和主要槽位/弹层；旧问题去向 | 初始审计清单已建立；还需真实旅程核对全部隐性入口，不能以数量宣称完整覆盖 |
| S1 | 无 Compose 的 `:ui-contract`；Chat 文本发送、Work 文本发送/审批端口；只读消息和能力投影；真实回执；Host SDK | 第一条迁移切片，尚未覆盖附件、编辑、分支、媒体、Automation、Tools、Project、Settings 全量命令 |
| S2 | `:ui-pack-next` 独立 Compose 验证包；V6 普通文本发送与单次批准/拒绝共用端口；仅 Debug 可显式打开 | 真实发送/审批/恢复与双包设备旅程待验收；不能宣布 S2 通过 |
| S3 | 尚未开始 V6 全量移包 | 必须先通过 S2；旧页面和兼容回调还未退出 |
| S4 | 尚未开始 UiDocument 持久化、模型生成或动态卡执行 | 必须先稳定 S1/S2，不把生成卡绑到旧 V6 |
| S5 | 保留既有 57 个截图输出及 38 场景台账 | 独立验证包和 Android 16/17、TalkBack、大字、IME、弱网、恢复的真实证据仍待补齐 |

本批没有通用动态 UI、额外模型请求、收费媒体新入口、运行时 Dex 加载或第二业务事实库。`ConversationUiPack` 是首条会话切片接口，不是已经迁完全部页面的应用 UI Pack。V6 仍在 App 中，尚未形成独立 `:ui-pack-v6` 编译边界。

## S-1 问题与证据

| ID | 当前根因及 Owner | 首批处置 | 回归证据 |
|---|---|---|---|
| R4-UNKNOWN-CANCELLED | 初始基线运行中心把取消并入未知数量；该页面及其计数器已被主线 #698 删除 | 已撤回页面、资源和测试补丁；不恢复退役入口 | 旧计数修正曾通过单测；不作为当前 PR 的已修复功能，后续在现行结果展示中复核两种终态 |
| R4-MCP-HEALTH | `McpServerSnapshot` 只有注册/发现信息，没有当前连接健康和授权观测 | MCP 条目中立灰点；服务概览和插件详情均显示“已注册，连接健康未检测” | `PluginInventoryBrowserRegressionTest.serviceConnectionsCategoryUsesLocalMcpInventory` 新增未检测文案断言；设备运行待补 |
| R4-CHAT-QUEUE | Chat SendCoordinator 确实将输入入 Shared Runtime 队列并更新 count，Chat surface 漏传 | 投影 kernel.queuedInputCount；保留 Work 专属 plan/approval/context 隔离 | `LocalHarnessUiStateTest` 覆盖单聊、群聊、队列清空及 Work 独立字段 |
| R4-PLUGIN-LIFECYCLE | 插件生命周期与资源残留的准入关系，不能推断所有 FAILED 都阻断所有工具 | 保持现有 ToolLifecycleAdmission 与资源保护；本批没有放宽门禁 | 现有 Plugin/Tool lifecycle 契约继续有效；没有安全隔离的运行证据时不缩小保护范围 |
| R4-DOMAIN-STATE | 旧提案的 Done/Delivered/Verified 映射不能当作当前生产误显证明 | 未进行全量字符串枚举替换；在后续各 Owner 投影中逐项核实 | 运行、验证、用户动作三维全量映射仍待 S1 后续切片 |
| R4-ACCESSIBILITY | 壁纸、IME、TalkBack 属于待设备复现的风险 | 未盲目重画旧页面 | Android 16/17 及真实辅助技术验收待补 |

MCP 首批只修假健康显示；实时注册、选中、连接、授权四维能力投影仍是 S1 后续工作。没有把用户“可见的工具”当作“当前有权执行”。

## 场景、动作、事实源和恢复

[可机读场景矩阵](UI-SCENE-MATRIX.json) 记录场景 ID、Owner、顶级路由、槽位种类、源文件、动作和恢复事实。运行期覆盖要求由 `LocalUiSceneCatalog` 装配，顶级 Owner 始终从 `LocalFeatureCatalog` 取得。设置枚举与路由覆盖由单测核对。

消息只读快照来自 Shared Session 已有有界正文，队列仍归 Shared Runtime，审批仍归 Work 的真实 interaction waiter。新端口不返回可写领域对象、Store、Runtime、执行协调器、Compose 类型或 V6 组件。

V6 普通文本和单次审批的回调固定画面生成时的身份；不在点击时按当前会话重新定向。文本发送先在 Shared Runtime 既有 foreground lock 内核对会话激活身份、模式、项目、账户 profile 和 Chat context generation，再调用原 Chat/Work execution port。`LocalAgentRunHandle.activationId` 随 rebind 更新，是进程内旧 UI 意图的生命周期栅栏，不持久化、不替代 Owner 的幂等/任务身份，也不用于收费操作重放。离开再回到同一会话后，旧激活身份失效。

`Accepted` 只表示原 Owner 接纳，`Queued` 表示原 Owner 已排队。审批 ID 由真实等待器注册时签发，每次等待都有新身份；模型提供的工具调用 ID 不作为 UI 审批权限。当前可见投影、当前等待器与命令身份必须一致，并由实际等待器成功接纳才返回 Accepted；重复调用 ID、过期和重复审批都不能凭界面状态伪造成功。新增身份只附于现有等待记录，不持久化、不建立审批账本，也不改变工具执行或自动审批政策。历史动作不自动重提。

## 独立包验证方式

新包编译依赖仅为 `:ui-contract`、`:ui-host` 和 Android/Compose 标准库，没有 App、旧 V6、ViewModel、Store、ModelGateway、ToolRegistry 依赖。新包使用平面消息列表和独立 Material 排版，自己的状态文案、输入框和审批按钮；业务仍通过相同端口。

仅 Debug 版本提供显式切片入口：

```sh
adb shell am start -n com.sy220284.dshmobile.debug/com.labteto.dshmobile.MainActivity \
  --es ui_pack next-conversation-slice
```

先在 V6 建好目标 Chat/Work 会话和可用模型，再从这个入口验证同一会话；返回按钮/Android Back 会明确退出切片。它不提供整包选择器，也不悄悄渲染缺失的旧页面。原会话数据不会由新包复制保存。

`UiPackManifest.checkCompatibility` 检查契约版本、必要 Scene、能力、窗口、无障碍和暗色声明。单测确认此切片包无法通过全应用覆盖检查。声明支持无障碍和窗口类型只是契约声明，不等于已通过设备验收。

自动审批政策、设备批准租期及问答继续由现有 Owner 处理，尚未纳入新强类型命令。双包草稿现由 Host 单一 UI 状态 Owner 持有，V6 和 Next 共用强类型端口；通过 SavedStateHandle 保留 Android 保存状态恢复，继续沿用原有 12 会话 LRU，未新增业务持久化或执行账本。保存状态恢复不等于强停/崩溃后的完整业务恢复。阅读位置已接入 Host：两包按稳定消息 ID 恢复，Work 保留阅读暂停，缺失消息明确提示；保存状态恢复仍待完整用户旅程验收。本批仍缺附件、审批参数大字布局、账号撤销和进程恢复的完整用户旅程证据。不能按“能编译”宣布插拔完成，也不能绕过 S2 出口直接全量迁移。

Project 页面已改为直接消费中立的 `ProjectUiPort`，创建、选择、重命名、删除、规则保存和目录恢复提交强类型命令，实际写入及引用检查仍由原 Project Owner 执行；生产端退出 `LocalProjectUiActions` 业务回调，旧测试回调仅存在于显式渲染夹具。其余 Feature 仍在迁移。

## 旧 22 项问题的唯一去向

| 原 ID | 阶段 | 当前证据与状态 |
|---|---|---|
| 1、8、15、17 | S0 复核 / S5 回归 | 原推断已失效，不建立旧文件修复任务；保留现行图标/源集/响应式功能 |
| 2、3、9、12、14、19 | S3 V6 包内部 / S5 | 语义动效、排版、具名圆角、静态文案仍待全量迁移；不约束新包必须用 Sy* |
| 6、7、10、11、22 | S0/S1 风险和能力契约 / S3/S5 | 本批仅真实审批身份与回执切片有代码；风险操作和发现性全场景待补 |
| 4、5、13 | 复现后 S-1 / 其余 S3/S5 | 当前没有设备复现证据，不把源颜色估算冒充正文 AA 失败 |
| 16、18、20、21 | S0 选择高风险旅程 / S5 | 真实追尾、输入/审批、恢复、弱网收费未知等仍待设备验收；0/38 不是 38 个功能缺失 |

方案中 R2 的六项风险统一后置到相应切片：Schema 复用、Sidecar 时间线、权限与幂等、模型用量/结构化差异、媒体/工作/自动化分账、隐私与资源预算。首批没有实现模型卡，所以没有新建 Schema 引擎、日志账本或模型费用分类。

## 验证回执

最终验证必须绑定最终源码；以下状态由实际结果更新，不能用设计文件或测试夹具代替完整旅程。

- 静态：UI 设计系统、颜色、CJK、架构、性能、Kotlin 风险、测试质量、安全、通知、工具链基线、CI 完整性已通过；最终模式隔离修正后的架构、测试质量、CI 完整性与功能树同步及独立检查已通过。
- `:ui-contract:test`：3 项测试通过，失败/错误/跳过均为 0。`:ui-host:compileDebugKotlin`、`:ui-pack-next:compileDebugKotlin` 已通过。
- 组合至 7c7a5c88 后，Run 38429460247（86629c0b）：全部 JVM 单测、ARM64 optimized APK/lint、x86 Debug/测试/optimized APK 构建通过。Android 16 运行 248 项，MCP 文案断言因列表和详情同时匹配而失败；已限定到详情文本，未削弱健康状态断言。独立包的两项渲染交互测试属于显式夹具验证。
- 完整 CI Run 38434973184（c5f3e6c9，组合基线 7c7a5c88）全部通过：静态、架构、官方夹具、全部 JVM 单测、ARM64/x86 APK、lint、Android 16/17 仪器化及启动检查、57 张截图输出。真实当前 Codex 与 App 的双包业务验收仍未完成。
- 主线随后 #698/#701 更新至 f2aeac62：已撤回退役运行中心相关修复，移除三项现行 Scene 要求，保留历史索引。Run 38439855781（f860ff02）最终完整矩阵通过；Android 17 首轮既有用量详情测试出现 5 秒等待超时，同 head 第二次通过，未修改断言或超时。后续共享草稿改动仍需重新验证。
- 首次完整 CI（Run 38416556578）：静态和架构通过；单测和构建被独立包解析出的旧 Lifecycle/Tracing 元数据校验阻断，尚未运行到测试。独立包补充与项目已核验版本一致的依赖约束及 Coroutine Android 依赖，不关闭校验、不添加旧版散列。Android 17 镜像下载同时出现 Zip 损坏，未记录设备通过。修正后重跑。
- 构建排障：新模块脱离 App 后曾解析到未缓存的 Activity 旧版；Host SDK 已去掉无用 UI 依赖，验证包使用项目固定 Activity 版本；未联网补齐。
- 首轮 App 构建：KSP 报 `No fir element was found for KtParameter`，失败，没有据此声称单测通过；已对最终依赖图重新执行。
- Android 16/17 仪器化：既有完整 CI 提供模拟器，尚未记录最终通过；渲染器夹具不代替真实模型/工具执行证明。
- 用户指定当前 Codex 为测试模型：临时 loopback 手动请求桥已通过实时 SSE 探测；这是模型桥探测，不是 App/S2 验收。ADB 已启动；本地模拟器主线产物下载被环境网络策略 403 阻断，等待下载域名开放。无需复制模型登录凭据。TalkBack、大字体/IME及真实业务恢复仍待验收。

按用户最新指示，先完成全方案实现、提交并创建 PR，不再把额外环境排障与实时模型联调作为代码推进前置条件。S2/S5 未验证项仍独立披露，不计作验收通过。
