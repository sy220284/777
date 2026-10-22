<h1 align="center">神言神语</h1>

<p align="center">
  <strong>一部 Android 手机，一个能对话、能调用工具、能持续处理任务的 AI 工作台。</strong>
</p>

<p align="center">
  原生界面 · 本机 Agent 内核 · 多模型接入 · 聊天与工作双模式
</p>

<p align="center">
  <a href="https://github.com/sy220284/777/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/sy220284/777/ci.yml?branch=main&style=flat-square"></a>
  <a href="https://github.com/sy220284/777/releases/latest"><img alt="最新版本" src="https://img.shields.io/github/v/release/sy220284/777?style=flat-square"></a>
  <img alt="Android 16+" src="https://img.shields.io/badge/Android-16%2B-3DDC84?style=flat-square">
  <img alt="arm64-v8a" src="https://img.shields.io/badge/ABI-arm64--v8a-6E4C9B?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/License-MIT-blue?style=flat-square"></a>
</p>

<p align="center">
  <a href="https://github.com/sy220284/777/releases/latest"><strong>下载 Android APK</strong></a>
  · <a href="docs/FEATURE-TREE.zh-CN.md">查看完整功能树</a>
  · <a href="docs/README.md">阅读项目文档</a>
  · <a href="CHANGELOG.md">更新记录</a>
</p>

---

## 这是什么

**神言神语**是一款开源的 Android 本机 AI 应用。它把聊天、人物记忆、任务规划、工具执行和成果展示放在同一个原生界面中。

你可以用它与有持续经历的人物聊天，也可以把文件处理、代码检查、资料检索等任务交给工作模式。AI 负责理解和决策；会话、工具调度、工作区、状态记录与恢复由手机上的 Harness 内核管理。

**不需要 root，也不要求电脑或自建云中继持续在线。** 但模型推理和部分外部工具需要网络与相应服务商授权；端侧运行不等于模型完全离线。

## 你可以用它做什么

| 想做的事 | 对应能力 |
| --- | --- |
| **日常聊天与角色互动** | 人物图集、人物档案、多故事线、关系与经历记忆、日记、单聊与群聊 |
| **让 AI 实际完成工作** | 任务规划、多轮执行、文件与 Git、终端命令、工具调用、结果与执行过程回看 |
| **处理复杂任务** | 子代理、Agent 团队、并行协作、任务状态、权限审批、中断恢复 |
| **扩展可用工具** | MCP、LSP、网页检索与读取、设备能力、技能及工作区工具 |
| **在对话中创作** | 在符合模型和账户条件时，使用图片、视频、配音、语音转写等多模态能力 |
| **掌握模型和消耗** | 多模型配置、模型能力路由、聊天/工作模式预算、Token 用量与历史记录 |

这里列的是**当前主分支登记的能力范围**，不代表每项功能在任何手机、模型和权限组合下都可用。具体入口与代码位置见[功能树](docs/FEATURE-TREE.zh-CN.md)，已知测试范围与限制见[验证说明](docs/VALIDATION.md)。

### 聊天模式：人物与故事持续下去

创建或选用人物，设定其背景与故事线，随后在单聊或群聊中互动。人物的关系、经历和日记按身份与场景管理，避免不同角色之间直接混用私有记忆。

会话支持历史查看、消息编辑与重发、回复重新生成、分支等操作。人物状态随实际对话更新；能否主动互动、何时触发，由对应设置和运行条件决定。

### 工作模式：从要求到可检查的结果

把目标交给 Agent，它可以按任务需要规划步骤、请求权限、使用工具、读取结果并继续处理。工作过程、工具输出、待办和成果与会话关联，便于判断任务走到了哪一步。

例如，你可以提出：

> 检查这个项目的代码，列出能复现的问题，修复后运行相关测试，并把修改和验证结果交给我。

工具能执行哪些操作，取决于当前模型、设备权限、工具配置和审批策略。**“已经尝试执行”不自动等于“任务已完成”**；涉及外部服务、真实设备或第三方账户的任务仍需对应环境验证。

