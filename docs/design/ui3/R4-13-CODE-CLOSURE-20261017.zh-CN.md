# R4 原13项代码缺口闭合清单（待验证）

实施基线：PR #705 `25ea8347d7fa6db01a6081b92eeab0b6b96daaf8`；提交前已接入并保留同期V3的 `9a19976aec149b6d8c095e800696dd6379c79088`，重新核对受影响的迁移与功能树。本次只将代码实现齐备的13项推进待验证；没有宣称编译、单测或发布验收通过。

## 本次结构变化

旧Shell屏幕、聚合ViewModel、兼容启动入口已实际删除；AppRoot只有真实Feature Port的独立V6装配。Debug运行完整候选，Release迁移签收能力继续封闭。工具执行与Work证据提交共享同一工作区读写租约；Session仍是唯一持久权威。

## 逐项实现与回归源码

### S-1-01 · 实时 MCP 注册/选中/连接/授权观测

MCP Owner 提供有序生命周期 Flow 和可等待真实变更的 mcp_lifecycle_events；回放缺口显式要求快照。所有实际准入（含 NEVER/自动策略）落 session/call/execution 精确授权事件，V6 工具活动展示原回执，替换 execution 不继承旧授权。

生产源码：`harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/mcp/McpToolBridgePlugin.kt`、`app/src/main/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinator.kt`、`app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolActivityProjection.kt`

回归源码（未运行）：`harness-interop/src/test/kotlin/com/labteto/dshmobile/interop/mcp/McpToolBridgePluginTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinatorTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/tools/LocalToolActivityProjectionTest.kt`

### S-1-02 · 插件故障隔离与恢复

不可信外部 MCP HTTP/stdio 只能走独立 mcp_worker Messenger/PFD，PID/实例/调用票据及断链撤权由原 Owner 管理，无主进程回退或副作用重放；受信任内置插件仍归原业务 Owner，LSP/终端外部程序运行在受控 OS 子进程。此项不承诺不同 UID 的权限沙箱。

生产源码：`app/src/main/java/com/labteto/dshmobile/local/tools/LocalPluginComposition.kt`、`app/src/main/java/com/labteto/dshmobile/local/tools/ipc/McpPluginWorkerService.kt`、`app/src/main/java/com/labteto/dshmobile/local/tools/ipc/McpPluginWorkerTransport.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/local/tools/ipc/McpWorkerWireTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/tools/ipc/McpWorkerCallLedgerTest.kt`、`harness-interop/src/test/kotlin/com/labteto/dshmobile/interop/mcp/McpToolBridgePluginTest.kt`

### S1-05 · Work 证据写入与核验契约

Work 机器判断逐条核对原 Goal 的 artifactPaths/verificationCommands、真实执行身份与当次字节版本；人工核验独立落事件。Tool 组合根和 WorkEvidence Adapter 共享同一注入的工作区读写租约，读取→准备→重验→提交期间排除原工具写入（包含 Worker 外部调用），最终 Session OS 写租约内再验目标和来源。外部编辑不会被冒充为已锁住，其后以 SHA 和文件事件使旧证明失效。

生产源码：`app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkDeliveryEvidence.kt`、`app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkMachineVerification.kt`、`app/src/main/java/com/labteto/dshmobile/local/LocalWorkEvidenceUiAdapter.kt`、`app/src/main/java/com/labteto/dshmobile/local/tools/LocalWorkspaceReadWriteGate.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/local/work/LocalWorkDeliveryEvidenceTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/work/LocalWorkMachineVerificationTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinatorTest.kt`

### S1-07 · 附件多类型预览、状态与恢复

附件继续由原 Session/File Owner 导入和完整性检查，失权返回 GrantRevoked 并允许重新选择；所有 V6 挑选经 Host 且固定原目标。图片 Host 按原1600px预算异步正式解码，音视频使用系统容器解码器并在后台暂停、离开释放，解码失败明确显示和手动重读。平台不支持的格式不伪报已播放。

