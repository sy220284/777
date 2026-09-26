# Android 16 本机 Harness 适配审计

审计基线：`deepseek-ai/deepseek-harness@477b4f420553e8a52c2fbccc464d7561b239c443`
（官方 `0.1.7-rc.2`）。本文件只评价 APK 内的本机模式；电脑远程模式继续使用官方
网页协议，范围见 `COMPATIBILITY.md`。官方该基线的 Session 写入格式为 V4；本机侧以语义等价为目标，不直接复刻其物理载体。

## 结论

以官方 `standard` preset 默认启用的能力为准，APK 已覆盖其中可在普通 Android 应用沙箱内保持语义的
主链路：模型轮次、推理与工具调用、追加式会话
事实、多会话持久化、权限审批、文件系统、文本发现、系统命令、后台任务、网页搜索与安全抓取、技能、
规划模式、目标、任务清单、用户问答、子代理、会话分叉、并行/流水线工作流、会话查询、结果交付标记、重试、
工具结果裁剪和上下文压缩。

实现遵循官方的关键不变量：模型看到的系统提示、用户消息、请求元数据、助手消息、工具调用和工具结果会先后进入
有大小上限并自动轮转的 `session.events.jsonl`；工具结果返回模型前有大小上限；模型请求按可重试错误执行有界退避；写入、编辑
和命令执行经过用户审批；文件与命令共享同一个应用私有工作区。

## 对照表

| 官方能力族 | Android 16 本机实现 | 状态 |
| --- | --- | --- |
| Agent loop / DeepSeek 适配器 | 多步模型—工具循环，保留 `reasoning_content` 和原始工具消息 | 已适配 |
| Session / persistence / projection | 多会话快照、有界追加式事件日志、会话列表与切换 | Android 等价实现 |
| LLM retry / cancellation | 408、429、5xx 与网络错误有界重试；轮次可停止 | 已适配 |
| Compaction / spill | 字符资源预算与模型上下文预算双层治理；每个模型 step 检查压力；超长工具结果安全保留头尾，完整内容进入 Session 私有 spill 并可分页回读 | Android 等价实现 |
| `read` / `write` / `edit` | 保留官方工具名；有界读取、完整写入、唯一字面量替换、路径穿越防护 | 已适配 |
| `glob` / `grep` | 保留官方工具名；原生 glob 与递归文本搜索，不依赖设备预装 `rg` | 已适配 |
| `bash` | 保留官方工具名；以 `/system/bin/sh` 执行，支持超时、取消、输出上限、审批 | Android 等价实现 |
| `job_*` | 后台命令实时输出，以及后台任务/子代理的列表、输出、停止 | 已适配 |
| `web_search` | 与官方相同的 DeepSeek Anthropic Messages 搜索格式 | 已适配 |
| `web_fetch` | 公网地址校验、域名解析固定、重定向复验、正文上限 | 已适配 |
| `skill` / instructions | `.dsh/skills/*/SKILL.md` 发现和读取 | 已适配 |
| plan mode / review | 只读规划约束、`exit_plan_mode` 审批、执行模式切换 | 已适配 |
| goal / todo | 会话目标和任务清单持久化、界面投影 | 已适配 |
| ask user | 工具暂停轮次，弹窗选项或自定义回答 | 已适配 |
| `subagent` / `subagent_fork` | 新上下文、继承会话、可选模型、后台执行、列表、追加消息和中断 | Android 等价实现 |
| workflow | 最多四个只读子任务并行执行，或按流水线逐步传递结果 | Android 等价实现 |
| session query | 五个官方查询面：跨会话搜索、事件搜索、会话轨迹、事件轨迹、完整事件读取 | Android 等价实现 |
| present | 校验工作区成果文件存在及大小后标记交付 | Android 等价实现 |
| approvals / sandbox policy | 应用私有目录硬边界，加逐次审批 | 已适配 |
| native process / persistent terminal | 原生参数数组进程执行、可跨多次工具调用保持的管道终端；输出读取阶段有硬上限 | Android 等价实现 |
| HTTP / stdio MCP | HTTP 与设备本地 stdio MCP 可动态发现并注册工具；全部远端工具按高权限逐次审批 | 已适配 |
| credentials | Android Keystore AES/GCM 加密；模型接口拒绝远程明文 HTTP；Webhook 完整令牌不进入模型/事件日志，只能经审批复制到敏感剪贴板 | Android 强化实现 |

