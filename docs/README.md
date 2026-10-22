# 文档

现行权威规范和验收入口列在“当前文档”中。保留溯源价值的阶段性修复记录统一放入 [`archive/`](archive/README.md)；它们不代表当前实现。

产品与业务运行说明见 [项目 README](../README.md)。当前已恢复 V6 原生 Android 界面；实际功能、运行边界与体验以当前主分支代码和验收结果为准。

## 当前文档

| 文档 | 内容 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构 3.0：Feature 所有权、Shared Capability、应用组合根、极薄 Runtime Kernel 与架构门禁 |
| [FEATURE-TREE.zh-CN.md](FEATURE-TREE.zh-CN.md)／[交互版](FEATURE-TREE.zh-CN.html) | 全功能中文树：模块、功能、子功能、细分能力、代码定位及未合并 PR 标记；随代码同步维护 |
| [FEATURE-TREE-AUTOMATION.zh-CN.md](FEATURE-TREE-AUTOMATION.zh-CN.md) | 主线与定时全源码扫描、源码功能登记、自动更新功能树和代码候选索引的使用及验证边界 |
| [FEATURE-REALITY-AUDIT.zh-CN.md](FEATURE-REALITY-AUDIT.zh-CN.md)／[逐项JSON](FEATURE-REALITY-AUDIT.auto.json) | 独立读取真实生产代码、路由、工具注册、页面与测试，逐项对照功能树；区分描述数、静态证据和真实执行状态 |
| [FEATURE-EXISTENCE-AUDIT.zh-CN.md](FEATURE-EXISTENCE-AUDIT.zh-CN.md)／[逐项JSON](FEATURE-EXISTENCE-AUDIT.auto.json) | 专项排查原14项缺入口及76项弱证据功能：独立存在、共享实现、历史兼容、真实测试声明与待验收状态 |
| [FUNCTION-AUDIT.zh-CN.md](FUNCTION-AUDIT.zh-CN.md) | 当前完整功能树逐节点审计台账、确定问题及验证缺口；依据最新主线持续更新 |
| [SYSTEM-AUDIT-GUIDE.zh-CN.md](SYSTEM-AUDIT-GUIDE.zh-CN.md) | 全量系统联审权威规范：按功能架构、数据状态、生命周期、外部能力、性能、体验与工程质量分组执行审计 |
| [SHARED-AUDIT-CONCLUSIONS.zh-CN.md](SHARED-AUDIT-CONCLUSIONS.zh-CN.md) | 可共享审计结论库：按链路闭环、数据字段、配置依赖与所有权归组的可复用审计规则 |
| [PROTOCOL.md](PROTOCOL.md) | 当前本机 Session / Agent 协议边界 |
| [COMPATIBILITY.md](COMPATIBILITY.md) | 当前 Android、ABI 与本机语义参考支持矩阵 |
| [SECURITY.md](SECURITY.md) | 凭据、工作区、工具、恢复、Web、设备、数据和更新安全边界 |
| [ANDROID-HARNESS-STATUS.zh-CN.md](ANDROID-HARNESS-STATUS.zh-CN.md) | 当前 Android 原生 Harness 已实现能力与平台限制 |
| [ANDROID-HARNESS-ROADMAP.zh-CN.md](ANDROID-HARNESS-ROADMAP.zh-CN.md) | 当前版本之后仍需继续收敛的架构与工程工作 |
| [VALIDATION.md](VALIDATION.md) | CI、差分验证、Android 16 / 17、架构与性能门禁 |
| [history/UI-BACKEND-RESET-20261017.zh-CN.md](history/UI-BACKEND-RESET-20261017.zh-CN.md) | 旧后端保留版、UI 清空及禁发标志历史记录（已退出当前状态） |

## 历史归档与扫描证据

- [`archive/`](archive/README.md)：已结束阶段的修复、功能退役记录，仅供溯源，不能覆盖现行规范。
- `FEATURE-*.auto.json`、验收矩阵和功能树来源索引：扫描证据或生成文件，不是独立产品规格，更不等于实机运行验证。
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
5. 文档中的完成状态必须能够从当前代码或 CI 验证。
6. 未列入“当前文档”表的阶段执行计划、迁移记录和历史说明不属于现行规范；需要追溯时使用 Git 历史与 `CHANGELOG.md`。