### 多模态：成果直接回到会话

支持的 MiniMax 模型可在聊天输入区的「+」入口提供图片、视频、配音、转写等能力。生成任务在会话中显示进度，完成后可查看或分享对应成果。具体功能依赖所选官方模型、服务可用性和账户额度；部分调用可能产生费用。

## 快速开始

1. 打开 [GitHub Releases](https://github.com/sy220284/777/releases/latest)，下载并安装适合设备的 APK。
2. 进入应用的**模型设置**，配置所需服务商的 API Key；如果账户与授权条件满足，也可使用 **Continue with ChatGPT** 登录符合条件的套餐模型。
3. 选择**聊天**或**工作**模式。聊天可先创建/选择人物；工作可以直接描述目标，在需要时确认工具权限。
4. 按实际需要启用文件、通知、相机、无障碍等权限。未授权的设备能力不会因此自动获得系统权限。

首次使用建议先发送一条普通消息，确认模型可响应；再在工作模式尝试一个无需高风险权限的小任务。模型接入和工具接入是两回事：模型能聊天，不代表已经配置了所有执行工具。

**系统要求**

- Android **16 及以上**（minSdk 36；targetSdk 36；compileSdk 37）。
- 官方默认 APK 面向 **arm64-v8a** 设备；x86_64 主要用于自行构建及模拟器验证。
- 当前界面以 Android 手机为主要目标，部分能力还会受到屏幕、系统版本和厂商策略影响。
- 正式版版本号与安装包以 [Releases](https://github.com/sy220284/777/releases/latest) 为准，不能用源码目录中的回退版本号判断最新发行版。

完整兼容性说明：[COMPATIBILITY.md](docs/COMPATIBILITY.md)。

## 本机运行、数据和权限边界

神言神语的 Agent 内核、会话与人物数据、工具编排、工作区及运行记录在 Android 本机工作；当前发行版不需要远程 Harness 中继。

- **模型与外部服务**：调用云端模型、搜索、MCP 或多模态服务时，相关请求会按所选服务的接口发送至外部。请自行确认服务商的数据政策和计费规则。
- **凭据**：模型密钥与本机账户凭据使用 Android Keystore 加密保存；ChatGPT 登录不意味着应用可以读取你在 ChatGPT 中的历史会话或记忆。
- **工具执行**：文件、终端、网络和设备操作分别受工具规则、审批与 Android 系统权限限制。默认、手动、自动三种审批档位的风险不同，自动档位应谨慎使用。
- **恢复与记录**：会话事件、运行状态及部分任务检查点在本机持久化；对结果未知的高风险副作用不会在恢复时直接盲目重放。
- **平台限制**：Android 无法保证任意后台进程永久存活；本项目不承诺完整桌面浏览器、Windows/PowerShell 或任意桌面插件环境。

更多细节请阅读[安全边界](docs/SECURITY.md)与[原生 Harness 状态](docs/ANDROID-HARNESS-STATUS.zh-CN.md)。

## 它如何工作

```text
你在 Chat / Work 发起请求
        ↓
装配对应会话、人物或任务上下文
        ↓
按已配置的模型、身份和能力路由请求
        ↓
模型回复 ── 或提出工具调用
                  ↓
            工具校验与审批
                  ↓
            本机/外部能力执行
                  ↓
            执行结果返回模型
        ↓
把消息、过程、结果和必要的恢复信息写入会话记录
        ↓
在原生界面展示回复与交付成果
```

聊天和工作共用底层会话、模型、工具和事件能力，但各自管理业务状态。系统按**架构 3.0**组织为产品 Feature、Shared Capability、平台基础设施及极薄的进程运行内核；UI 展示的是业务运行结果，不自行充当第二份任务事实源。

架构与协议详情：[ARCHITECTURE.md](docs/ARCHITECTURE.md) · [PROTOCOL.md](docs/PROTOCOL.md)。

## 开发与构建

仓库采用 **Kotlin、Jetpack Compose、Gradle**。标准开发环境为 **Linux / WSL2 Ubuntu x86_64**；构建使用 JDK 27+，生成 JVM 21 目标字节码。当前 Android 工具链与依赖版本以[开发文档](docs/DEVELOPMENT.md)和 Gradle 配置为准。

项目的标准本地开发流程使用 GitHub Actions 生成并校验的工具链 Artifact，按需安装缺失组件，然后**离线构建**。请先阅读[工具链下载及安装步骤](docs/DEVELOPMENT.md)：

```bash
git clone https://github.com/sy220284/777.git shenyan-shenyu
cd shenyan-shenyu

# 查看本机还缺哪些构建组件
bash tools/dev/ai-toolchain.sh plan --profile build

# 从 main 最新成功的「开发工具链」工作流下载缺失 Artifact，
# 解压到同一目录后安装
bash tools/dev/ai-toolchain.sh bootstrap \
  --profile build \
  --artifacts-dir /tmp/shenyan-artifacts

# 检查环境并离线构建 Debug APK
bash tools/dev/ai-toolchain.sh check
bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug
```

已有符合要求的工具和缓存会被复用。**不要把常规联网 Gradle 构建当作本仓库规定的工具链安装路径。** 构建 x86_64 模拟器包时，可以设置 `DSH_RUNTIME_ABIS=x86_64`；完整测试环境的额外 Artifact、Android 16/17 模拟器与 optimized APK 构建见[开发文档](docs/DEVELOPMENT.md)。

### 仓库结构

| 目录 | 职责 |
| --- | --- |
| `app/` | Android 应用、原生 UI、业务 Feature 与应用组合 |
| `core/` | 多模块共用的基础数据与网络工具 |
| `harness-core/` | 平台无关的 Agent、工具和会话核心契约 |
| `harness-runtime-android/` | Android 运行时、进程与终端能力 |
| `harness-interop/` | MCP、LSP、GitHub 等互通适配 |
| `harness-device-android/` | Android 设备能力 |
| `reference-validation/` | Harness 语义对照与回归验证 |
| `docs/` | 架构、功能、兼容、安全和验收文档 |
| `tools/`、`.github/` | 本地工具链、构建脚本与 CI / 发布流程 |

### 质量与验收

CI 根据改动风险选择对应门禁：文档检查、静态与架构规则、单元测试、ARM64/x86 构建以及 Android 16/17 模拟器验证。**某条 CI 成功记录只能证明该次提交运行的检查通过**，不能替代外部账户、真实设备与完整端到端验收。正式 APK 的发布资格单独受完整主分支验证约束。

权威说明：[VALIDATION.md](docs/VALIDATION.md) · [贡献指南](CONTRIBUTING.md) · [工程规则](AGENTS.md)。

## 文档导航

- [功能树与代码定位](docs/FEATURE-TREE.zh-CN.md) · [功能审计](docs/FUNCTION-AUDIT.zh-CN.md)
- [系统架构](docs/ARCHITECTURE.md) · [原生 Harness 当前状态](docs/ANDROID-HARNESS-STATUS.zh-CN.md)
- [UI / UX 规范](docs/UI-UX.zh-CN.md) · [开发工具链](docs/DEVELOPMENT.md)
- [安全说明](docs/SECURITY.md) · [兼容性](docs/COMPATIBILITY.md) · [验证规则](docs/VALIDATION.md)
- [完整文档目录](docs/README.md) · [变更记录](CHANGELOG.md)

## 开源与来源

本仓库起源于 [`sorsama/deepseek-harness-mobile` 的 `e5f8c2f` 提交](https://github.com/sorsama/deepseek-harness-mobile/commit/e5f8c2f)，后续独立演进为 Android 应用 **`com.sy220284.dshmobile`**。官方 Harness 语义参考由 [`upstream/deepseek-harness.lock.json`](upstream/deepseek-harness.lock.json) 锁定，供差分验证使用；它不代表本应用是上游官方发行版。

项目采用 [MIT 许可证](LICENSE)；第三方许可及声明参见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。安全问题请按 [SECURITY.md](SECURITY.md) 中的方式私下反馈。
