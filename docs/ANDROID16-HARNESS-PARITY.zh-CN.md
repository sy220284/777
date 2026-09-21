# Android 16 本机 Harness 适配审计

审计基线：`deepseek-ai/deepseek-harness@ddefc45fbc7f8e46dd73185e68295696d1297887`
（官方 `master`，`0.1.6-alpha.2`）。本文件只评价 APK 内的本机模式；电脑远程模式继续使用官方
网页协议，范围见 `COMPATIBILITY.md`。

## 结论

以官方 `standard` preset 默认启用的能力为准，APK 已覆盖其中可在普通 Android 应用沙箱内保持语义的
主链路：模型轮次、推理与工具调用、追加式会话
事实、多会话持久化、权限审批、文件系统、文本发现、系统命令、后台任务、网页搜索与安全抓取、技能、
规划模式、目标、任务清单、用户问答、子代理、会话分叉、并行工作流、会话查询、结果交付标记、重试、
工具结果裁剪和上下文压缩。

实现遵循官方的关键不变量：模型看到的用户消息、请求头、助手消息、工具调用和工具结果都会先后进入
`session.events.jsonl`；工具结果返回模型前有大小上限；模型请求按可重试错误执行有界退避；写入、编辑
和命令执行经过用户审批；文件与命令共享同一个应用私有工作区。

## 对照表

| 官方能力族 | Android 16 本机实现 | 状态 |
| --- | --- | --- |
| Agent loop / DeepSeek 适配器 | 多步模型—工具循环，保留 `reasoning_content` 和原始工具消息 | 已适配 |
| Session / persistence / projection | 多会话快照、追加式事件日志、会话列表与切换 | 已适配 |
| LLM retry / cancellation | 408、429、5xx 与网络错误有界重试；轮次可停止 | 已适配 |
| Compaction / spill | 工具结果头尾裁剪、历史上限压缩、所有原始事件留在日志 | Android 等价实现 |
| `read` / `write` / `edit` | 保留官方工具名；有界读取、完整写入、唯一字面量替换、路径穿越防护 | 已适配 |
| `glob` / `grep` | 保留官方工具名；原生 glob 与递归文本搜索，不依赖设备预装 `rg` | 已适配 |
| `bash` | 保留官方工具名；以 `/system/bin/sh` 执行，支持超时、取消、输出上限、审批 | Android 等价实现 |
| `job_*` | 后台命令和后台子代理的列表、输出、停止 | 已适配 |
| `web_search` | 与官方相同的 DeepSeek Anthropic Messages 搜索格式 | 已适配 |
| `web_fetch` | 公网地址校验、域名解析固定、重定向复验、正文上限 | 已适配 |
| `skill` / instructions | `.dsh/skills/*/SKILL.md` 发现和读取 | 已适配 |
| plan mode / review | 只读规划约束、`exit_plan_mode` 审批、执行模式切换 | 已适配 |
| goal / todo | 会话目标和任务清单持久化、界面投影 | 已适配 |
| ask user | 工具暂停轮次，弹窗选项或自定义回答 | 已适配 |
| `subagent` / `subagent_fork` | 新上下文、继承会话、后台执行、列表、追加消息和中断 | Android 等价实现 |
| workflow | 最多四个独立只读子任务并行执行并汇总 | Android 等价实现 |
| session query | 五个官方查询面：跨会话搜索、事件搜索、会话轨迹、事件轨迹、完整事件读取 | Android 等价实现 |
| present | 校验工作区成果文件存在及大小后标记交付 | Android 等价实现 |
| approvals / sandbox policy | 应用私有目录硬边界，加逐次审批 | 已适配 |
| credentials | Android Keystore AES/GCM 加密，配置与日志不保存明文密钥 | Android 强化实现 |

## Android 16 专项

- `minSdk`、`targetSdk`、`compileSdk` 均为 36，低版本设备无法安装。
- 构建插件升级到支持接口级别 36 的 8.9.2。
- 全面屏布局统一使用安全绘制区域；不设置退出全面屏的兼容开关。
- Compose 返回处理接入 Android 16 预测返回接口；审批和提问优先关闭，避免轮次悬挂。
- 大屏、折叠屏和自由窗口不锁定方向、比例或尺寸；窄屏按钮拆行，状态放入可滚动区域。
- 局域网扫描和直连按需申请“附近的设备”权限；互联网中继和本机模式不触发该权限。
- 应用启用严格意图过滤匹配，外部深链必须满足已声明规则。
- 删除 Android 8～15 的通知、WebView 和语言兼容分支。
- APK 的 AndroidX Graphics 与 DataStore 依赖会打包 8 个多架构原生 `.so`；已验证每个 ELF
  `LOAD` 段的 `p_align` 为 `0x4000`，且 APK 内未压缩库的数据偏移均按 16 KB 对齐。
- 普通 CI 与发布构建都会校验 `minSdk=36` 及 16 KB APK/ELF 对齐，防止依赖升级后回退。

## 无法保持官方语义的能力

下列功能依赖桌面操作系统、Node 22、任意第三方进程或完整浏览器宿主。普通 Android 应用沙箱无法提供
同等执行环境，因此不放入本机工具目录；电脑远程模式仍可完整使用它们。

| 官方能力 | 原因 |
| --- | --- |
| Cordis 动态插件、组合包、热重载、`plugin_manager`、Cordis 自省 | APK 无法安装和执行任意 Node 包及其构建脚本 |
| `run_code` 程序化工具调用 | 官方运行时依赖 Node 沙箱和动态生成的工具绑定 |
| PowerShell / Windows 工具 | Android 没有 Windows 进程与 PowerShell 环境 |
| 持久 PTY、桌面终端控制 | Android 公共应用接口不提供等价的宿主终端和完整 Unix 用户空间 |
| LSP | 各语言服务器及语言运行时没有随 Android 系统提供 |
| Stagehand 浏览器自动化 | 依赖受控 Chromium、桌面页面上下文和额外模型服务 |
| Office 转 PDF | 官方链路依赖桌面文档转换运行时 |
| `read_image` 模型输入 | 当前本机 DeepSeek 文本路由没有图片输入能力 |
| 任意 stdio MCP 服务器 | APK 无法启动用户安装的 Node/Python MCP 进程；远程 MCP 仍需独立服务与凭据配置 |
| 秒级自主调度 | Android 16 后台调度受系统配额和最小周期约束，无法承诺官方常驻进程语义 |
| 实验性 Agent Teams / Ralph | 官方标准预设默认关闭；其持久成员进程依赖常驻宿主 |

## 安全边界

本机模式只访问 `filesDir/local-harness/workspace`。网页抓取拒绝环回、私网、链路本地、组播、保留地址、
用户信息网址和非常用端口，并在每次重定向后重新解析和固定地址。Shell 继承 Android 应用身份，无法越过
系统沙箱；设备是否带有某个命令由厂商系统决定。
