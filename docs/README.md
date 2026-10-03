# 文档

本目录只保留当前主线仍然有效的文档。

历史版本变化、旧阶段实施记录和已经淘汰的设计方案不再作为独立文档保留；需要追溯时使用 Git 历史和 `CHANGELOG.md`。

面向用户的产品介绍、使用方式和当前界面截图见 [项目 README](../README.md)。

## 当前文档

| 文档 | 内容 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 当前 8 模块结构、app 内 capability runtime、状态投影、Engine / Coordinator 边界 |
| [SYSTEM-AUDIT-GUIDE.zh-CN.md](SYSTEM-AUDIT-GUIDE.zh-CN.md) | 全量系统联审权威规范：顶层审计视角、具体维度、多轮执行方法、问题分级与输出标准 |
| [PROTOCOL.md](PROTOCOL.md) | 当前远程 Web 协议与本机 Session / Agent 协议边界 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | 当前 Android、ABI、本机语义参考和远程 Harness / relay 支持矩阵 |
| [SECURITY.md](SECURITY.md) | 凭据、工作区、工具、恢复、Web、设备、数据和更新安全边界 |
| [ANDROID-HARNESS-STATUS.zh-CN.md](ANDROID-HARNESS-STATUS.zh-CN.md) | 当前 Android 原生 Harness 已实现能力与平台限制 |
| [ANDROID-HARNESS-ROADMAP.zh-CN.md](ANDROID-HARNESS-ROADMAP.zh-CN.md) | 当前版本之后仍需继续收敛的架构与工程工作 |
| [VALIDATION.md](VALIDATION.md) | CI、差分验证、Android 16 / 17、架构与性能门禁 |
| [UI-UX.zh-CN.md](UI-UX.zh-CN.md) | 当前聊天 / 工作模式、人物调节、Token 页面与交互规范 |
| [UI-ARTIFACTS.md](UI-ARTIFACTS.md) | 当前效果图清单与截图归档规则 |
| [design/navigation-and-pages-v2.md](design/navigation-and-pages-v2.md) | 当前导航、侧边栏与主要功能子页面设计稿及交互承载规范 |

## 其他入口

- 仓库工程执行规范：[../AGENTS.md](../AGENTS.md)
- 项目总览：[../README.md](../README.md)
- 版本变化：[../CHANGELOG.md](../CHANGELOG.md)
- 第三方声明：[../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)
- 官方 Harness 锁定基线：[../upstream/deepseek-harness.lock.json](../upstream/deepseek-harness.lock.json)

## 维护规则

文档只写当前真实实现。

当功能、架构、协议、版本、CI 或 UI 发生实质变化时：

1. 同步修改对应当前文档。
2. 删除已经失效的正文，不在当前文档继续叠加“旧版说明”。
3. 历史变化写入 `CHANGELOG.md`，不要复制成新的阶段文档。
4. 文件名不再使用已经结束的阶段编号、旧版本号或临时项目代号。
5. 文档中的完成状态必须能够从当前代码或 CI 验证。
