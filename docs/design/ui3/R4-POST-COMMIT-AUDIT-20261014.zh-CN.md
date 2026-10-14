# PR #705 后续提交完整增量源码审计（2026-10-14）

范围：`1bf65caf..934c2027`，含本轮前的 Work/文件/Next 增量、后续 Host/表格/群聊提交、合入主线 #711～#713 以及上一轮修正，共 120 个差异路径。删除的重复实现核对保留实现，生成 HTML/索引和 SVG 按生成源、引用与内容结构审阅。本文不是整个仓库的全量系统联审，也不是编译、测试或设备验收。

方法：按实际差异逐项追入口、数据源、Owner、准入、回执、取消/恢复和消费者。先读取现行代码，未将旧审计的候选自动认定为缺陷；不将尚未实现的 R4 工作登记为现有功能 Bug。复用 SC-001、SC-002、SC-009、SC-017、SC-015（见共享审计结论库），未新增重复共享结论。

## 覆盖结果

| 范围 | 源码审阅结论 | 证明边界 |
|---|---|---|
| 群聊及 V6 接线 | 已复核捕获身份、同一 Owner、异步 Queued、Shared RunHandle 提交栅栏；新建/模式入口已接线 | 群公告、图库删除联动等仍属未实现迁移，不是本轮已证明的故障 |
| Work 策略/读取 | 审批仍由真实 waiter 校验 nonce；计划/设备授权沿原 Owner；事件分页保留日志实例/reset 栅栏；成果仍检查真实文件 | 未证明设备授权/撤销的真实 E2E，也未冒充需求核验 |
| 文件/附件/Session 接续 | 文件仍走原沙箱/读取预算；异步预览失效检查及捕获接续身份存在；轻量二进制预览明确不提供播放器 | 完整媒体预览、进程恢复与全量 Shell 端口仍未实现 |
| Host/注册/Gradle | 注册拒绝未知/重复/缺场景包；Host 从已连接端口建立结构能力；只允许 Debug 切片，不是生产全包替换 | 已连接不是当前权限；没有将 Manifest/a11y 声明当设备证据 |
| 动态文档/表格/表单 | 闭合 Schema、预算、同卡 revision、历史逐卡回退、双包原生绘制；表格标明制作时快照 | 修正提交校验依赖异步快照；实时媒体/证据/自动发布仍是待实现功能 |
| Next 布局 | 新操作及附件在不可滚动主 Column 累积，会占用时间线/输入区；改为可滚动操作行及附件列表 | 结论基于测量链源码，尚未获得窄屏/IME/200% 字体设备回执 |
| 插件/材质 | 搜索说明、加载保留卡片、禁用正在更新的修改入口可见；材质移包与删除前 App 实现逐行一致，装饰 Canvas 不参与测量 | 没有把布局修复当 MCP 健康/插件故障隔离全量完成 |
| 设计/资源 | 7 路由、21 设置目的地、24 活动图标；SVG 未包含脚本/远端引用；离线 HTML 与源的关系明确 | 修正 palette generator 旧路径及演示 sheet 继承键；不宣称视觉/Figma/设备验收 |
| 新测试/CI/S0/文档 | 测试保持明确替身范围、断言未削弱；S0 结构门禁已接 CI；场景映射仍待验收；设计门禁读取迁后模块 | 本轮未运行测试、编译、静态门禁或跟进 CI；门禁源码存在不是验证通过 |

## 本轮确认并修正

