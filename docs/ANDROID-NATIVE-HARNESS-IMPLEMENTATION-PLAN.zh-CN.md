# 777 Android 原生 Harness 内核：完整规划与实施方案

> 状态：规划基线  
> 适用项目：`sy220284/777`  
> 平台范围：Android 16+（API 36+）  
> 当前 777 基线：`0.12.0-777.5`  
> 官方对照基线：`deepseek-ai/deepseek-harness@ddefc45fbc7f8e46dd73185e68295696d1297887`（`0.1.6-alpha.2`）

## 1. 最终目标

777 的长期定位调整为：

> **777 本身就是 Android 原生版 DeepSeek Harness。**

Harness 的 Agent、Session、Tool、Workflow、Subagent、Goal、Todo、Plan、Job、Compaction、Skill、MCP、LSP、插件能力等，应成为 APK 自身底层能力，而非在 APK 内额外启动 Ubuntu + Node + 官方 Harness 作为第二套主体。

官方 DeepSeek Harness 的源码承担三类职责：

1. **语义标准**：定义相同行为在官方实现中的正确结果。
2. **协议标准**：定义 Session Event、工具协议、RPC、配置与持久化格式。
3. **测试标准**：作为持续集成中的参考实现，用于差分验证 Android 原生实现。

官方 TypeScript/Node 代码原则上不进入 APK 的主运行链路。

Node.js、Python、Git、语言服务器和本机 MCP 进程可以作为 **Agent 可调用的工具运行环境** 存在，但不能反过来承载 Harness Core。

---

## 2. 架构原则

### 2.1 必须保持的原则

- Android 16 是唯一目标平台，不为 Android 8～15 增加兼容债务。
- Harness Core 使用 Kotlin 原生实现。
- UI、Android 能力和 Harness Core 必须解耦。
- Harness Core 不直接依赖 Compose、Activity、Service、Shizuku 或无障碍服务。
- 模型可见的状态必须来自可持久化的 Session Event。
- 文件、进程、终端、网络、设备控制全部通过能力接口接入。
- 官方行为兼容以“输入/事件/状态/输出一致”为准，不追求类名或代码结构逐行相似。
- 官方版本对齐由人工发起；更新官方基线前必须完成差分测试并明确兼容影响。
- 不能保持官方语义的能力必须显式标记“Android 替代实现”或“平台不支持”，禁止伪装兼容。

### 2.2 明确不采用的路线

- 不把 Ubuntu/PRoot 作为 Harness Core 的宿主。
- 不在 APK 内直接运行一份官方 Node Harness 作为主引擎。
- 不为了复用官方代码牺牲 Android 原生生命周期和权限模型。
- 不将所有逻辑继续堆进 `LocalHarnessEngine.kt`。
- 不让外部工具环境拥有 Session、Agent Loop、审批或工具注册的控制权。

---

## 3. 目标总体架构

```text
777
├─ app
│  ├─ Compose UI
│  ├─ Android 生命周期
│  ├─ 导航 / 设置 / 通知
│  └─ 本机与远程模式入口
│
├─ harness-core
│  ├─ agent
│  ├─ session
│  ├─ prompt
│  ├─ context
│  ├─ tools
│  ├─ approvals
│  ├─ goal
│  ├─ todo
│  ├─ plan
│  ├─ jobs
│  ├─ workflow
│  ├─ subagent
│  ├─ skill
│  ├─ compaction
│  ├─ plugin
│  └─ scheduler
│
├─ harness-protocol
│  ├─ Session Event
│  ├─ Tool Schema
│  ├─ RPC DTO
│  ├─ Config Schema
│  └─ 上游版本兼容模型
│
├─ harness-runtime-android
│  ├─ Android 文件系统
│  ├─ Android 进程执行
│  ├─ PTY
│  ├─ 网络
│  ├─ Keystore
│  ├─ 工作区
│  └─ 后台任务
│
├─ harness-device-android
│  ├─ Shizuku
│  ├─ 无障碍
│  ├─ 截图 / 视觉
│  ├─ App 控制
│  ├─ 系统设置
│  ├─ 通知 / 剪贴板
│  └─ 虚拟屏
│
├─ harness-interop
│  ├─ MCP
│  ├─ LSP
│  ├─ 外部进程协议
│  └─ Git / Python / Node 工具接入
│
└─ reference-validation
   ├─ 官方版本基线
   ├─ 参考实现 Runner
   ├─ 一致性测试夹具
   └─ 差异报告
```

