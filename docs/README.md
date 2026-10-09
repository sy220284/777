# 文档

本目录只保留当前主线仍然有效的文档。

历史版本变化、旧阶段实施记录和已经淘汰的设计方案不再作为独立文档保留；需要追溯时使用 Git 历史和 `CHANGELOG.md`。

面向用户的产品介绍、使用方式和当前界面截图见 [项目 README](../README.md)。

## 当前文档

| 文档 | 内容 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构 3.0：Feature 所有权、Shared Capability、应用组合根、极薄 Runtime Kernel 与架构门禁 |
| [FEATURE-TREE.zh-CN.md](FEATURE-TREE.zh-CN.md)／[交互版](FEATURE-TREE.zh-CN.html) | 全功能中文树：模块、功能、子功能、细分能力、代码定位及未合并 PR 标记；随代码同步维护 |
| [FUNCTION-AUDIT.zh-CN.md](FUNCTION-AUDIT.zh-CN.md) | 当前完整功能树逐节点审计台账、确定问题及验证缺口；依据最新主线持续更新 |
| [SYSTEM-AUDIT-GUIDE.zh-CN.md](SYSTEM-AUDIT-GUIDE.zh-CN.md) | 全量系统联审权威规范：按功能架构、数据状态、生命周期、外部能力、性能、体验与工程质量分组执行审计 |
| [SHARED-AUDIT-CONCLUSIONS.zh-CN.md](SHARED-AUDIT-CONCLUSIONS.zh-CN.md) | 可共享审计结论库：按链路闭环、数据字段、配置依赖与所有权归组的可复用审计规则 |
| [PROTOCOL.md](PROTOCOL.md) | 当前远程 Web 协议与本机 Session / Agent 协议边界 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | 当前 Android、ABI、本机语义参考和远程 Harness / relay 支持矩阵 |
| [SECURITY.md](SECURITY.md) | 凭据、工作区、工具、恢复、Web、设备、数据和更新安全边界 |
| [ANDROID-HARNESS-STATUS.zh-CN.md](ANDROID-HARNESS-STATUS.zh-CN.md) | 当前 Android 原生 Harness 已实现能力与平台限制 |
| [ANDROID-HARNESS-ROADMAP.zh-CN.md](ANDROID-HARNESS-ROADMAP.zh-CN.md) | 当前版本之后仍需继续收敛的架构与工程工作 |
| [VALIDATION.md](VALIDATION.md) | CI、差分验证、Android 16 / 17、架构与性能门禁 |
| [UI-UX.zh-CN.md](UI-UX.zh-CN.md) | 当前聊天 / 工作模式、人物调节、Token 页面与交互规范 |
| [UI-ARTIFACTS.md](UI-ARTIFACTS.md) | 当前效果图清单与截图归档规则 |
| [design/mobile-ui.md](design/mobile-ui.md) | 当前手机侧边栏、人物图集与主要子页面设计稿及交互承载规范 |

## 其他入口

- 仓库工程执行规范：[../AGENTS.md](../AGENTS.md)
- 项目总览：[../README.md](../README.md)
- 版本变化：[../CHANGELOG.md](../CHANGELOG.md)
- 第三方声明：[../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)
- 官方 Harness 锁定基线：[../upstream/deepseek-harness.lock.json](../upstream/deepseek-harness.lock.json)

## 维护规则

文档只写当前真实实现。

当功能、架构、协议、版本、CI 或 UI 发生实质变化时：

1. 同步修改对应当前文档；若功能层级、归属、入口或共享能力有变化，必须同时更新中文功能树与交互视图，并核对未合并 PR 标记。
2. 删除已经失效的正文，不在当前文档继续叠加“旧版说明”。
3. 历史变化写入 `CHANGELOG.md`，不要复制成新的阶段文档。
4. 文件名不再使用已经结束的阶段编号、旧版本号或临时项目代号。
5. 文档中的完成状态必须能够从当前代码或 CI 验证。
6. 未列入“当前文档”表的阶段执行计划、迁移记录和历史说明不属于现行规范；需要追溯时使用 Git 历史与 `CHANGELOG.md`。
