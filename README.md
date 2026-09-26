<p align="center">
  <img src="docs/images/banner.jpg" alt="神言神语 — 装进口袋的 Agent 平台" width="100%">
</p>

<h1 align="center">神言神语</h1>

<p align="center">
  一款开源的 Android 端侧 Agent 平台。<b>APK 内自带完整的 Harness 内核</b>——<br>
  模型循环、持久终端、原生进程、内置 Node/Python 运行时、MCP 互通、聊天 / 工作双模式，全程不需要电脑在线。
</p>

<p align="center">
  <a href="https://github.com/sy220284/777/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/sy220284/777/ci.yml?branch=main&style=flat-square"></a>
  <a href="https://github.com/sy220284/777/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/sy220284/777?style=flat-square"></a>
  <img alt="Android 16+" src="https://img.shields.io/badge/Android-16%2B-3DDC84?style=flat-square">
  <img alt="arm64" src="https://img.shields.io/badge/ABI-arm64--v8a-6E4C9B?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=flat-square"></a>
</p>

> **血统与许可**：本仓库 fork 自
> [`sorsama/deepseek-harness-mobile@e5f8c2f`](https://github.com/sorsama/deepseek-harness-mobile/commit/e5f8c2f)
> （DSH Mobile 时代），现以独立应用标识 `com.sy220284.dshmobile` 发行，可与上游同时安装。
> 上游 MIT 许可证与第三方声明完整保留。DeepSeek Harness 官方源码经
> `upstream/deepseek-harness.lock.json` 钉定版本，作为语义、协议与一致性测试的基准。

---

## 它是什么

一个内核，三种形态：

1. **本机自包含执行（主形态）**——模型循环、会话、工具、子代理、工作流全部在 APK 内运行。
   持久终端与原生进程执行 shell 命令，内置 Node / Python 双运行时兜底脚本生态，
   MCP（HTTP / stdio）与 LSP 打通外部工具和语言服务器。断网也能干活。
2. **远程中继控制**——通过配对的 HTTPS 中继连接电脑上的 DeepSeek Harness，
   手机当驾驶舱：驱动会话、回应审批与提问、收完成通知。旧的局域网扫描与明文直连已移除。
3. **聊天模式**——把 Agent 当"人"聊：人设运行时、人物主档案与多故事线、多角色群聊、
   长期记忆、连续意图路由、定时互动。输出经 AI 味短语黑名单硬门禁，说人话是产品约束不是建议。

## 功能总览

### 执行内核（本机 Harness）

- 完整 Agent 循环：多会话、追加式事件账本、计划、目标、任务清单、技能、用户问答、
  可选模型子代理、并行 / 流水线工作流。
- 工具面：文件读写与编辑、搜索、安卓 shell（持久终端 + 托管进程）、后台任务、
  网页搜索与获取、代码智能（按项目与文件类型检测语言服务器）。
- **内置 Node / Python 双运行时**：无需 root，脚本生态开箱即用。
- **MCP 互通**：HTTP 与 stdio 双传输，外部 MCP 服务器直接挂成本机工具；旧传输兼容。

### 设备能力

- 无障碍服务、虚拟屏（VirtualDisplay）、Shizuku 桥——Agent 可以看屏幕、操作界面、提权执行。

### 聊天模式

- 人设图集 V4：人物主档案、多故事线、会话绑定、立绘页面。
- 多角色群聊：独立人设智能体、自然点名、群聊长期关系、路由延迟优化。
- 连续用户意图路由、意图优先级加固、用户意图内容策略。
- 消息修改重发、回复版本前后切换、表格渲染、自适应壁纸（按图片内容动态调整阅读层）。
- 定时互动：人设按计划主动发起对话。
- **去 AI 味硬门禁**：聊天输出过 AI 味短语黑名单，命中即拦。

### 工作模式

- 统一顶栏、自然自动化、能力中心与运行中心全链路打通、工作执行闭环与人物记忆。

### 通用

- 前台服务维持后台执行，回合完成、审批、提问均有系统通知。
- 会话搜索、轨迹账本（按回合排列 + 用量合计）、会话导出、消息反馈。
- 中英双语、浅色 / 深色 / 跟随系统主题、背景图个性化。
- 应用内增量更新：哈希校验的分发链，无需反复下载完整包。

## 差分验证体系

本机内核不是"照着文档抄"，而是**对着官方实现逐项比对**：

1. **钉版**——`upstream/deepseek-harness.lock.json` 锁定官方 commit，官方语义不再漂移。
2. **黄金结果**——对锁定版本跑官方行为，录制为一致性夹具（`tools/capture/`）。
3. **原生比对**——`reference-validation` 模块的 `NativeConformanceRunner` 用同一输入驱动本机实现，
   输出与黄金结果逐字节对照。
4. **CI 门禁**——一致性不一致即构建失败；`mock-harness`（Ktor 模拟服务端）作为常驻测试对手，
   连 mock 自身的缺陷都曾在此体系中被反向抓出。

当前一致性覆盖与路线见
[执行内核 Phase B/C 验证](docs/EXECUTION-KERNEL-PHASE-BC-VALIDATION.zh-CN.md)。

## 架构

| 模块 | 体量 | 职责 |
|---|---|---|
| `app/` | 294 文件 · 62.4k 行 | 组合根：聊天 / 工作双模式 UI、连接与配对、通知与前台服务、更新链、Webhook |
| `core/` | 69 · 12.5k | 纯 JVM 协议核心（上游血统）：线路 DTO、RPC 客户端、WebSocket 下行流、重连循环、会话折叠、通知分类 |
| `harness-core/` | 31 · 4.1k | 纯 JVM Agent 内核：AgentLoop、输入队列、请求执行、上下文组装、会话事件账本、资源调度 |
| `harness-interop/` | 9 · 2.9k | 互通层：MCP 客户端（HTTP / stdio / 旧传输）与工具桥、LSP 插件 |
| `harness-runtime-android/` | 6 · 1.0k | 进程运行时：持久终端、托管进程、Android 运行时插件 |
| `harness-device-android/` | 8 · 1.4k | 设备能力：无障碍服务、虚拟屏控制器、Shizuku 桥、设备提供者 |
| `mock-harness/` | 7 · 1.9k | Ktor 实现的 harness `/api` 模拟服务端，一致性测试的对手盘 |
| `reference-validation/` | 3 · 204 | 原生一致性运行器：对照锁定官方版本的黄金结果 |

依赖方向单向：`app → harness-* → harness-core`，`core`（远程模式）与内核互不依赖。

## 环境要求

- Android 16+（minSdk 36）。
- 内置运行时按 `arm64-v8a` 打包；模拟器 / x86_64 设备可用 `DSH_RUNTIME_ABIS=x86_64` 自行构建。
- 远程模式需一台运行中的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
  （已针对 `0.1.3-alpha.1` 测试；App 与 harness 需同时升级到流式协议版本，
  详见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)）。

