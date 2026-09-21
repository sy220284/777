# Android 原生 Harness M1 实施状态

## 本轮完成

- 新增独立纯 Kotlin 模块 `:harness-core`，不依赖 Android、Compose、Activity 或 Service。
- 将追加式会话事件日志迁入核心模块，Android 侧保留薄适配层，已有会话文件格式不变。
- 将后台任务控制器迁入核心模块，收拢任务编号、状态、输出和子代理收件箱职责。
- 建立平台无关的 `AgentLoop`，明确 turn / step、模型回复、工具调用、工具结果、完成、失败和取消事件顺序。
- 增加假的模型与假的工具端到端测试，覆盖普通对话、工具回填和取消不伪造完成事件。
- 持续集成新增 `:harness-core:test` 门禁。

## 兼容保证

- APK 主运行链路继续使用 Kotlin 原生实现。
- 官方 Harness 仅作为已锁定的语义、协议和测试参考。
- 本轮不变更官方参考版本，也不增加任何自动同步官方更新流程。
- 现有 Android 本机 Harness 的公开界面与持久化文件名保持不变。

## M1 后续

1. 将 `LocalHarnessEngine` 的 turn / step 调度逐段切换到 `AgentLoop`。
2. 将 Session 模型、投影、迁移和导出迁入 `:harness-core`。
3. 将工具目录改为核心接口加 Android 能力提供器。
4. 增加进程重启后的会话恢复与旧数据迁移门禁。

每一步独立提交，并保持主线可构建、可安装、可回滚。