生产源码：`app/src/main/java/com/labteto/dshmobile/local/LocalAttachmentUiPortComposition.kt`、`app/src/main/java/com/labteto/dshmobile/local/attachment/LocalAttachmentInspection.kt`、`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiAttachmentPicker.kt`、`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiAttachmentMediaViewer.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/ui/screens/local/LocalAttachmentAdmissionTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/LocalAttachmentImporterTest.kt`、`app/src/test/java/com/labteto/dshmobile/ui/host/UiHostStreamDocumentSaveTest.kt`

### S1-08 · Host 权限/文件/分享/通知/焦点服务

另补 `UiSystemPermission` 封闭运行时权限协议与 `UiSystemPermissionRequester`：Host统一窗口Owner实际发起Android通知/媒体读取权限请求，返回重新查询系统事实，页面或进程重建不自动重放。

导入、字节/流式保存、工作区导出、分享和外部打开均由 Host 实现，挑选至异步写入结束共用同一个系统操作 Owner；系统权限/通知打开只回报启动，授权事实在返回 ON_RESUME 后重读；IME/焦点由正式 Scene Host 在导航和会话切换时清理，UiPack 不启动文件 Intent。

生产源码：`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiHostSystemOperationOwner.kt`、`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiDocumentTransfer.kt`、`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiWorkspaceExporter.kt`、`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiApplicationSceneHost.kt`、`app/src/main/java/com/labteto/dshmobile/ui/host/LocalHostPreferencesUiAdapter.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/ui/host/UiHostSystemOperationOwnerTest.kt`、`app/src/test/java/com/labteto/dshmobile/ui/host/UiHostStreamDocumentSaveTest.kt`、`app/src/test/java/com/labteto/dshmobile/ui/host/HostNotificationPermissionTest.kt`

### S1-09 · 全量旧 UI 回调退场

实际删除 LocalHarnessScreen、LocalHarnessViewModel、LocalLegacyApplicationEntry 三个旧执行入口；仅保留独立的纯发送提示函数。AppRoot 唯一装配 LocalV6StandaloneViewModel 的真实 Feature Ports，未知包不暗回 Legacy，架构门禁拒绝这些旧类/函数重新进入生产源码。

生产源码：`app/src/main/java/com/labteto/dshmobile/ui/AppRoot.kt`、`app/src/main/java/com/labteto/dshmobile/ui/host/LocalV6StandaloneViewModel.kt`、`app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalSendFeedback.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/ui/host/LocalV6StandaloneBootTest.kt`、`app/src/test/java/com/labteto/dshmobile/ui/host/V6ConversationMigrationTest.kt`

### S3-02 · 7 路由/二级场景/深链/返回的通用 Host

7路由、二级Scene、深链、返回、会话范围持久导航及退休场景恢复统一走中立Host。旧默认/Legacy选择迁为唯一当前V6包，异包ID仍拒绝；损坏导航由用户显式清理后重建，恢复不再进入已删除Shell。

生产源码：`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiHostSceneNavigation.kt`、`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiHostSceneNavigationRecovery.kt`、`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiApplicationSceneHost.kt`、`app/src/main/java/com/labteto/dshmobile/ui/host/LocalApplicationUiHost.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/ui/host/UiHostSceneNavigationTest.kt`、`app/src/test/java/com/labteto/dshmobile/ui/host/UiHostSceneNavigationRecoveryTest.kt`、`app/src/test/java/com/labteto/dshmobile/ui/host/LocalApplicationUiHostDecisionTest.kt`

### S3-06 · Host/Shell 旧绑定退场

完整独立V6成为唯一应用代码入口；原75 Renderer及真实Owner装配保留。Debug应用根和独立DebugActivity可运行同一完整候选，release不提供迁移签收能力；这是待验证构建，尚未开放正式发布准入。