## 快速开始

1. 从 [Releases](https://github.com/sy220284/777/releases/latest) 安装 APK。
2. **本机模式**：连接页直接进入本机 Harness，填入 DeepSeek API 密钥（Android Keystore 加密存储）即用。
3. **远程模式**：在电脑上装 [`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay)：

   ```sh
   dsh plugin --profile web add dsh-relay
   dsh web
   ```

   打开打印的 URL 设置密码，进入 `/relay/pair`；在应用里**中继 → 配对中继**扫码。
   远程访问仅支持 HTTPS 中继配对。

## 安全与边界

- API 密钥由 Android Keystore 加密；写文件、编辑文件、执行命令必须逐次取得用户批准。
- 本机执行限制在应用私有工作区；Webhook 仅监听回环地址。
- 远程访问要求配对的 HTTPS 中继，详见 [docs/SECURITY.md](docs/SECURITY.md)。
  代理可在远端执行命令，请只配对可信服务。

## 构建

```sh
./gradlew :app:assembleDebug      # 调试版 APK
./gradlew :app:assembleRelease    # 发布版 APK（设置了 keystore 环境变量时会签名）
```

工具链：AGP 9.4 / Kotlin 2.2.10 / Jetpack Compose（BOM 2026.09）/ Hilt 2.59，compileSdk 37。

发布版本号来自 git 标签：发布工作流从标签名导出 `DSH_VERSION_NAME`，`versionCode` 由它推导；
本地构建回退到 `app/build.gradle.kts` 的写死值。开发流程与发布细节见
[CONTRIBUTING.md](CONTRIBUTING.md)。

## 文档

- [架构](docs/ARCHITECTURE.md) · [协议](docs/PROTOCOL.md) · [兼容性](docs/COMPATIBILITY.md) · [安全](docs/SECURITY.md)
- [Android 16 本机 Harness 适配审计](docs/ANDROID16-HARNESS-PARITY.zh-CN.md)
- [Android 原生 Harness 内核规划](docs/ANDROID-NATIVE-HARNESS-IMPLEMENTATION-PLAN.zh-CN.md) · [M1 进展](docs/NATIVE-HARNESS-M1-STATUS.zh-CN.md)
- [执行内核 Phase B/C 验证](docs/EXECUTION-KERNEL-PHASE-BC-VALIDATION.zh-CN.md)
- [UI/UX 重设计](docs/ANDROID-UIUX-REDESIGN.zh-CN.md) · [频率优先重设计](docs/UI-FREQUENCY-FIRST-REDESIGN.zh-CN.md)

## 截图

| 连接 | 聊天 | 轨迹 |
|:--:|:--:|:--:|
| <img src="docs/images/home.png" width="240" alt="连接界面：本机 Harness 入口、已配对中继与实时可达性"> | <img src="docs/images/chat.png" width="240" alt="聊天：流式输出的回合、工具卡片、目标停靠栏与输入框"> | <img src="docs/images/trajectory.png" width="240" alt="轨迹：按回合排列的账本，附带用量合计"> |

| 会话详情 | 子代理 |
|:--:|:--:|
| <img src="docs/images/session-info.png" width="240" alt="详情面板：上下文构成、目标、任务、子代理、主机信息"> | <img src="docs/images/subagent.png" width="240" alt="子代理目录，可继续与子代理对话"> |

## 许可证

[MIT](LICENSE)。随附的第三方材料列在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
DeepSeek Harness 及其品牌归各自所有者所有；本项目是独立的社区构建，与上游已按自己的路径演化。