---

## 4. Core 重构方案

现有 `LocalHarnessEngine.kt` 已覆盖大量主链路，但职责过于集中。后续禁止继续向单类叠加核心能力。

### 4.1 Agent

建议拆分：

```text
agent/
├─ Agent.kt
├─ AgentLoop.kt
├─ AgentState.kt
├─ Turn.kt
├─ Step.kt
├─ AgentInbox.kt
├─ AgentCancellation.kt
└─ AgentRetryPolicy.kt
```

必须对齐官方核心语义：

- turn / step 生命周期；
- 一轮模型请求可包含多次工具调用；
- 工具调用结束后是否继续下一 step；
- 重试不得重复提交用户输入；
- 取消时不得产生伪造的完成事件；
- Assistant、ToolCall、ToolResult 的持久化顺序；
- 请求上下文冻结与重放。

### 4.2 Session

```text
session/
├─ Session.kt
├─ SessionEvent.kt
├─ SessionStore.kt
├─ SessionProjection.kt
├─ SessionMigration.kt
├─ SessionQuery.kt
└─ SessionExport.kt
```

目标：

- 追加式事实日志作为唯一真源；
- UI 状态由 Projection 计算；
- Session 格式有显式版本；
- 支持相邻版本迁移；
- 新版读取旧会话，禁止静默破坏旧数据；
- 回滚时必须能识别“未来版本”并拒绝错误写入；
- 会话搜索、轨迹、分叉、导出基于同一事实日志。

### 4.3 Tool Registry

从静态 `LocalToolCatalog` 升级为动态注册：

```kotlin
interface HarnessTool {
    val name: String
    val schema: ToolSchema
    suspend fun execute(context: ToolContext, input: JsonObject): ToolResult
}
```

Tool Registry 负责：

- 注册 / 卸载；
- 作用域；
- schema；
- 审批策略；
- 超时；
- 输出裁剪；
- 执行前后钩子；
- 审计事件。

### 4.4 Capability Provider

所有操作系统能力必须经过接口：

```text
HarnessFileSystem
HarnessProcessRuntime
HarnessTerminalProvider
HarnessNetworkProvider
HarnessCredentialStore
HarnessDeviceProvider
HarnessLlmProvider
HarnessScheduler
```

Core 只认识接口。

---

## 5. 原生插件体系

官方 Harness 的关键思想“万物皆插件”应在 Android 内核中保留，但实现为 Kotlin 原生插件模型。

建议接口：

```kotlin
interface HarnessPlugin {
    val id: String
    suspend fun install(context: HarnessContext)
    suspend fun uninstall()
}
```

`HarnessContext` 提供：

- ToolRegistry
- CapabilityRegistry
- EventRegistry
- ProjectionRegistry
- CommandRegistry
- ModelRegistry
- SettingsRegistry

第一阶段插件仅支持 APK 内置可信插件；后续再评估动态加载机制。

禁止直接加载未知 APK/Dex 作为 Harness 插件，避免把插件系统变成任意代码执行入口。

---

## 6. 功能实施路线

### P0：原生 Harness Core 架构化

目标：现有功能不倒退的前提下完成底层拆分。

工作：

- 建立独立 Core 模块；
- 拆 Agent / Session / Tool / Job；
- 抽 Capability Provider；
- UI 只依赖公开状态接口；
- 建立 Session Event 统一模型；
- 增加 Core 纯 JVM 测试。

验收：

- 当前本机 Harness 所有既有功能保持；
- 无 Compose 依赖即可运行核心测试；
- Agent Loop 可使用假的模型和假的工具完整跑一轮；
- 进程重启后 Session 可恢复。

### P1：官方 Agent / Session 完整语义对齐

目标：让 777 从“功能相似”升级为“行为兼容”。

覆盖：