生产源码：`app/src/main/java/com/labteto/dshmobile/ui/AppRoot.kt`、`app/src/main/java/com/labteto/dshmobile/ui/host/LocalV6StandaloneViewModel.kt`、`app/src/main/java/com/labteto/dshmobile/ui/host/LocalV6StandaloneSelection.kt`、`ui-pack-v6/src/main/kotlin/com/labteto/dshmobile/uipack/v6/V6ApplicationScenePack.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/ui/host/V6FullSceneCoverageTest.kt`、`app/src/test/java/com/labteto/dshmobile/ui/host/LocalV6StandaloneBootTest.kt`、`app/src/test/java/com/labteto/dshmobile/ui/host/V6ConversationMigrationTest.kt`

### S4-01 · 可信工具 Schema 与结构化结果适配

Schema仅从当前ToolRegistry的原注册实例投影；提交草稿重新比较注册Schema并递归验证对象/数组/必填/枚举。真正执行仍经原ToolRegistry/Coordinator授权和注册身份复核，本轮补所有策略的精确授权记录与原回执展示。

生产源码：`app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolCapabilityDraftValidator.kt`、`app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolSchemaProjection.kt`、`app/src/main/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinator.kt`、`ui-pack-v6/src/main/kotlin/com/labteto/dshmobile/uipack/v6/V6WorkToolActivityPanel.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/local/tools/LocalToolCapabilityDraftValidatorTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/LocalToolSchemaContinuityTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinatorTest.kt`

### S4-02 · 动态图片/音频/视频状态/预览/保存

Media Owner保留原账户/会话/任务、真实下载与未知收费结算；视频续传同时比较远端ETag和本地字节SHA，下载租约禁止同任务并发清理。本轮V6音视频退出页面自建播放器，改用Host原生容器播放/后台暂停/释放和错误反馈；保存经统一流式Host且结束关闭失败不会报Saved。

生产源码：`app/src/main/java/com/labteto/dshmobile/local/presentation/LocalMediaCreationRuntime.kt`、`app/src/main/java/com/labteto/dshmobile/local/model/LocalMediaVideoResume.kt`、`app/src/main/java/com/labteto/dshmobile/local/model/LocalVideoTaskLeaseManager.kt`、`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiAttachmentMediaViewer.kt`、`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiDocumentTransfer.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/local/model/LocalMediaVideoResumeTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/model/LocalVideoTaskLeaseManagerTest.kt`、`app/src/test/java/com/labteto/dshmobile/ui/host/UiHostStreamDocumentSaveTest.kt`

### S4-03 · 动态证据有效性/核验卡

证据卡订阅原事件、目标和原生文件变更，人工/原始机器条件/未知状态分开。文件订阅补上未手工关联的原Goal成果路径，删除/重命名/重建均重新挂载；原目标/文件SHA/来源代次不符即失效，原工具写入与完整核验提交共享工作区读租约。

生产源码：`app/src/main/java/com/labteto/dshmobile/local/LocalWorkEvidenceUiAdapter.kt`、`app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkEvidenceFileObserver.kt`、`app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkMachineVerification.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/local/work/LocalWorkEvidenceFileObserverTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/work/LocalWorkMachineVerificationTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinatorTest.kt`

### S4-07 · 历史编辑/分页/删除/版本迁移与完整恢复

已知Session V0/V1/V2、旧messages完整快照和当前UiDocument v1由各自唯一升级边界解读，原数据不重写、未来版本显式阻断。旧快照迁移全程使用Session跨进程写租约；文本/表格/图表编辑CAS、批量原子墓碑、分页和晚到发布沿唯一EventLog。新增双Owner迁移竞争与冷读修改/删除恢复回归。

生产源码：`app/src/main/java/com/labteto/dshmobile/local/session/LocalTranscriptSnapshotMigration.kt`、`harness-core/src/main/kotlin/com/labteto/dshmobile/harness/session/SessionEventSchemaCodec.kt`、`app/src/main/java/com/labteto/dshmobile/local/session/LocalUiDocumentSessionStore.kt`、`app/src/main/java/com/labteto/dshmobile/local/presentation/LocalUiDocumentUiAdapter.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/local/LocalTranscriptSnapshotMigrationTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/session/LocalUiDocumentSessionStoreTest.kt`、`harness-core/src/test/kotlin/com/labteto/dshmobile/harness/session/SessionEventSchemaCodecTest.kt`