1. 中高：同卡新定义已持久化而渲染 StateFlow 尚未更新时，旧 action request 能从旧快照获得校验。`LocalUiDocumentUiAdapter.submit` 改从 Session Owner 读取当前定义与当前消息集合，拒绝损坏回退、删除锚点及旧 revision；所有发布路径和提交共用前台锁。原实际审批 nonce 仍有效，未发现新增审批绕过。
2. 中：Next 持续新增的会话/群聊/文件/策略按钮与附件在主 Column 纵向累积，挤压 weight 时间线和输入。改为横向可滚动操作行与 LazyRow 附件；没有删除操作或降低触控尺寸。
3. 低：`create-preview.py` 仍读已删除的 App foundation 路径，移包后无法重新生成调色板素材。改读唯一 `:ui-pack-v6` 源，未建立第二色板。
4. 低：离线 `dialogs[state.sheet]` 会把 `?sheet=__proto__` 等继承属性当数组解构，导致演示打不开。改为 own-property 查询，未知值走既有说明；同时同步 #711 已合并口径并重生成离线 HTML。此处只影响设计演示，不涉及 App 权限。

以上是源码确认的修正，未宣称运行复现/修复验证成功。源码审计完成不意味着零缺陷或 R4 完成；设备行为、性能、SavedState 长期留存预算与全部 S2/S5 仍须按后续验证/实现安排处理。

## 逐文件覆盖索引

