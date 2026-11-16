# 本机 App Server：统一入口与功能接口目录

依据：Codex App Server 的 Thread / Turn / Item、请求/响应/通知结构与 777 架构 3.0。当前 777 是 Android 本机模块化单体，默认通过应用内 Kotlin 端口调用，保留全部本机数据及既有安全审批；不额外创建本地网络监听或第二套进程。

## 对应关系

- Thread → 现有 Session（持久事实由 Session Owner / EventLog 管理）
- Turn → Chat Turn 或 Work Run（运行中断与恢复由原 Owner 管理）
- Item → 消息、工具调用、审批请求、任务进度和完成回执（仍由原 EventLog 与投影管理）
- App Server → `AppServerUiPort`，由唯一 `LocalAppServer` 装配六大 Feature 的现有公开 Port。
- Notifications → `AppServerUiPort.notices`，消费 Feature 原有 Flow；事件只提示刷新原 Owner 快照，不创造新的全局序号。
- Commands → `UiAppServerCommand` / `UiAppServerReply`，逐一对应 Chat、Work、Session、Agent、Automation、Tools、Settings 原有操作回执。
- Tool discovery → `LocalCapabilityDiscovery` 统一由实时 ToolRegistry 导出；模型 `tool_search` 与 UI 目录查询真实工具，`tool_call` 按名称直接执行；连接、权限、参数与审批均由真实目标工具的原执行器校验，无需逐轮预加载。

## 使用方式

客户端注入 `AppServerUiPort`，读取 `manifest()` 以发现功能清单，读取 `session/chat/work/agents/automation/tools/settings/workRead` 现有强类型接口，监听 `notices` 并以当前会话身份读取快照；发送操作使用 `submit(UiAppServerCommand)`。UI 的普通读取仍可直接透过这个入口获得各 Feature 的窄投影。UI 层仅管理呈现状态，不接管任务、工具、Checkpoint 或调度。

独立智能体、团队、Fork 继续使用同一个 Agent Runtime 和 Job Owner。自动化任务仅在用户明确创建后由原 Scheduler 执行。工具页搜索与模型能力搜索均只查询当前已注册能力，搜索不会静默授予权限。

## 全功能契约检查

`tools/audit/check-app-server-contract.py` 对全部 `uicontract` 接口逐一生成消费路径索引，并检查 Feature Catalog、统一请求/响应、Hilt 唯一装配、工具发现及 Work/Tools/Automation 实际 UI 接入；`.github/workflows/architecture3-callgraph-audit.yml` 在每次相关 PR 变更运行并保存 `build/app-server-audit/interface-inventory.json`。完整编译和设备互动仍以相同提交的 Android 能力与 UI CI 验收。

### 审核边界

接口库存的静态引用只定位审计候选，是否冗余还须以装配、实际运行和迁移历史核验。此入口集中的是客户端访问方式；各 Feature 继续拥有单一写入权和独立的恢复事实。

## 统一状态读取

`read(UiAppServerRead)` 将 Chat、Work、Session、Agent、Automation、Settings、Capabilities 的已有快照组成一套查询协议。每次只访问对应功能的状态 Owner；`Capabilities` 复用实时 `ToolRegistry` 的目录/检索结果，按 limit 限制数据量。客户端可搭配 `notices` 收到状态变化后针对性刷新，不建立新的全局影子状态或重复持久化。

## 文件与附件边界

新增 `AppServerUiPort.files` 与 `AppServerUiPort.attachments`。工作区目录、搜索、预览、下载发布由已存在的 `FilesUiPort / LocalSessionFilesRuntime` 执行；用户明确选择的手机附件经 `AttachmentUiPort` 的会话身份验证导入。旧的同类 UI 调用统一转向上述契约，端口移除运行中已具备能力的“默认不支持”实现。

## 排队消息的统一入口

`AppServerUiPort.queuedUsers` 暴露与 Chat/Work 共用的 `UiQueuedUserPort`。客户端可通过 `read(QueuedUsers)` 获取当前会话排队消息，或提交 `UiAppServerCommand.Queue(Edit/Remove/Reorder)`。调用复用同一 `LocalConversationUiPortComposition`，原始会话身份、运行中消息状态、队列修改和存储写入继续由 Chat/Work Owner 原子校验。

## 媒体、记忆、计费与用量

App Server 还通过惰性注入公开 `media / memory / pricing / usage` 四个原有功能接口。媒体与记忆的类型化指令通过 `UiAppServerCommand.Media / Memory` 传入原 Owner 并返回原操作回执；用量与官方价格通过 `read(Usage/Pricing)` 查询原始只读快照。Work/Chat 的媒体创作和用量显示已从具体运行类改为 App Server 接入。

## 未连接接口清理

全量 UI 声明及消费引用盘点确认，`ConversationReadingUiPort`、`ConversationShellUiPort`、`HostPreferencesUiPort`、`ModelPerformanceUiPort`、`ConversationReasoningUiPort` 只有类型定义，没有实际功能 Owner、UI 消费者和业务调用。对应四份声明文件已删除，避免未来出现表面上存在、实际上没有可执行能力的重复入口。CI 接口盘点增加 `UiPort` 实际消费者非空检查，防止同类空接口重新积累；此检查基于引用关系筛查候选，运行中的接口仍以编译和设备验证为准。

此外检查了权限通用 DTO：`UiSystemPermission.kt` 仅含未使用的权限枚举和回执、在当前生产代码、UI 测试及其他模块均没有引用；该文件随空契约一并清理。Android 权限检查、跳转与服务重绑定继续使用现有真实系统接口。

## 设置类状态通知的完整性

`noticesFor(SETTINGS)` 订阅原 Settings 状态、Usage 版本和官方 Pricing 更新。这样价格拉取或新的模型用量结算都能触发对应客户端重新读取，而不要求先发生模型设置更改；无需新增缓存或影子计费源。仅订阅工具、会话等其他功能时不会初始化用量或价格 Owner。