- turn / step；
- request series；
- assistant attempt；
- tool call / result；
- retry；
- cancellation；
- context projection；
- session fork；
- resume；
- compaction；
- spill；
- result presentation；
- session query。

验收采用官方参考实现差分测试，见第 10 节。

### P2：插件与能力注册架构

目标：后续功能不再修改 Agent Loop。

实现：

- Plugin Registry；
- Tool Registry；
- Capability Registry；
- Event hooks；
- Projection；
- Agent scope；
- 配置开关。

验收：

新增一个工具或能力时，不需要修改 `AgentLoop.kt`。

### P3：完整进程 / 终端 / 工具环境

Harness Core 保持 Kotlin 原生，额外提供可调用工具。

能力：

- `/system/bin/sh` 完善；
- PTY；
- Git；
- Python；
- Node.js；
- curl；
- SSH；
- rg；
- jq；
- tar/zip；
- 常用 POSIX 工具；
- 后台进程树；
- 信号 / 超时 / 强制停止；
- 标准输入输出流。

实现方式可按工具分别内置 Android/ARM64 可执行文件，不要求存在完整 Ubuntu。

验收：

- Git clone / diff / status；
- Python 脚本；
- Node 脚本；
- PTY 交互；
- 后台任务恢复状态；
- 非零退出码与信号准确报告。

### P4：MCP

优先实现：

1. Streamable HTTP；
2. SSE / HTTP；
3. stdio。

MCP Client 必须原生 Kotlin 实现。

本机 stdio MCP 可通过 Process Runtime 启动 Node/Python/原生二进制。

验收：

- MCP 工具可进入 Tool Registry；
- MCP 崩溃不会拖死 Agent；
- 权限与凭据隔离；
- 能完整取消挂起调用；
- 重连有边界。

### P5：LSP

实现 Kotlin 原生 LSP Client，通过 PTY/stdio 驱动语言服务器。

首批：

- TypeScript / JavaScript；
- Python；
- Kotlin；
- Rust；
- Go；
- clangd。

Harness 工具层提供：

- definition；
- references；
- hover；
- diagnostics；
- symbols；
- rename（需要审批）。

### P6：Android Device Provider

新增 Android 专属工具族。

#### Shizuku

负责：

- 包管理；
- Activity / Service 调用；
- settings；
- dumpsys；
- 系统属性；
- 进程与权限状态；
- 安装 / 卸载（必须审批）。

#### 无障碍

负责：

- 控件树；
- 文本定位；
- 点击；
- 输入；
- 滚动；
- 返回 / 首页。

#### Android API

负责：

- 剪贴板；
- 通知；
- Intent；
- 文件选择；
- 分享；
- 电量与网络状态；
- 应用信息。

所有高风险操作必须显示明确审批目标与参数。

### P7：截图、视觉与虚拟屏

能力顺序：

1. 屏幕截图；
2. 视觉模型输入；
3. 元素 / 坐标定位；
4. 操作后验证；
5. VirtualDisplay；
6. 独立 App 启动；
7. 虚拟屏输入注入；
8. 实时预览。

虚拟屏不得直接绕过 Harness 权限策略。

### P8：调度、Webhook 与长期任务

实现：

- WorkManager 持久任务；
- Android 允许范围内的精确提醒；
- 条件监测；
- Webhook 入口（默认关闭）；
- 重启恢复；
- 通知回调；
- 失败退避。

Android 后台限制无法提供桌面常驻进程的秒级语义，必须明确记录平台差异。

### P9：生产化与自愈

包括：

- 运行环境诊断；
- 数据一致性检查；
- Session 修复；
- Capability 自检；
- 更新回滚；
- 设备能力矩阵；
- 崩溃恢复；
- 安全审计；
- 性能基准；
- 真机矩阵。

---

## 7. Android 专属能力目录建议

最终 Tool Registry 可包含：

```text
android_device_info
android_screen
android_screenshot
android_tap
android_type
android_swipe
android_back
android_home

android_app_list
android_app_info
android_app_launch
android_app_stop
android_app_install
android_app_uninstall

android_settings_get
android_settings_set
android_permissions

android_notification_list
android_clipboard_get
android_clipboard_set

android_vscreen_create
android_vscreen_status
android_vscreen_launch
android_vscreen_tap
android_vscreen_swipe
android_vscreen_key
android_vscreen_close
```

