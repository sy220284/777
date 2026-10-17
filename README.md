<h1 align="center">神言神语</h1>

> **目前仅保留后端与新 UI 接口。** 原 V6 页面、组件、图标、字体、动画与设计素材已移除；启动入口不再绘制旧界面。新 UI 未接入前，APK 无法提供正常的图形交互。详见 [后端基线与新 UI 接入规范](docs/UI-BACKEND-RESET.zh-CN.md)。

<p align="center">
  一款开源的 Android 端侧 Agent 平台。<b>一部手机，就是一台 Agent 主机。</b>
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
> 上游 MIT 许可证与第三方声明完整保留。本机原生 Harness 的官方语义参考由
> `upstream/deepseek-harness.lock.json` 钉定；当前发行版只提供 Android 本机模式。

---

## 神言神语是什么

模型负责思考，其余一切——任务循环、工具执行、文件系统、终端、子代理、记忆、人物、创作——都在手机上完成编排。APK 自带完整 Harness 内核与 Node / Python / Git 运行环境：安装即用，无需 root，无需电脑在线，没有云中继，API 密钥只留在 Android Keystore。

它同时是三样东西，共用同一个内核：

- **一个长期角色伙伴**：聊天模式下的人物有记忆、经历和成长；
- **一个真正干活的 Agent**：读写文件、跑命令、用 Git、调 MCP / LSP、操作设备，直到交付；
- **一个随身创作台**：图片、视频、配音、转写直接生成在会话里。

## 系统组成

```text
界面层    （旧 UI 已移除，等待全新 UI 接入）
记忆层    人物主档案 · 故事线 · 关系记忆 · 世界书 · 日记与披露边界
内核层    Agent 循环 · 主 / 子代理 · 任务与技能 · 审批闸门 · 事件账本与恢复
能力层    文件 / Git · 持久终端 · Node / Python / LSP · MCP · Web · Vision · 设备能力
模型层    统一能力合同 · 多协议路由 · API Key / ChatGPT 套餐 · Keystore 加密
工程层    差分验证 · CI 门禁 · 架构 3.0 · 功能树 · 增量更新哈希链
```

每一层只做自己的事：界面投影事件账本，记忆按归属隔离，内核不信任模型的自述，能力全部经过闸门，模型身份一次任务一个，工程层负责证明以上都是真的。

## 两种用法，一个内核

### 聊天：让"这个人"始终认识你

每次回复前，内核装配上下文：人物设定、关系、共同经历、场景、故事线、最近对话，然后才轮到模型。人物图集负责"这个人是谁"，故事线和关系记忆负责"你们经历过什么"。

人物运行时按生命与对话模式模型工作：人物卡只保留少量长期底色，每一轮的思维、互动与表达模式由当前话题、注意力、身体与情绪、关系状态、生活进展和你的调节动态形成——不是固定性格关键词到固定话术的映射，也不靠滑杆改写事实。

记忆有明确归属：人物关系记忆按 Gallery / Persona 身份隔离，不同角色之间不串记忆。日记支持跨场景连续性，但群聊只允许公开日记进入 Prompt，私人与可分享日记被挡在外面；单聊条目要有明确公开授权证据才能标记为公开。

群聊沿用同一套机制：公开事件共享，每个角色的身份、关系和隐藏状态彼此隔离。消息可以修改重发，人物可以定时主动找你，回复风格有硬门禁过滤"AI 味"短语——像模型的，不如像人的。

### 工作：把事做完，并证明做完了

Agent 理解目标、规划步骤、调用真实工具、根据结果继续判断，直到交付。运行语义与 Codex 核心机制对齐：用户指令在优先级链最上层，助手建议不会升格为决定，旧约束不会在恢复后复活。

任务闭环统一：授权精确到具体操作，完成绑定真实验收与交付证据——执行步数不冒充任务完成率，Agent Team 的进度按真实任务状态计算；取消与恢复语义明确，长任务有配额保护。

子代理有独立身份：稳定身份指令与本轮任务分离，不默认继承父历史（未授权不可见），输出截断对父代理可见，任务归属可追踪到父子执行关系。

工具原文跨上下文压缩保留可引用 ID，恢复分页按字节对齐返回。长任务有运行 checkpoint 与 Agent inbox，中断后恢复时不重放结果未知的危险副作用。生成的成果同步进入会话时间线：在聊天里直接预览、分享，文件更新后卡片原位跟进。

