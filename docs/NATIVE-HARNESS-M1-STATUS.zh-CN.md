# Android 原生 Harness M1 实施状态

## 已完成

- 新增独立纯 Kotlin 模块 `:harness-core`，不依赖 Android、Compose、Activity 或 Service。
- 将追加式会话事件日志迁入核心模块，Android 侧保留薄适配层，已有会话文件名保持不变。
- 将后台任务控制器迁入核心模块，收拢任务编号、状态、输出和子代理收件箱职责。
- 建立平台无关的 `AgentLoop`，并将主 `LocalHarnessEngine` 的 turn / step 调度切换到该核心循环。
- 核心循环现已记录 `turn/start`、`step/start`、assistant、tool call/result、`step/end` 与 turn 结束状态。
- 工具调用保留模型原始参数字符串；Android 适配器可提供批量执行器，现有并行子代理能力不会因主循环迁移而退化。
- 步数安全上限改为受控结束状态，不再当作普通异常处理。
- 官方 Harness 差分验证模块已进入主线，锁定 `0.1.7-alpha.2 / 00102833...`，日常 CI 不自动同步上游。
- 普通对话与单工具案例已升级为完整 turn / step 生命周期差分。
- 核心单测、官方差分门禁、Android 16 安装包构建与模拟器运行均由 CI 负责验证。

## 当前边界

- 模型历史、会话投影、迁移和导出仍由 Android 适配层持有；`AgentLoop` 已拥有调度权，但 Session 还没有完全核心化。
- 工具目录仍由 Android 端 `LocalToolCatalog` 定义；核心插件/能力注册表尚未落地。
- 子代理内部循环仍有独立实现，后续应复用同一核心循环和会话语义。
- 官方 Harness 继续只作为锁定的语义、协议和测试参考，不作为 APK 运行时依赖。

## M1 后续

1. 将 Session 模型、投影、迁移和导出迁入 `:harness-core`，并增加进程重启恢复门禁。
2. 将工具目录改为核心接口加 Android 能力提供器，消除 `LocalHarnessEngine` 内的大型工具分派。
3. 将子代理循环迁入同一 `AgentLoop` / Session 体系，统一取消、失败和并行语义。
4. 完成 M1 回归后进入规划中的原生插件与能力注册表阶段。

每一步独立提交，并保持主线可构建、可安装、可回滚。
