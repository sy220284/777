# 777 文档导航

这里仅列出当前有效的产品、架构、开发与设计依据。功能事实以生产源码及实际设备验证为准；已合并 PR 的一次性报告通过 [Git 历史](https://github.com/sy220284/777/commits/main/docs/) 和 PR 讨论追溯。

## 产品、架构与能力

- [架构 3.0](ARCHITECTURE.md)：Feature 所有权、共享能力、状态事实源与运行边界。
- [Android 当前能力](ANDROID-HARNESS-STATUS.zh-CN.md)与[待办路线](ANDROID-HARNESS-ROADMAP.zh-CN.md)：已实现能力、平台限制及待核实的问题。
- [工具操作指南](AGENT-TOOL-USAGE.zh-CN.md)、[App Server 内部接口](APP-SERVER-API.md)：真实调用及 UI 契约。
- [协议](PROTOCOL.md)、[兼容性](COMPATIBILITY.md)、[安全边界](SECURITY.md)。
- [本地数据与备份](LOCAL-STORAGE-ARCHITECTURE-20261024.zh-CN.md)、[会话证据增量格式](SESSION-STORAGE-REQUEST-DELTA-V4.zh-CN.md)。
- [模型能力合同](MODEL-EFFECTIVE-CONTRACT.zh-CN.md)、[预算](MODEL-BUDGET-POLICY.zh-CN.md)、[默认协议与回复通道](MODEL-PROTOCOL-REPLY-DEFAULTS-20261020.zh-CN.md)。
- [媒体能力](MEDIA-CAPABILITIES.zh-CN.md)、[MiniMax 接入](MINIMAX-ADAPTATION.zh-CN.md)、[Kimi Code 接入](KIMI-CODE-ADAPTATION.zh-CN.md)。
- [人物与聊天](CHAT-FREE-RESPONSE.zh-CN.md)、[统一运行时](CHAT-UNIFIED-RUNTIME.zh-CN.md)、[人物档案](PERSONA-V4.md)、[主观日记](CHAT-DIARY-SUBJECTIVE-V2.zh-CN.md)。
- [Agent 自我改进](AGENT-IMPROVEMENT.md)、[网络准入规则](NETWORK-ACCESS-IMPLEMENTATION-GUIDE.zh-CN.md)。

## UI 与设计资产

- [现行交互原则](UI-UX.zh-CN.md)、[手机端体验说明](design/mobile-ui.md)。
- [V6 设计资源入口](design/v6/README.zh-CN.md)、[当前原生实现规范](design/v6/V6-CURRENT-IMPLEMENTATION.zh-CN.md)、[实现与验证状态](design/v6/STATUS.zh-CN.md)。
- [现行路由与页面清单](design/v6/current-ui.json)、[可编辑原型](design/v6/mobile/index.html)、[独立离线稿](design/v6/mobile/app.html)。
- [V6.4 待实施设计目标](design/v6/V6.4-VISUAL-MATERIAL-GUIDELINES.zh-CN.md)：与生产已完成事项分开记录。

## 开发、审查与法律

- [本机开发](DEVELOPMENT.md)、[按需验证](VALIDATION.md)、[联审规范](SYSTEM-AUDIT-GUIDE.zh-CN.md)、[复用审计结论](SHARED-AUDIT-CONCLUSIONS.zh-CN.md)。
- [协作规则](../AGENTS.md)、[贡献指南](../CONTRIBUTING.md)、[变更记录](../CHANGELOG.md)。
- [字体来源与许可](legal/fonts/PROVENANCE.md)。

## 文档维护

1. 功能变化核对生产代码和测试结果，在对应现行文档原位修订；阶段审计、临时计划和旧功能台账随完成清理。
2. 新旧状态分开：已合并实现、开放 PR 的设计、设备实测结果各自注明。能力中心调整见 [PR #950](https://github.com/sy220284/777/pull/950)，合并前以其分支为候选实现。
3. 路由、能力中心、图标或状态变动须同步更新 V6 的页面清单、原型源码和单文件离线稿；资源与许可保持完整。
4. 执行 `python3 tools/audit/check-docs.py` 和 `python3 docs/design/v6/build-standalone.py --check` 检查相对链接与设计源一致性，GitHub Actions 自动对相关 PR 执行。
