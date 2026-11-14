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
core/                     本机复用的序列化 DTO、JSON 编解码及 HTTP 通用工具
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
修复应基于真实问题与正确边界，不依靠删除测试掩盖功能错误。
### UI
- 用户可见文本进入字符串资源。
- 复用现有 Design System。
- 不在主页面堆低频诊断。
- Chat / Work 状态保持独立投影。
- streaming 热路径避免整棵状态重建。
- UI 改动同时检查窄屏、大字号、输入法、浅色 / 深色和返回行为。
### 协议与数据
- 共用 JSON 解码契约对未知字段保持兼容，不能为了清退远程入口破坏本机使用的 DTO。
- 不依赖猜测的 Harness 版本分支行为。
- 本机 Session Event Log 是主要事实源。
- 运行恢复不能盲目重放结果未知的副作用。
- 本地 Token 总量只采用成功响应实际返回的 API usage；失败或未返回 usage 的请求只做诊断，不估算供应商最终计费；prompt breakdown 不重复计数。
## 构建与调试
编译、单元测试、设备排障都可按需要自行运行；本仓库不设置强制验收项目、验收证明、PR 检查清单或 CI 合并门槛。
## PR
PR 标题和描述使用中文，说明改动的功能与原因即可。运行过的测试可自愿说明，不要求任何指定格式的验收记录或附件。
## 发布
发布工作流从 git 标签生成版本名。
本地版本回退来自：
```text
.github/release-version
```
不要手工在多个文件同步改版本号。