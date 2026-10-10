# 777 功能树代码存废核实结论（2026-10-10）

> **核查依据：实际 Git 代码树与源码**，快照 `0ebb025ab1213b0c81fecdcb51f7e677dc7d9717`；没有使用 PR 描述、历史功能规划或旧审计意见作为存在性判断。功能树当前 **545 节点、447 末级项目**。每项来源详见 [447 项逐项源码台账](FEATURE-CODE-EVIDENCE.zh-CN.md)，原文档和 [HTML 交互树](FEATURE-TREE.zh-CN.html) 同步保留。

## 存废判定结论

1. **已确认退役并从树中剔除：2 项**——“模拟 Harness 服务端”“真实中继协议一致性测试”。当前代码树无 `mock-harness/`，本机的 `LocalFeatureCatalog` 和 `LocalPluginComposition` 装配链也没有旧主机配对、远程会话和中继控制；`harness/README.md` 只是退役说明，不属于现行能力。
2. **其他 447 个末级节点：尚无足以认定“代码已删除或已退役”的新增确证项**。逐项路径在当前仓库中有对应源文件或测试证据，且所属 23 个模块的实际入口/所有者均能定位。但静态“存在”不可推导所有细分功能都运行可达或业务正确。
3. **确认应保留的非远程能力**：本机 Chat/Work、项目、自动化、技能、模型、GitHub、MCP、LSP、Android 设备、视觉、Webhook、PDF、终端、更新与发布。在当前应用装配中，`LocalFeatureCatalog` 有 7 个产品 Feature，`LocalHarnessScreen` 挂载 7 个页面 Contribution；`LocalPluginComposition` 登记 9 个本地/外部工具插件，包括本机运行时、MCP、GitHub、LSP、设备、视觉、自动化、Webhook 与内置工具。
4. **证据纠正：85 项源码定位已经按真实代码改正**，不再用无关 DTO、接口、旧源码路径冒充具体实现；仅根据文件名或词面匹配的自动化判断会制造假阳性，已经撤销相关启发式 CI 检查器。
5. **代码验收和行为验收分开**：仓库中有 792 个生产 Kotlin/Java 源文件、409 个测试 Kotlin/Java 文件；128 个树节点涉及的 160 条 `@Test fun` 引用已在实际 63 个测试文件中找到；40 个节点有专属源码定位，其余 279 个仍仅具有归属候选证据。这意味着**不能诚实地宣布 447 个子功能都完成逐方法、逐副作用、重启恢复的验收**。它们被保留的理由是当前没有充分代码证据判断已经被剔除；该结论不能替代完整度审计。

## 23 个现行模块与实际源码入口

| 功能模块 | 树中末级项 | 现行入口／主要 Owner（源码） |
|---|---:|---|
| 应用外壳与统一交互 | 25 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt) |
| 聊天功能 | 62 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatComposition.kt) |
| 工作与智能体功能 | 86 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkComposition.kt) |
| 项目功能 | 9 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/project/LocalProjectFeatureRuntime.kt) |
| 自动化功能 | 27 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/automation/HarnessAutomationScheduler.kt) |
| 工具与扩展功能 | 61 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/tools/LocalPluginComposition.kt) |
| 设置与个人配置 | 37 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/ui/screens/settings/SettingsScreen.kt) |
| 共享会话与事件 | 23 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRepository.kt) |
| 共享模型与路由 | 23 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/model/LocalModelGateway.kt) |
| 共享智能体核心 | 17 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/runtime/LocalAgentRunCoordinator.kt) |
| 共享工具执行 | 7 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolPolicy.kt) |
| 共享记忆 | 7 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/memory/MemoryStore.kt) |
| 共享资源与任务 | 5 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/jobs/LocalJobManager.kt) |
| 共享交互 | 4 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/interaction/LocalInteractionCoordinator.kt) |
| 共享用量和诊断 | 5 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/TokenUsageAnalytics.kt) |
| 共享存储与安全 | 6 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/security/KeystorePreferenceSecretStore.kt) |
| 轻量进程内核 | 5 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeKernel.kt) |
| Android 本机运行时 | 5 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/harness-runtime-android/src/main/kotlin/com/labteto/dshmobile/runtime/AndroidRuntimePlugin.kt) |
| 底层协议与兼容 | 3 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/local/model/LocalModelGateway.kt) |
| 外部互通适配 | 4 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/mcp/McpToolBridgePlugin.kt) |
| 构建与持续集成 | 10 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/.github/workflows/ci.yml) |
| 测试与语义验证 | 11 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/reference-validation/src/main/kotlin/com/labteto/dshmobile/reference/AdvancedConformanceMain.kt) |
| 发布与升级 | 5 | [实际代码入口](https://github.com/sy220284/777/blob/0ebb025ab1213b0c81fecdcb51f7e677dc7d9717/app/src/main/java/com/labteto/dshmobile/update/UpdateInstaller.kt) |

## 精确口径

**本次“存废核实”结论：已确认过时且应删除的节点 = 2（此前已剔除）；本轮新确认需要删除的节点 = 0；当前保留 = 447。** 任何候选子功能若进一步发现生产调用链断裂，应先以具体代码、不可达证据和对应回归测试确认，再在同一修改中更新两个树视图及证据台账。现有证据仍不支持把全部 447 项标为“实现完整／全部已验收”。
