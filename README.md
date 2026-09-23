<p align="center">
  <img src="docs/images/banner.jpg" alt="DSH Mobile — 装进口袋的 DeepSeek Harness" width="100%">
</p>

<h1 align="center">DSH Mobile — DeepSeek Harness 远程端</h1>

> **777 安卓构建版：**本仓库基于上游提交
> [`sorsama/deepseek-harness-mobile@e5f8c2f`](https://github.com/sorsama/deepseek-harness-mobile/commit/e5f8c2f)
> 整理，使用独立应用标识 `com.sy220284.dshmobile`，可与上游版本同时安装。原 MIT 许可证和
> 第三方声明均完整保留。

## 777 第二阶段：手机内置 Harness

`0.12.0-777.5` 面向 Android 16+ 扩展原生安卓执行核心。连接页可直接进入“本机 Harness”，模型循环、
多会话追加日志、文件读写与编辑、搜索、安卓 shell、后台任务、网页搜索与获取、计划、目标、任务清单、
技能、用户问答、可选模型子代理以及并行/流水线工作流都在 APK 内运行，不需要电脑端保持在线。DeepSeek API 密钥由
Android Keystore 加密；写文件、编辑文件和执行命令必须逐次取得用户批准。

完整功能对照和 Android 16 适配边界见
[Android 16 本机 Harness 适配审计](docs/ANDROID16-HARNESS-PARITY.zh-CN.md)。

后续将把当前本机实现升级为真正的 Android 原生 Harness 内核：官方源码作为语义、协议和测试基线，
Agent / Session / Tool / Workflow 等核心能力由 APK 原生实现；完整路线见
[Android 原生 Harness 内核完整规划与实施方案](docs/ANDROID-NATIVE-HARNESS-IMPLEMENTATION-PLAN.zh-CN.md)。

本机执行被限制在应用私有工作区。安卓不会允许普通应用读取其他应用的私有目录；系统也不自带
Node、Python、Git 等桌面程序。需要这些运行时的任务仍需后续按架构和许可证单独内置，或继续使用
原有的电脑远程模式。

<p align="center">
  一款开源的 Android 伴侣应用，把你的 <b>DeepSeek Harness</b> 装进口袋。<br>
  通过加密中继用手机驱动会话、查看计划与目标、回应审批与提问，
  并在 harness 干完活时收到通知。
</p>

<p align="center">
  <a href="https://dshm.zyphite.com"><img alt="Website" src="https://img.shields.io/badge/website-dshm.zyphite.com-4176E6?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/sorsama/deepseek-harness-mobile?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/sorsama/deepseek-harness-mobile/ci.yml?branch=main&style=flat-square"></a>
  <img alt="Android 16+" src="https://img.shields.io/badge/Android-16%2B-3DDC84?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=flat-square"></a>
</p>


DSH Mobile 是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（MIT）的
**非官方伴侣应用**，用 harness 自己的视觉语言逐项还原它的网页 GUI。仅支持 Android，使用
Kotlin + Jetpack Compose。

另一端的搭档是 [**dsh-relay**](https://github.com/sorsama/deepseek-harness-relay) —— 一个 harness
插件，补上 harness 自己承认缺失的那层身份验证，让这个应用能凭真正的凭据和固定的密钥去连接
harness，而不是对着一个敞开的端口。参见
[Relay](https://github.com/sorsama/deepseek-harness-mobile/wiki/Relay)。

**[dshm.zyphite.com](https://dshm.zyphite.com)** 是项目主页 —— 一页讲清这个应用是什么、
长什么样、怎么跑起来。

[**wiki**](https://github.com/sorsama/deepseek-harness-mobile/wiki) 是面向用户的指南：
[入门](https://github.com/sorsama/deepseek-harness-mobile/wiki/Getting-Started)、
[连接](https://github.com/sorsama/deepseek-harness-mobile/wiki/Connecting)、
[疑难解答](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting)、
[功能巡览](https://github.com/sorsama/deepseek-harness-mobile/wiki/Feature-Tour) 和
[常见问题](https://github.com/sorsama/deepseek-harness-mobile/wiki/FAQ)。

---

## 截图

| 连接 | 聊天 | 轨迹 |
|:--:|:--:|:--:|
| <img src="docs/images/home.png" width="240" alt="连接界面：最近使用的 harness 及其实时可达性、发现、手动输入与自动连接开关"> | <img src="docs/images/chat.png" width="240" alt="聊天：流式输出的回合、每种工具的图标、工具卡片、目标停靠栏与输入框"> | <img src="docs/images/trajectory.png" width="240" alt="轨迹：按回合排列的账本，附带用量合计"> |
| 已配对的中继、实时连接状态与自动连接。 | 流式输出的回合、每种工具一个字形、可展开的工具卡片、权限选择器。 | 同一个会话，以按回合排列的账本呈现，并给出用量合计。 |

| 会话详情 | 子代理 |
|:--:|:--:|
| <img src="docs/images/session-info.png" width="240" alt="详情面板：上下文构成、目标、计划模式、任务、队列、子代理、主机信息"> | <img src="docs/images/subagent.png" width="240" alt="子代理目录，可继续与子代理对话"> |
| 上下文构成、目标、计划模式、后台任务、排队的回合、主机信息、会话日志导出。 | 子代理目录 —— 打开子代理的对话记录、追问，或将其中断。 |

## 功能

- **轻松连接** —— 自动发现同一 Wi-Fi 下的 harness（主动子网扫描 + 就绪握手），
  记住用过的主机并在进入时探测其存活状态，支持手动输入 `host:port`、同设备回环连接，
  以及已配对中继的自动连接开关。
- **Discord 式导航** —— 从屏幕左缘右滑打开按工作区分组的聊天列表，左滑关闭；
  从右缘左滑打开会话详情面板。
- **完整的聊天体验** —— 流式输出的回合与可展开的推理过程、Markdown、
  终端/差异/读取/搜索/网页工具卡片、队列停靠栏（编辑 / 移除 / 引导）、历史分页、图片与文件附件。
- **斜杠命令与技能** —— 输入框会先拿 `/` 开头的一行去比对会话自己的命令目录，
  命中就交给 harness 的命令网关执行；目录不认领的内容按普通提示发送，技能就是这样被调用的。
- **GUI 有的都有** —— 目标（阶段、轮次、暂停/继续/编辑）、计划模式与计划审阅、
  权限审批、用户提问、待办停靠栏、子代理（目录、追问、中断）、后台任务、工作流运行、技能、
  模型选择、代理预设、会话搜索、轨迹账本、会话导出、消息反馈。
- **通知** —— 回合完成、目标完成 / 受阻、有审阅或提问在等你；
  通过前台服务维持后台连接。
- **和 harness 长得一样** —— 完全采用 DeepSeek Harness 的设计令牌（颜色、字体、圆角、
  披露行、微光、墨水按钮），支持浅色 / 深色 / 跟随系统主题。
- **中英双语** —— 设置页只提供 English / 中文，两套界面文案共同维护。

## 环境要求

- Android 16 及以上（minSdk 36）；低于 Android 16 的设备无法安装。
- 一个正在运行的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
  （已针对 `0.1.3-alpha.1` 测试）。**0.10.0 需要 harness 0.1.3** —— 该版本不再把回复的增量写入日志，
  改为通过 App 必须主动订阅的实时流传输，因此 App 与 harness 必须同时升级：旧版 App 在 0.1.3 上看不到
  正在生成的回答，而本版 App 在 0.1.2 上无法执行斜杠命令。参见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。

## 快速开始

1. 从 [Releases](https://github.com/sorsama/deepseek-harness-mobile/releases/latest)
   安装最新的 APK。
2. 打开应用，选择连接方式。这几种不是同一个设置的不同变体 ——
   挑与你在电脑上配置好的那一种。

   **中继** —— 加密、有身份验证，在 Wi-Fi 之外也能用。把
   [`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay) 装进 harness 的 web 配置：

   ```sh
   dsh plugin --profile web add dsh-relay
   dsh web
   ```

   **在那台电脑上**打开打印出来的 URL，设置密码，然后打开 `/relay/pair`。
   在应用里：**中继 → 配对中继**，扫描二维码。等你用的每台客户端都配对完，
   就关掉中继的 `compat.addressGrants` —— 这里没有任何东西需要它。

   远程控制仅支持 HTTPS 中继配对。旧的局域网扫描和直连入口已移除，明文中继需要升级为 HTTPS 后重新配对。

3. 选一个会话开始聊，harness 干完活会通知你。

如果连接失败，应用会直接说明原因；wiki 的
[疑难解答](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting)
页面就是按那一句话组织的。

## 兼容性与安全

- 协议兼容性见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。
- 远程访问需要配对的 HTTPS 中继，详见 [docs/SECURITY.md](docs/SECURITY.md)。代理可在远端执行命令，请只配对可信服务。
- 本机 Webhook 仅监听回环地址。代码智能会按项目与文件类型自动检测可用语言服务器；首次启动外部语言服务器进程仍需权限审批，未检测到时自动退回文件搜索、编译与测试能力。

## 构建

```sh
./gradlew :app:assembleDebug      # 调试版 APK
./gradlew :app:assembleRelease    # 发布版 APK（设置了 keystore 环境变量时会签名）
```

发布的版本号来自 git 标签：发布工作流从标签名导出 `DSH_VERSION_NAME`，`versionCode` 由它推导。
本地构建则回退到 `app/build.gradle.kts` 里写死的值。

针对真实 harness 的开发流程、模块划分和发布流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `core/` | 纯 JVM 协议核心：线路 DTO、RPC 客户端、WebSocket 下行流、重连循环、会话折叠、通知分类器 |
| `app/` | Android UI：各界面、发现与连接、前台服务、通知、国际化 |
| `mock-harness/` | 用于测试的 harness `/api` 服务端 Ktor 模拟实现 |
| `tools/capture/` | 把真实 harness 流量录制成一致性测试夹具 |
| `harness/` | 加密中继配对指南 |
| — | 中继本身在 [sorsama/deepseek-harness-relay](https://github.com/sorsama/deepseek-harness-relay) |
| `docs/` | [架构](docs/ARCHITECTURE.md)、[协议说明](docs/PROTOCOL.md)、[兼容性](docs/COMPATIBILITY.md)、[安全](docs/SECURITY.md) |

## 许可证

[MIT](LICENSE)。随附的第三方材料列在
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。DeepSeek Harness 及其品牌归各自所有者所有；
本项目是一个独立的、由社区构建的远程端。