### S4-08 · 敏感草稿/留存/节点/历史/重组预算

原会话/附件/动态表单加密存储保留，补齐媒体创作提示词、故事标题备注阶段、动态界面设计和表格筛选的加密SavedState；旧Compose MutableState仅一次读取，后续只保存密文，跨scope不恢复。动态文档使用Session唯一当前窗口派生检查点，冷重建只接续其后事件；损坏/丢失检查点由原日志流式重建，墓碑一并保留，无第二文档账本。

生产源码：`ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiPrivateTextState.kt`、`app/src/main/java/com/labteto/dshmobile/ui/host/LocalEncryptedUiDraftStore.kt`、`app/src/main/java/com/labteto/dshmobile/local/session/LocalUiDocumentSessionStore.kt`、`app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionProjectionCheckpoints.kt`

回归源码（未运行）：`app/src/test/java/com/labteto/dshmobile/ui/host/UiPrivateTextStateTest.kt`、`app/src/test/java/com/labteto/dshmobile/ui/host/LocalEncryptedUiDraftStoreTest.kt`、`app/src/test/java/com/labteto/dshmobile/local/session/LocalUiDocumentSessionStoreTest.kt`

## 验证边界

本地没有可用的Gradle发行包，上一轮下载已因网络限制失败；本轮遵循代码交付范围，不安装工具、不重复下载。架构/性能、测试质量、Kotlin风险、场景矩阵、功能树、UI边界及差异格式按真实命令结果记录；这些静态结果不能替代Kotlin编译与测试运行。

当前机器台账：29项代码实现待验证、5项专项验证待执行、0项代码缺口。正式生产准入、合并、发布均未执行。

实际执行的架构、性能、测试质量、Kotlin风险、UI场景矩阵、设计边界、功能树、Android安全、CI完整性及V3台账静态检查均通过；编译与Kotlin/JVM/Compose测试仍未运行。

## 最新 #705 的实现完整性复核补充

复核基准：`4ade8686ecd08f70fcdd0a8e117d08cf9cc7e45b`，主线 `e03573fed4a3a77f9f6db850fb0522e367a678d2`。本轮继续核对原13项的当前调用链，不将上述台账状态视为行为证明，也不将本轮范围扩大为 Agent Engine V3 的24项整项完成。

本轮确认并修复两处缺口：

| 缺口 | 根因与修正 | 回归源码 |
| --- | --- | --- |
| 历史卡片已从EventLog成功读取，却因检查点写入失败变成页面加载失败 | 可丢弃的窗口加速侧车错误地影响权威读取结果；可选写入边界只隔离并记录IOException，原日志读取、业务写入、取消及其他错误语义保留 | `LocalUiDocumentSessionStoreTest.failedOptionalCheckpointWriteDoesNotHideDurableHistory`：以非空目录阻挡原子替换，验证历史仍可读、移除冲突后可重新冷建检查点 |
| 选中的Work工具详情保留旧授权状态 | 列表收到Owner新投影，但详情仍持有选择时的旧对象；现在同一执行与结果更新选中回执，执行替换、结果变化或记录移除则撤下旧详情 | `V6WorkToolActivityPanelTest.selectedReceiptRefreshesAuthorizationWithoutReusingAReplacedExecution`：拒绝/允许刷新、替换执行、终态变化和移除 |

这两处属于原13项的恢复与展示边界补齐，不增加新权限、业务Owner或持久化事实源。功能树入口数量与归属不变，源码定位随代码同步。新增测试未运行；原13项继续为代码待验证，不能认定运行完整、验收完成或可发布。最新基准CI读取时为pending，尚无编译与行为结果。