命名应独立于普通 `bash`、`read` 等跨平台工具，避免平台行为混淆。

---

## 8. 官方基线维护

官方 Harness 仍作为语义、协议与测试参考，但 **不设置自动监测、自动同步、自动生成同步 PR 或自动跟随官方版本的机制**。

项目只维护一个明确的“已验证官方基线”：

```text
upstream/deepseek-harness.lock.json
```

建议内容：

```json
{
  "repository": "deepseek-ai/deepseek-harness",
  "version": "0.1.6-alpha.2",
  "commit": "ddefc45fbc7f8e46dd73185e68295696d1297887",
  "verifiedAt": "2026-09-21"
}
```

规则：

- 该文件只表示当前 777 已验证兼容的官方参考版本；
- 不定时轮询官方仓库；
- 不因为官方发布新版本自动创建分支、PR 或任务；
- 不自动修改 Kotlin Core、协议模型或工具目录；
- 只有在人工决定“对齐某个官方版本”时，才更新基线并执行完整差分验证；
- 对齐失败时继续保留上一份已验证基线，不影响当前 APK 开发与发布。

---

## 9. 官方一致性差分测试

官方 Harness 只在开发/CI 参考环境运行，不进入 APK。

```text
测试向量
├─ 输入
├─ Mock LLM
├─ Mock Tool
└─ 初始 Session
       │
       ├─ Official Harness Runner（参考环境）
       │
       └─ 777 Native Harness（JVM）
                 ↓
          Canonical Result
                 ↓
              比较
```

比较内容：

- Session Event 顺序；
- 模型可见消息；
- Tool Schema；
- Tool Call；
- Tool Result；
- Retry 次数；
- Cancellation；
- Goal / Todo / Plan；
- Workflow；
- Subagent；
- Compaction；
- Fork / Resume；
- Projection；
- 错误分类。

对时间戳、UUID、临时路径等非确定字段先做 Canonicalize，再比较。

### 9.1 必须覆盖的黄金案例

- 无工具普通对话；
- 单工具；
- 多工具并行；
- 工具失败；
- 工具超时；
- 模型 429；
- 模型 5xx；
- 用户停止；
- 审批拒绝；
- Ask User；
- Plan Mode；
- Goal；
- Todo；
- 后台 Job；
- Subagent；
- Workflow parallel；
- Workflow pipeline；
- Session fork；
- Session resume；
- Compaction；
- 大工具输出；
- 旧 Session 迁移。

人工决定切换官方参考基线时，以上案例全部重跑；差异必须先解释并完成移植，之后才能更新锁定版本。

---

## 10. APK 自动更新

这一部分仅指 **777 自己向用户设备发布新版 APK**，与官方 Harness 版本同步无关。

App 内更新器可以：

- 检查 777 Release；
- 获取签名更新清单；
- 比较 versionCode；
- 下载 APK；
- 校验 SHA-256；
- 校验签名证书；
- 调用系统 PackageInstaller；
- 安装前保留数据检查点。

更新通道：

- 稳定；
- 预览；
- 固定版本。

如果系统要求用户确认安装，则正常打开系统确认界面；禁止依赖无法保证的静默安装。

---

## 11. 数据与回滚

Harness Core 升级比普通 UI 升级风险更高。

每次涉及 Session/Persistence 的更新必须：

1. 创建升级检查点；
2. 用副本执行迁移；
3. 验证旧会话；
4. 验证新会话；
5. 验证 resume / fork / query；
6. 成功后再切正式数据。

禁止降级程序直接写入未来版本 Session。

至少保留一个可读的上一版数据检查点。

---

## 12. 安全模型

### 12.1 权限层级

建议统一分级：

- 只读；
- 工作区写入；
- 普通进程执行；
- 网络；
- Android 界面操作；
- Shizuku 系统操作；
- 安装 / 卸载；
- 外部目录访问。

工具必须声明能力级别。

