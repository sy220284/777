# 777 文档导航

这里集中列出仍在使用的产品、架构和开发资料。已结束的审计报告、旧 PR 台账、过时的设计交付记录以及静态功能树已从当前目录清理；历史内容可通过 Git 提交与 [CHANGELOG](../CHANGELOG.md) 追溯。

应用概览与安装说明见 [项目 README](../README.md)。产品能力及限制以最新生产代码和实际用户入口为准。

## 现行产品与架构

| 文档 | 用途 |
|---|---|
| [架构](ARCHITECTURE.md) | Feature 所有权、Shared Capability、运行时核心与模块关系 |
| [Android 能力与限制](ANDROID-HARNESS-STATUS.zh-CN.md) | 本机执行能力、操作系统限制及当前实现 |
| [后续架构方向](ANDROID-HARNESS-ROADMAP.zh-CN.md) | 仍待解决的系统级能力问题 |
| [协议](PROTOCOL.md) | Session、Agent 与工具通信边界 |
| [兼容性](COMPATIBILITY.md) | Android、ABI、运行环境 |
| [安全](SECURITY.md) | 凭据、工作区、工具授权和数据保护 |
| [本地存储架构](LOCAL-STORAGE-ARCHITECTURE-20261024.zh-CN.md) | SQLite、媒体引用、备份与数据安全 |
| [模型配置行为](MODEL-EFFECTIVE-CONTRACT.zh-CN.md) | 模型路由、有效配置及使用约束 |
| [媒体能力](MEDIA-CAPABILITIES.zh-CN.md) | 多模态创作入口、数据归属及供应商接口 |
| [人物聊天](CHAT-FREE-RESPONSE.zh-CN.md) | 人物回复、记忆和故事语义 |
| [自我改进机制](AGENT-IMPROVEMENT.md) | 经验形成、发现追查和可复用改进 |
| [UI / UX](UI-UX.zh-CN.md) | 产品交互与设计原则 |
| [V6 原生界面设计](design/v6/README.zh-CN.md) | 当前 Compose 实现、组件和视觉资源 |

## 开发与审查

- [工程与调试](DEVELOPMENT.md)：开发环境、构建和问题排查。
- [按需测试](VALIDATION.md)：具体问题使用的构建、测试及设备调试方法。
- [系统审查方法](SYSTEM-AUDIT-GUIDE.zh-CN.md)与[可复用审查规律](SHARED-AUDIT-CONCLUSIONS.zh-CN.md)：提供排查思路，具体结论依据现行代码。
- [工程协作规范](../AGENTS.md)、[贡献指南](../CONTRIBUTING.md)。

## 正在开发的能力

PR [#895](https://github.com/sy220284/777/pull/895) 的实施状态见[能力接通清单](audits/pr895-capability-current-20261114.md)和[Codex 能力差异](audits/codex-777-capability-alignment-20261112.md)。这两份文档随该 PR 的实际代码变化更新。

## 文档维护

1. 当前功能变化直接修改对应的现行说明，避免重复建立多个状态台账。
2. 已结束的阶段报告、过期清单及其生成器从现行仓库清理，追溯时查看 Git 历史和 CHANGELOG。
3. 图标、原型和其他实际设计资产保留在各自目录；不把旧设计交付报告重新作为实现依据。
4. 文档准确说明功能、实现边界和待解决问题，不添加独立的验收材料或签收流程。