### 创作：在会话里生成，在会话里交付

模型负责想，端侧负责落地。聊天输入区的加号面板接入 MiniMax 原生多模态：图片生成、视频任务、配音与语音转写。入口按模型能力声明显示——只有会话使用官方 MiniMax 模型时才出现。

创作是会话内的一等公民：提交即在聊天时间线显示阶段卡，完成后原位转换为成果——图片按原图比例呈现、轻触查看，音频带进度条原位播放，视频自动下载保存、播放并分享。提交后关闭弹窗不取消已提交的任务，付费任务持久化并防止重复扣费，删除成果同步清理任务回执。

语音转写使用应用私有暂存、流式上传、结束后清理。设置侧边栏在选中 MiniMax 模型时提供官方套餐额度与账户余额查询。

## 一次"发消息"的完整链路

```text
你发消息
↓
装配上下文：人物 / 记忆 / 故事线 / 任务状态（按模型预算分配）
↓
能力合同选定模型与执行策略（一次任务一个身份）
↓
模型推理
↓
需要动手时：工具闸门（存在性 → 模式边界 → 数据边界 → 审批）→ 真实执行 → 结果回填
↓
继续推理，直到给出结果
↓
全过程写入事件账本：可恢复、可回放、可审计
```

模型能力与执行策略来自一份统一的能力合同：运行路由、聊天控件与工作模式读取同一份快照。模型列表可通过 API 自动获取，也可查询安全的官方模型目录；既支持 API Key，也支持通过 OpenAI 的 **Continue with ChatGPT** 授权使用符合条件的套餐模型，两种认证身份分别保存和路由。

一次任务开始后，模型档案固定下来。即使你随后在界面切换到其他模型或账户，已启动的任务、子代理、Vision 和后续处理仍沿用原来的运行身份，避免串账户、串 API Key 或串上下文。

上下文预算按模型分配，工作与聊天各自计算；真实边界（单请求上下文窗口、并发待发送预算、请求次数、超时与路由熔断）始终生效。

用户消息、模型回复、推理过程、工具调用与结果、任务步骤、计划和恢复信息都写入 Session Event Log。会话分支、重新生成、修改重发、长历史分页、中断恢复、模型重放证据与团队隔离，都建立在同一份事实记录上——界面快照只是为了显示更快，不是另一套历史。

## 设计原则

这四条原则写在工程规范（[AGENTS.md](AGENTS.md)）里，也决定了你用到的每一个行为：

1. **端侧自治**——内核、工具、记忆、执行环境都在手机上；电脑与云中继不是依赖项。
2. **结果必须被证明**——"模型说它做了"与"工具真的执行成功"是两件事；工具闸门、交付证据和事件账本为此存在。
3. **身份与记忆完整**——模型一次任务一个身份，人物记忆有明确归属；串账户、串上下文、角色串记忆都被结构性阻止。
4. **复杂度从第一行开始成立**——架构门禁、禁止伪拆分、功能树随代码同步；工程纪律最终兑现为产品可靠性。

## 工程如何自证

1. **差分验证体系**——`upstream/deepseek-harness.lock.json` 钉定官方 Harness 基线 commit，官方产物产出黄金结果，原生实现逐项比对，CI 一致性门禁拦截语义漂移。
2. **功能树随代码同步**——全功能中文树（[FEATURE-TREE](docs/FEATURE-TREE.zh-CN.md)）登记模块、功能、代码定位与未合并 PR 标记，配套源码扫描工具核对文档与实现。
3. **多层 CI 门禁**——单测、Android 16 / 17 兼容、架构门禁（禁止伪拆分与复杂度搬家）、性能基线；UI 变更附带明暗主题截图归档。
4. **架构 3.0**——Feature 所有权、Shared Capability 与极薄 Runtime Kernel（[ARCHITECTURE.md](docs/ARCHITECTURE.md)）：应用由 Feature 模块组合，跨 Feature 能力共享，内核层保持极薄。
5. **可信增量更新**——应用内更新走带哈希校验的分发链，减少重复下载完整 APK。

## 能力速览