### 12.2 默认策略

- read/search：工作区内默认允许；
- write/edit：审批；
- shell：审批；
- 安装/卸载：强审批；
- Shizuku 修改系统设置：强审批；
- 无障碍点击涉及付款、账号、安全设置：阻断或二次确认；
- 外部应用私有数据：普通 App 权限下不可访问；
- API Key：Keystore；
- Session：应用私有目录；
- 系统备份：继续关闭。

---

## 13. CI 最终门禁

每个 PR 至少执行：

```text
Core JVM tests
Protocol tests
App unit tests
Lint
R8
Android 16 instrumentation
16 KB APK / ELF alignment
Security regression
Session migration
Tool contract
Compatibility fixtures
```

涉及 Harness Core 或人工切换官方参考基线时增加：

```text
Official reference runner
Differential conformance suite
```

涉及 Android Device Provider 时增加真机测试矩阵，模拟器测试不能替代 Shizuku、无障碍、厂商 ROM 行为。

---

## 14. 推荐仓库模块演进

第一阶段避免一次性大搬家，可按顺序迁移：

```text
当前
app/local/*

阶段 1
:harness-core
:harness-protocol
app

阶段 2
:harness-runtime-android
:harness-device-android

阶段 3
:harness-interop
```

官方参考实现、测试夹具与版本锁可继续放在 `tools/`、`docs/` 或单独的测试目录中，不建立自动同步模块。

每迁移一个能力都保持主线可构建、可安装、可回滚。

---

## 15. 实施里程碑

### M0：规划与基线

- 本文档入库；
- 记录当前官方参考 commit；
- 建立能力矩阵。

完成标准：团队对“原生内核、不内嵌官方 Harness 主运行时、不自动同步官方版本”无歧义。

### M1：Core 拆分

- Agent；
- Session；
- Tool；
- Job；
- Capability；
- 纯 JVM 测试。

完成标准：现有本机能力零回退。

### M2：官方差分测试

- Reference Runner；
- Canonicalizer；
- 黄金案例；
- CI 验证入口。

完成标准：人工对齐官方版本时能够精确识别行为差异。

### M3：Plugin / Capability

完成标准：新增工具不改 Agent Loop。

### M4：Process / PTY / 工具链

完成标准：手机可以承担真实代码代理任务。

### M5：MCP + LSP

完成标准：外部工具生态可接入。

### M6：Android Device Provider

完成标准：Agent 可以结构化读取与操作 Android。

### M7：视觉 + 虚拟屏

完成标准：复杂 App 自动化可以在独立显示环境运行。

### M8：调度 / 长任务 / Webhook

完成标准：支持可恢复的长期 Agent 工作。

### M9：生产化

完成标准：更新、迁移、回滚、自愈和真机矩阵闭环。

---

## 16. 第一批具体开发任务

接下来建议按下面顺序创建实施 PR：

1. `refactor: extract native harness core`
2. `test: add official harness differential runner`
3. `feat: add native plugin and capability registry`
4. `feat: add process runtime and persistent PTY`
5. `feat: add native MCP client`
6. `feat: add native LSP client`
7. `feat: add Shizuku Android provider`
8. `feat: add accessibility and screen provider`
9. `feat: add vision interaction loop`
10. `feat: add virtual display agent runtime`
11. `feat: add signed in-app updater`

不得把 P0～P9 全塞进一个巨型 PR。

---

## 17. 成功判据

项目完成这一阶段演进后，应同时满足：

- 没有电脑也能完成完整 Agent 任务；
- Harness Core 不依赖 Node/Ubuntu；
- 官方版本只在人工决定对齐时更新参考基线；
- 同一测试向量下官方参考实现与 777 结果一致；
- 语义差异能够通过差分测试定位到具体模块；
- Git / Python / Node 是工具，不是 Harness 宿主；
- MCP / LSP 原生接入；
- Agent 可以操作 Android 系统和 App；
- 官方新版本不会在未经人工决定的情况下改变 777 的代码、规划或发布节奏。

最终目标：

> **保留官方 Harness 的行为与生态能力，同时让这些能力真正成为 Android APK 自己的底层。**