## Android 16 专项

- `minSdk`、`targetSdk`、`compileSdk` 均为 36，低版本设备无法安装。
- 构建插件升级到支持接口级别 36 的 8.9.2。
- 全面屏布局统一使用安全绘制区域；不设置退出全面屏的兼容开关。
- Compose 返回处理接入 Android 16 预测返回接口；审批和提问优先关闭，避免轮次悬挂。
- 大屏、折叠屏和自由窗口不锁定方向、比例或尺寸；窄屏按钮拆行，状态放入可滚动区域。
- 局域网扫描与直连已移除；远程控制仅使用 HTTPS 中继配对。
- 应用启用严格意图过滤匹配，外部深链必须满足已声明规则。
- 删除 Android 8～15 的通知、WebView 和语言兼容分支。
- 正式交付 APK 仅包含 `arm64-v8a`；`x86_64` 仅用于 Android 16 模拟器测试。所有随包 ELF 都会校验
  `LOAD` 段 16 KB 对齐，APK 内未压缩库的数据偏移也按 16 KB 对齐。
- 普通 CI 与发布构建都会校验 `minSdk=36`、目标 ABI 以及 16 KB APK/ELF 对齐，防止依赖升级后回退。
- CI 在 Android 16 模拟器运行界面与协议集成测试；交付 APK 使用 R8 与资源收缩，并关闭调试能力。

## 无法保持官方语义的能力

下列功能依赖桌面操作系统、Node 22、任意第三方进程或完整浏览器宿主。普通 Android 应用沙箱无法提供
同等执行环境，因此不放入本机工具目录；电脑远程模式仍可完整使用它们。

| 官方能力 | 原因 |
| --- | --- |
| Cordis 动态插件、组合包、热重载、`plugin_manager`、Cordis 自省 | APK 无法安装和执行任意 Node 包及其构建脚本 |
| `run_code` 程序化工具调用 | 官方运行时依赖 Node 沙箱和动态生成的工具绑定 |
| PowerShell / Windows 工具 | Android 没有 Windows 进程与 PowerShell 环境 |
| 完整 PTY、桌面终端控制 | 当前提供持久管道终端；Android 公共应用接口仍不能等价提供桌面 PTY/完整 Unix 用户空间 |
| Stagehand 浏览器自动化 | 依赖受控 Chromium、桌面页面上下文和额外模型服务 |
| Office 转 PDF | 官方链路依赖桌面文档转换运行时 |
| `read_image` 模型输入 | 当前本机 DeepSeek 文本路由没有图片输入能力 |
| 秒级自主调度 | Android 16 后台调度受系统配额和最小周期约束，无法承诺官方常驻进程语义 |
| 实验性 Agent Teams / Ralph | 官方标准预设默认关闭；其持久成员进程依赖常驻宿主 |

## 代码智能 / LSP

- `harness-interop` 已实现语言服务器进程启动、JSON-RPC framing、initialize、definition、references、hover、implementation、document/workspace symbols、rename 预览与 diagnostics，并已注册为本机 Harness 的按需模型工具。
- 普通用户不再配置启动命令。777 会按目标文件和项目特征自动选择当前运行环境中已存在的 Kotlin、Java、Python、TypeScript/JavaScript、Rust、Go、C/C++ 语言服务器，并支持工作区自带的 TypeScript language server；首次真正启动新的外部代码智能进程仍需人工审批。
- 当前 APK 不主动下载或安装第三方语言服务器。若项目与运行环境中没有可用服务器，Agent 会继续使用文件读取、grep/glob、编译和测试完成任务，不会把 LSP 当成硬依赖。旧版本已经保存的显式启动命令仅作为升级兼容的内部覆盖项继续生效。