| 域 | 能做到 |
|---|---|
| 执行内核 | 完整 Agent 循环：计划、目标、任务、技能、主 / 子代理、并行与流水线；持久终端、托管进程、内置 Node / Python / Git（无需 root）；MCP HTTP / stdio、网页搜索与获取、Vision、LSP 按需安装与本地复用 |
| 设备能力 | 无障碍服务、通知读取、VirtualDisplay，在隔离虚拟屏中持续完成设备任务 |
| 聊天模式 | 人物主档案 + 多故事线；逐轮动态形成思维 / 互动 / 表达模式；世界书；日记披露边界；多角色群聊与主动点名；消息修改重发；定时互动；"AI 味"短语硬门禁 |
| 工作模式 | 授权精确、验收真实、交付证据绑定；运行语义对齐 Codex 机制；子代理独立身份；Agent Team 进度按任务状态；审批默认 / 手动 / 全自动三档；checkpoint 与恢复；成果同步进聊天 |
| 多模态创作 | 聊天加号面板提交，阶段卡原位转换成果；图片查看、音频原位播放、视频保存与分享；付费任务持久化防重复扣费；官方额度查询；入口按模型能力显示 |
| 可观测性 | 聊天 / 工作独立 Token 统计并保留本机累计；全部模型与已配置模型切换；7 / 30 / 90 天趋势；单次调用 requestId 全链路追踪；事件账本与会话导出 |
| 通用体验 | 前台服务与系统通知（完成 / 审批 / 提问）；会话搜索、轨迹账本与导出；明暗主题、背景图与自适应阅读层（流体玻璃设计语言）；带哈希校验的应用内增量更新 |

## 安全与边界

- API 密钥由 Android Keystore 加密。
- 写文件、编辑文件、执行高风险命令受审批和能力边界约束。
- 本机文件工具受规范化路径边界约束：包含应用私有工作区与获准的用户共享存储根目录。Shell 以工作区作为默认目录，可执行应用权限允许的命令；Webhook 只监听回环地址。
- Agent 可以实际执行文件、命令与设备操作；请只启用你理解并信任的能力。

## 环境要求

- Android 16+（minSdk 36，targetSdk 36，compileSdk 37）。
- 默认内置运行时按 `arm64-v8a` 打包。
- 模拟器 / x86_64 设备可用 `DSH_RUNTIME_ABIS=x86_64` 自行构建。

## 快速开始

1. 从 [Releases](https://github.com/sy220284/777/releases/latest) 安装 APK。
2. 进入模型设置：配置 API Key，或通过 **Continue with ChatGPT** 连接 ChatGPT 账户并使用符合条件的套餐模型；API Key 使用 Android Keystore 加密存储。
3. 聊天模式直接开聊；工作模式直接派任务；多模态创作在聊天输入区加号面板提交，进度与成果都显示在会话里。

## 构建

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

工具链：

- AGP 9.4.0
- Kotlin 2.4.20
- Jetpack Compose BOM 2026.09.00
- Hilt 2.60.1
- Kotlin 单一源码语言 / JVM 21（构建 JDK 27+）

当前正式版本以 GitHub Releases 的最新标签为准。

发布版本号来自 git 标签：发布工作流导出 `DSH_VERSION_NAME`，`versionCode` 由版本名推导；本地构建回退到 `.github/release-version`。

开发与合并规则见 [AGENTS.md](AGENTS.md)，贡献流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 文档

- [文档入口](docs/README.md)
- [功能树](docs/FEATURE-TREE.zh-CN.md)：全功能中文树，模块、功能、代码定位与未合并 PR 标记，随代码同步维护
- [架构](docs/ARCHITECTURE.md) · [协议](docs/PROTOCOL.md) · [兼容性](docs/COMPATIBILITY.md) · [安全](docs/SECURITY.md)
- [Android 原生 Harness 当前状态](docs/ANDROID-HARNESS-STATUS.zh-CN.md) · [后续路线](docs/ANDROID-HARNESS-ROADMAP.zh-CN.md)
- [验证体系](docs/VALIDATION.md) · [UI / UX](docs/UI-UX.zh-CN.md) · [效果图](docs/UI-ARTIFACTS.md)

## 许可证

[MIT](LICENSE)。第三方材料见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

DeepSeek Harness 及其品牌归各自所有者所有；本项目是独立的社区构建，并已沿自己的产品与架构路径持续演化。
