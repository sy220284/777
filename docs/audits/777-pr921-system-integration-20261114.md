# 777 #921 系统复核落实与验收清单

> 2026-11-14。基准为 #916 合并后主线；以 PR #921 当前 `head_sha` 与最终集成主线的真实代码、CI 和设备运行回执为准。本文描述已提交的改造、验收入口及尚需设备证实的边界，不以文档或单项测试替代产品级交付。

## 1. 已通过源码修改落实的共同根因

| 原根因 | 唯一事实源 / 改造后的路径 | 防回归 |
|---|---|---|
| 来源 URL 尾部匹配跨仓库误判 | `.github-source.json` 经 `parseGitHubSkillUpdateSource` 校验，`LocalGitHubSkillImporter.installedSourceIdentity` → `UiSkillSourceIdentity` → 所有插件列表、详情、批量安装判断 | `ExtensionCatalogPresentationTest` 跨仓库同路径、可变提交、无凭证；`PluginInventoryBrowserRegressionTest` 已安装成员技能 |
| MCP “已登记/已授权/有工具”混作一个条件 | Tool Owner 真实服务器信息 + 单次执行授权；安装避开已知服务再次 OAuth，正式可用仍要求已发现可用工具 | `McpSkillRequirementStatusTest` 与两条插件/技能详情 Android 回归 |
| 登录/令牌更新先断开旧连接 | `LocalMcpOAuthCoordinator` 只准备凭据，`LocalToolsUiFacade.switchMcpHttpConnection` 检查身份变更后提交；OAuth、Bearer、刷新使用统一连接切换与回滚，撤销/清凭据/手动连接序列化 | `LocalMcpCredentialStoreAndroidTest` 凭据轮换、回滚、并发新写保护 |
| 一包多技能装完却无续授权操作 | 插件详情将成员技能来源和依赖投影为「继续完成授权」，复用已有安装协调器，只处理缺失依赖 | `PluginInventoryBrowserRegressionTest` 已安装包续授权 |
| UI 直接依赖具体 Facade | `ToolsViewModel → ToolsManagementUiPort → LocalToolsUiFacade`（Hilt 实际绑定） | 编译、Dagger 绑定及 Android UI 回归 |
| 历史分页两套算法、实时/历史字段漂移 | `TranscriptHistoryPager<T,C>` 一套有界分页、去重和切换检查；`projectTranscriptHistoryMessage` 统一历史/实时投影。 `UiMessageSnapshot` 具备媒体、附件、未知块、工具状态及说话人。原生 V6 仍持有富字段消息确保完整渲染 | `LocalTranscriptHistoryBudgetTest`、`LocalTranscriptUiProjectionTest`、`LocalTranscriptHistoryRaceStressTest` |
| 通用质量类空转、无生产消费者 | 删除 `LocalOutputQualityPipeline` 及其仅测试引用，保留 Work 真正的 Goal/文件/执行证据校验路径和 `LocalOutputQualityResult` | Work 完成证据、拒绝虚假成功及工具回执的既有测试 |
| MCP Worker 消息发送者未验证真实 UID | `McpPluginWorkerService` 验证 Messenger 的 `sendingUid` 为当前 app UID，原有 token、owner Binder、服务非导出检查继续生效 | `McpPluginWorkerCallerAndroidTest` 真实绑定发送消息/跨进程 PID |

## 2. 用户路径验收

1. 发现 → 详情 → 一键安装：插件包仅一条市场记录；子技能、依赖位于详情；无额外配置直接可用。
2. 已安装无需授权：状态从本机实际来源恢复；再次操作不会重复下载。
3. 需要授权：官方 HTTPS 端点自动预置，用户在浏览器授权；取消后旧连接和已安装文件保持可用。
4. 已登记但工具为空：插件和普通技能都提供「检查连接」而不是重新 OAuth，也不误标可运行。
5. 多依赖：失败时保留之前成功项，重新进入插件详情出现「继续完成授权」，只处理真实缺项；拒绝授权后不循环弹窗。
6. 再启动/断网/服务故障：从持久事实恢复已安装、已授权、已登记和待验证；不自动重放已经可能发生外部效果的调用。
7. 会话：同一页在冷加载、向上翻页、前台流式追加、A→B→A 快速切换时不丢消息、不重复；图片、工具状态和说话人展示字段保持一致。
8. UI：四种手机宽度（320/360/393/412dp）× 三主题和放大字体均可读；入口和主操作正确反映执行状态。

## 3. 与当前 Android 安全模型相符的明确边界

MCP Worker 是独立进程，并通过应用 UID、传输 owner Binder、原有进程调用账本和网络策略约束命令；仍与主应用共享 UID。它是运行稳定性和故障隔离机制，不提供不同 UID 的权限沙盒。第三方 stdio 执行依赖应用可执行环境，HTTPS 访问依赖宿主网络权限；独立 UID 需要完整的 Binder 网络/文件/进程代理、证据字段、权限收敛和设备验证，不能通过单改 `isolatedProcess` 冒充完成。现有功能应保留。若产品承诺独立 UID，则这一部分仍须以独立真实设备测试证明后才能标记完成。

Figma Starter 的 MCP 限额阻断在线画布读取与写入；本轮按照实际 V6 Compose、权威 UI 规范与 Android 设备测试复核，不宣称已经同步在线可编辑 Figma 图层。

## 4. 最终集成放行证据

- PR 最终 `head_sha` 与相同提交的 Android 能力契约、完整单测、编译和 Android 36 真机式模拟器点击回执对应；
- 最新 `main` 与 PR 组合无冲突，检查既有 #916 插件市场、#917 会话存储、#919 工具执行恢复能力；
- 同步更新 `docs/ARCHITECTURE.md`、`docs/UI-UX.zh-CN.md` 和市场设计契约，保护原有安装和功能入口；
- 仅用真实回执标记通过、失败或未验证。尚未取得真实运行结果的场景应保持明确未验证状态。

**本文件是代码和测试入口台账，不是验收成功声明。**