## 安全边界

本机模式只访问 `filesDir/local-harness/workspace`，递归发现会拒绝跟随指向工作区外的符号链接。
会话快照与有界事件日志保存在应用私有目录，系统备份和设备迁移提取已关闭。网页抓取拒绝环回、私网、链路本地、组播、保留地址、
用户信息网址和非常用端口，并在每次重定向后重新解析和固定地址。Shell 继承 Android 应用身份，无法越过
系统沙箱；设备是否带有某个命令由厂商系统决定。


## 0.1.7-alpha.2 基线补强

- 本机会话事件日志改为分段追加：单段仍受大小限制，但历史段永久保留，不再为新事件裁掉旧事实。
- 启动/切换会话时会修复中断的开放轮次：已记录开始但无结果的工具标记为 `TOOL_OUTCOME_UNKNOWN`；尚未记录开始的调用标记为 `TOOL_NOT_STARTED`。
- 模型请求的轻量路由与上下文规模使用 `request/context` 落盘；仅失败尝试额外使用 `request/context-full` 保留一次完整消息与工具现场，失败或取消的模型尝试使用 `assistant/attempt` 结算。
- 会话 JSON 中的 `modelHistory` 已降级为旧版本迁移字段；新快照不再复制模型上下文。运行时 `modelHistory` 只是内存投影，启动时由 Session 事件中的有效检查点与语义尾部重建；旧快照仅在首次迁移时作为兼容输入。


## 0.1.7-rc.2 基线补强

- 上下文治理改为双层预算：原有字符上限继续负责 Android 内存与序列化压力；官方 DeepSeek 路由同时使用模型上下文预算。当前官方 `deepseek-flash` / `deepseek-v4-pro` 按 1,000,000 token 窗口、256,000 token 输出预留、65,536 token 额外余量与 0.8 压力阈值计算；未知第三方模型不猜测窗口，只保留字符保护。
- 主 Agent 在每个模型 step 前重新检查上下文压力，并把当前临时上下文与实际工具 schema 纳入估算；长工具链不会只在整轮开始时检查一次。若提供方仍明确返回上下文窗口超限，当前请求会保留全部 system 指令、强制压缩较早非 system 历史并仅重试一次；普通 400/422 错误不会进入该恢复路径。
- 压缩检查点以带 `<compacted-summary>` 标记的 user 消息进入模型历史，不追加第二条 system 摘要，避免 DeepSeek“最新 system”语义覆盖真正系统提示。
- 工具结果统一通过 UTF-8 / Unicode 安全保留器进入模型上下文，避免在代理对或 UTF-8 字符中间截断。超限原文在 16 MiB 单项上限内写入 `noBackupFilesDir` 下的 Session 私有 spill，模型可用只读 `tool_output_read` 按原工具调用编号和 UTF-8 字节偏移分页回读；单 Session 与全局 spill 均有容量上限，删除 Session 同时删除对应 spill。
- 动态扩展工具继续按需发现。前台普通子 Agent、持久子 Agent 与自动化子 Agent 的可选工具启用集合改为单次 Agent Run 独立作用域，子 Agent 发现能力不会污染父 Agent 或兄弟分支。
- 固定提示词与工具描述删除运行时已经硬性执行的重复审批说明，保留能力导航、安全边界和必要前置条件，减少每轮固定 token。
- 自动化任务新增有界轻量运行回执：最多保留 200 条、30 天；完整工作结果仍只保存在既有 `workSessionId` 对应会话中。聊天模式界面继续采用 60 分钟周期下限，工作模式底层允许 Android WorkManager 的 15 分钟周期；不复制桌面常驻进程的一分钟调度语义。
- 官方本次仍使用 Session V4，因此无需新增 Session 格式迁移。
