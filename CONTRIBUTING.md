# Contributing

感谢你参与 777 / 神言神语。

贡献前请先阅读：

- [AGENTS.md](AGENTS.md) —— 工程执行、测试、PR 与合并规则
- [README.md](README.md) —— 当前产品与架构概览
- [docs/README.md](docs/README.md) —— 当前文档入口

## 环境

- Android Studio，使用当前稳定版即可。
- JDK 21 或更高版本；项目 JVM 编译目标固定为 21。
- Android SDK 36 / 37。
- Kotlin / AGP 版本由仓库锁定，不要自行升级后顺手提交。

常用构建：

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleOptimized
```

## 仓库结构

```text
app/                      Android UI、组合根、本机 Harness 产品能力
core/                     远程 Harness Web 协议
harness-core/             平台无关 Agent 核心
harness-runtime-android/  Android 进程 / 持久终端
harness-interop/          MCP / LSP
harness-device-android/   Android 设备能力
reference-validation/     官方 Harness 差分验证
```

app 内采用架构 3.0 的模块化单体：产品业务归所属 Feature，跨 Feature 中立能力归 Shared Capability，Feature / Provider 构造集中在应用组合根，UI 只通过 presentation / Feature API / projection 消费。

新增功能先确定领域 Owner、共享能力边界、公开 Port、生产装配和状态事实源；`LocalRuntimeKernel` 只负责进程 start-once、生命周期 scope、bootstrap / recovery 触发和初始化失败投影。

## 开发规则

### 功能

任何修改都要同时检查：

```text
上游输入
→ 当前能力
→ 下游调用
→ 状态 / 数据
→ UI / 用户行为
→ Agent / Tool / 后台任务
→ 测试 / 日志 / 构建
```

新增或修复不得破坏聊天、工作、主代理、子代理、Automation、Web、Vision、Session 恢复、Token 归属等横向链路。

### 代码

长期要求：

- 规范性
- 健壮性
- 可读性
- 可维护性
- 可扩展性
- 可测试性
- 性能可控性
- 可观测性
- 安全性

不要用提高架构预算、关闭门禁、扩大白名单或删除测试来“修复”问题。

### UI

- 用户可见文本进入字符串资源。
- 复用现有 Design System。
- 不在主页面堆低频诊断。
- Chat / Work 状态保持独立投影。
- streaming 热路径避免整棵状态重建。
- UI 改动同时检查窄屏、大字号、输入法、浅色 / 深色和返回行为。

### 协议与数据

- 远程 wire layer 对未知 JSON 字段保持宽松。
- 不依赖猜测的 Harness 版本分支行为。
- 本机 Session Event Log 是主要事实源。
- 运行恢复不能盲目重放结果未知的副作用。
- 本地 Token 总量只采用成功响应实际返回的 API usage；失败或未返回 usage 的请求只做诊断，不估算供应商最终计费；prompt breakdown 不重复计数。

## 验证

本仓库 CI 的必需链：

```text
架构 / 性能 / UI / Kotlin 门禁
→ 单元测试
→ 官方 Harness conformance
→ Lint
→ optimized APK
→ Android 16
→ Android 17
→ merge-gate
```

本地常用：

```sh
./gradlew :core:test   :harness-core:test   :harness-runtime-android:test   :harness-interop:test   :harness-device-android:testDebugUnitTest      :app:testDebugUnitTest

./gradlew :reference-validation:test
./gradlew :app:lintDebug
./gradlew :app:assembleOptimized
```

涉及 Runtime / APK、设备能力、MCP / LSP、Session 恢复等高风险能力时，增加对应专项验证。

## PR

PR 标题和描述使用中文。

说明至少包括：

- 改了什么。
- 为什么改。
- 影响哪些横向 / 纵向链路。
- 实际做了哪些测试。
- 是否涉及 UI、性能、存储、Runtime、协议或安全边界。

如果 main 在 PR 验证后又发生变化，旧 CI 不能直接作为最终放行依据。

最终需要验证：

```text
current main + current PR head
```

## 发布

发布工作流从 git 标签生成版本名。

本地版本回退来自：

```text
.github/release-version
```

不要手工在多个文件同步改版本号。
