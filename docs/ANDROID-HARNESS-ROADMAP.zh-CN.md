# Android 原生 Harness 路线

本文只记录当前版本之后仍需要继续收敛的工作。

当前实现状态见 [ANDROID-HARNESS-STATUS.zh-CN.md](ANDROID-HARNESS-STATUS.zh-CN.md)。

## 总目标

继续保持：

```text
本机原生 Harness
+ 官方语义可验证
+ Android 生命周期正确
+ 能力边界清楚
+ 性能可控
+ 用户体验稳定
```

后续工作不再以“大阶段迁移”为单位，而是围绕实际剩余边界逐项收口。

## 1. 结构化工具结果

当前部分 Android 工具失败仍以模型可读文本为主要载体。

目标统一：

```text
success
errorCode
retryable
content
audit metadata
```

要求：

- 模型可读。
- UI 可投影。
- 日志可诊断。
- Retry policy 可判断。
- 不需要解析自然语言错误文本。

## 2. 语义压缩检查点

已完成第一层 typed Work Checkpoint：

- 当前目标。
- 关键约束。
- 已确认决定。
- 已失败尝试。
- 未完成事项。
- 阶段进展。
- 重要产物。
- 已涉及工具。
- 随模型历史持久化。
- 安全进程恢复时自动进入续跑上下文。

后续只继续增强检查点的 UI 可视化、人工编辑 / 确认和跨设备导入边界，不再把一段自由文本摘要当作唯一恢复事实。

## 3. 完整 Android 运行链差分

当前验证已经拆分为“官方 golden 差分”和“本机行为契约”两层；恢复 / 审批已进入本机行为契约。

继续扩展到：

- Prompt 注入。
- 权限。
- 审批。
- Ask User。
- Cancel / resume。
- Session 恢复。
- 子代理继承。
- Plugin Tool View。
- Web / Vision。
- Android device。
- Automation。

差分测试关注行为，不要求代码结构和官方实现一致。

## 4. 恢复语义

继续区分：

```text
状态可恢复
≠
外部进程可透明续跑
```

需要加强：

- Android 进程死亡后的 run 重建。
- 长任务的显式 resume contract。
- 外部程序中断后的用户可理解状态。
- 未知副作用的继续决策。
- 终端 / MCP / LSP 生命周期诊断。

## 5. 架构边界继续治理

后续架构工作直接围绕当前 3.0 边界继续收敛：

- Feature 继续只持有自己的领域状态、规则、写入与最小公开 API；
- Shared Capability 继续保持中立单一事实源；
- Session ownership、Agent run identity、资源调度与恢复协调保持单一 Shared Runtime Owner；
- Feature / Provider 构造继续集中在应用组合根；
- `LocalRuntimeKernel` 保持进程 start-once、生命周期 scope、bootstrap / recovery 触发与失败投影的极薄职责；
- UI 只通过 presentation / Feature API / projection 消费；
- FeatureCatalog 继续作为启动期路由归属事实源；
- 数据查询、diagnostics、缓存和低频维护逻辑归入对应 Owner，不形成跨域中央入口。

目标是让所有权、调用边界、生产装配和状态事实源持续清晰，新增功能无需扩大 Kernel、Shell 或全局共享对象的业务职责。

## 6. UI 状态继续收敛

继续保证：

- Chat / Work 状态隔离。
- 高频 streaming 不推动整棵 UI。
- 低频统计不进入主聊天热路径。
- Dialog / Sheet 自己承担展示装配。
- ViewModel 只依赖 presentation / Feature API / projection，不直接消费领域 Store、Coordinator 或 Shared Runtime 实现。

任何 UI 功能新增都同时检查认知成本和 Compose 重组成本。

## 7. Token 与性能诊断

已有请求级账本后，继续强化：

- action 成本分布。
- 主 / 子代理比较。
- prompt section 估算偏差。
- cache hit 利用率。
- 高成本功能定位。
- 长会话成本变化。
- 自动任务成本。

原则：

**诊断帮助减少真实消耗，不新增重复统计。**

## 8. Runtime 与 APK

继续优化：

- Runtime 启动成本。
- APK 体积。
- 重复资源。
- ABI 产物。
- native library 布局。
- 解压 / 恢复成本。
- Runtime 完整性校验。

任何优化必须同时验证 Node / Python / Git 实际执行，不能只看 APK 变小。

## 9. 安全

继续强化：

- 输入与路径边界。
- MCP / LSP 外部进程权限。
- Device capability 最小权限。
- 日志敏感信息。
- Web SSRF。
- Automation 副作用。
- 更新链完整性。

安全修复不得通过降低可观测性隐藏问题。

## 10. 可观测性

所有复杂任务最终应能回答：

- 哪个 Session。
- 哪个 run。
- 哪个 Agent。
- 哪个 action。
- 为什么执行。
- 花了多久。
- 消耗多少 Token / 资源。
- 为什么失败。
- 是否安全恢复。

优先用结构化 id 串联，不复制大段正文。

## 11. 测试

每项后续工作必须形成：

```text
实现
→ 单元测试
→ 专项回归
→ 架构 / 性能门禁
→ Android 16 / 17
→ 最新 main 组合 CI
```

多 PR 同时进行时，合并顺序和回归规则遵循 [../AGENTS.md](../AGENTS.md)。

## 完成标准

任何路线项只有同时满足以下条件才算完成：

- 功能行为正确。
- 原有能力无回归。
- 性能无明显退化。
- 用户体验没有增加不必要复杂度。
- 失败可诊断。
- 恢复边界明确。
- 测试覆盖真实行为。
- 最新主线组合 CI 通过。