- `.github/scripts/check-ui-design-system-boundaries.py` — 门禁/CI 源码：检查对象与调用接线审阅，未运行。
- `.github/scripts/check-ui-r4-scene-matrix.py` — 门禁/CI 源码：检查对象与调用接线审阅，未运行。
- `.github/workflows/ci.yml` — 门禁/CI 源码：检查对象与调用接线审阅，未运行。
- `app/src/androidTest/java/com/labteto/dshmobile/ui/host/NextConversationOwnerPortIntegrationTest.kt` — 测试源码：断言与替身边界审阅，未运行。
- `app/src/androidTest/java/com/labteto/dshmobile/ui/host/NextConversationUiPackInteractionTest.kt` — 测试源码：断言与替身边界审阅，未运行。
- `app/src/androidTest/java/com/labteto/dshmobile/ui/screens/tools/PluginInventoryBrowserRegressionTest.kt` — 测试源码：断言与替身边界审阅，未运行。
- `app/src/androidTest/java/com/shenyan/designsystem/components/ShenyanMaterialQualityTest.kt` — 测试源码：断言与替身边界审阅，未运行。
- `app/src/main/java/com/labteto/dshmobile/local/LocalChatGroupUiAdapter.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/LocalConversationUiPortComposition.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/LocalWorkPolicyUiPortComposition.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/chat/LocalGroupChatMembershipCoordinator.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/interaction/LocalInteractionCoordinator.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/presentation/LocalUiDocumentUiAdapter.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/presentation/document/LocalUiDocumentCodec.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionFilesRuntime.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionModels.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsModels.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/work/LocalRequirementEvidence.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkApprovalCoordinator.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRuntime.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/ui/host/LocalConversationPackGate.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/ui/host/LocalConversationSliceHost.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/ui/host/LocalUiDocumentFormState.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalConversationSurface.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalDynamicDocument.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessViewModel.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalShellUiContribution.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalUiDocumentGenerateButton.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalWorkspaceFilesDialog.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/java/com/labteto/dshmobile/ui/screens/tools/PluginInventoryBrowser.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/main/res/values/strings.xml` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `app/src/test/java/com/labteto/dshmobile/local/LocalConversationUiPortTest.kt` — 测试源码：断言与替身边界审阅，未运行。
- `app/src/test/java/com/labteto/dshmobile/local/presentation/document/LocalUiDocumentCodecTest.kt` — 测试源码：断言与替身边界审阅，未运行。
- `app/src/test/java/com/labteto/dshmobile/ui/host/LocalConversationPackGateTest.kt` — 测试源码：断言与替身边界审阅，未运行。
- `app/src/test/java/com/labteto/dshmobile/ui/host/LocalUiDocumentFormStateTest.kt` — 测试源码：断言与替身边界审阅，未运行。
- `app/src/test/java/com/labteto/dshmobile/ui/host/UiPackRegistryTest.kt` — 测试源码：断言与替身边界审阅，未运行。
- `docs/FEATURE-REVIEW.auto.json` — 生成索引：按生成源/注册关系审阅。
- `docs/FEATURE-SOURCE-INDEX.auto.json` — 生成索引：按生成源/注册关系审阅。
- `docs/FEATURE-TREE.zh-CN.html` — 生成索引：按生成源/注册关系审阅。
- `docs/FEATURE-TREE.zh-CN.md` — 生成索引：按生成源/注册关系审阅。
- `docs/UI-3.0-IMPLEMENTATION.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/UI-SCENE-MATRIX.json` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/UI-UX.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/UI-3.0.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/mobile-ui.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/ui3/PLAN-R4.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/ui3/R4-GROUP-PORT-20261014.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/ui3/R4-STAGE-EXECUTION-20261014.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/ui3/UI-DOCUMENT-TABLE-R4-20261014.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/ui3/UI-PACK-REGISTRY-AUDIT-20261014.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/v6/README.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/v6/STATUS.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/v6/SY-ENTRY-ICONS.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/v6/V6-CURRENT-IMPLEMENTATION.zh-CN.md` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/v6/assets/file-icons-preview.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/file-icons/archive-light.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Account.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Advanced.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Appearance.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/ChatStyle.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Diary.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Gallery.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/GroupChat.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Memory.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Models.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/NewSession.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Notifications.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Performance.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Permissions.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Persona.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Quota.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Reader.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Search.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/SessionStorage.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Settings.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Tasks.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Tools.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Updates.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/Usage.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/icons/WorkspaceFiles.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/portraits/persona-1.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/portraits/persona-2.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/portraits/persona-3.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/assets/portraits/persona-4.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/build-standalone.py` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/v6/create-preview.py` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/v6/current-ui.json` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/v6/mobile/app.html` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/mobile/index.html` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/mobile/v6.css` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/v6/mobile/v6.js` — 规范/数据/设计源：当前与历史口径审阅。
- `docs/design/v6/sy-entry-icons.svg` — 设计资源/投影：按源、结构与引用审阅。
- `docs/design/v6/sync-design-package.py` — 规范/数据/设计源：当前与历史口径审阅。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/local/presentation/LocalArtifactUiItem.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/local/presentation/LocalToolActivityUiItem.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/local/presentation/LocalWorkHistoryPageUi.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/local/session/LocalConversationFiles.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/local/tools/LocalFileUiValues.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/local/work/LocalRequirementEvidenceLink.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/uicontract/ChatGroupUiPort.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/uicontract/ConversationUiPort.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/uicontract/FilesUiPort.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/uicontract/LocalUiDocumentBuilder.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/uicontract/UiDocument.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/uicontract/UiDocumentFormUiPort.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/uicontract/WorkPolicyUiPort.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/main/kotlin/com/labteto/dshmobile/uicontract/WorkReadUiPort.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-contract/src/test/kotlin/com/labteto/dshmobile/uicontract/LocalUiDocumentBuilderTest.kt` — 测试源码：断言与替身边界审阅，未运行。
- `ui-contract/src/test/kotlin/com/labteto/dshmobile/uicontract/UiDocumentTest.kt` — 测试源码：断言与替身边界审阅，未运行。
- `ui-host/build.gradle.kts` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/ConversationUiPack.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-host/src/main/kotlin/com/labteto/dshmobile/uihost/UiPackRegistry.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-pack-next/src/main/kotlin/com/labteto/dshmobile/uipack/next/NextChatGroupManager.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-pack-next/src/main/kotlin/com/labteto/dshmobile/uipack/next/NextConversationUiPack.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-pack-next/src/main/kotlin/com/labteto/dshmobile/uipack/next/NextDocumentGenerateButton.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-pack-next/src/main/kotlin/com/labteto/dshmobile/uipack/next/NextUiDocument.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-pack-next/src/main/res/values/strings.xml` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
- `ui-pack-v6/src/main/kotlin/com/shenyan/designsystem/components/ShenyanMaterial.kt` — 生产改动：差异与相关调用链审阅（含删除/迁移）。
