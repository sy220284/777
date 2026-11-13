# 文档

现行文档列在“当前文档”中。保留溯源价值的阶段性修复记录统一放入 [`archive/`](archive/README.md)；它们不代表当前实现。

产品与业务运行说明见 [项目 README](../README.md)。当前已恢复 V6 原生 Android 界面；实际功能和运行边界以当前主分支代码为准。

## 当前文档

| 文档 | 内容 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构 3.0：Feature 所有权、Shared Capability、应用组合根与 Runtime Kernel |
| [FEATURE-TREE.zh-CN.md](FEATURE-TREE.zh-CN.md)／[交互版](FEATURE-TREE.zh-CN.html) | 全功能中文树：模块、功能、子功能、细分能力、代码定位及未合并 PR 标记；随代码同步维护 |
| [SYSTEM-AUDIT-GUIDE.zh-CN.md](SYSTEM-AUDIT-GUIDE.zh-CN.md) | 全量系统联审权威规范：按功能架构、数据状态、生命周期、外部能力、性能、体验与工程质量分组执行审计 |
| [SHARED-AUDIT-CONCLUSIONS.zh-CN.md](SHARED-AUDIT-CONCLUSIONS.zh-CN.md) | 可共享审计结论库：按链路闭环、数据字段、配置依赖与所有权归组的可复用审计规则 |
| [PROTOCOL.md](PROTOCOL.md) | 当前本机 Session / Agent 协议边界 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | 当前 Android、ABI 与本机语义参考支持矩阵 |
| [SECURITY.md](SECURITY.md) | 凭据、工作区、工具、恢复、Web、设备、数据和更新安全边界 |
| [ANDROID-HARNESS-STATUS.zh-CN.md](ANDROID-HARNESS-STATUS.zh-CN.md) | 当前 Android 原生 Harness 已实现能力与平台限制 |
| [ANDROID-HARNESS-ROADMAP.zh-CN.md](ANDROID-HARNESS-ROADMAP.zh-CN.md) | 当前版本之后仍需继续收敛的架构与工程工作 |
| [VALIDATION.md](VALIDATION.md) | 本地编译与按需调试命令 |
| [history/UI-BACKEND-RESET-20261017.zh-CN.md](history/UI-BACKEND-RESET-20261017.zh-CN.md) | 旧后端保留版、UI 清空及禁发标志历史记录（已退出当前状态） |

## 历史归档与扫描证据

- [`archive/`](archive/README.md)：已结束阶段的修复、功能退役记录，仅供溯源，不能覆盖现行规范。
- 已取消仓库级验收矩阵与证明材料生成流程；历史变更从 Git 提交追溯。
- 当前实际行为以现行权威文档、代码和测试为准；历史版本沿革从根目录 `CHANGELOG.md` 与 Git 提交查看。

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
3. 历史变化写入 `CHANGELOG.md`；必须保留的专项溯源记录统一放入 `archive/` 并注明非现行规范。
4. 文件名不再使用已经结束的阶段编号、旧版本号或临时项目代号。
5. 文档应准确描述当前代码实现，不附加验收材料要求。
6. 未列入“当前文档”表的阶段执行计划、迁移记录和历史说明不属于现行规范；需要追溯时使用 Git 历史与 `CHANGELOG.md`。
