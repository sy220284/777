# 本机 App Server：统一入口与功能接口目录

依据：Codex App Server 的 Thread / Turn / Item、请求/响应/通知结构与 777 架构 3.0。当前 777 是 Android 本机模块化单体，默认通过应用内 Kotlin 端口调用，保留全部本机数据及既有安全审批；不额外创建本地网络监听或第二套进程。

## 对应关系

- Thread → 现有 Session（持久事实由 Session Owner / EventLog 管理）
- Turn → Chat Turn 或 Work Run（运行中断与恢复由原 Owner 管理）
- Item → 消息、工具调用、审批请求、任务进度和完成回执（仍由原 EventLog 与投影管理）
- App Server → `AppServerUiPort`，由唯一 `LocalAppServer` 装配六大 Feature 的现有公开 Port。
- Notifications → `AppServerUiPort.notices`，消费 Feature 原有 Flow；事件只提示刷新原 Owner 快照，不创造新的全局序号。
- Commands → `UiAppServerCommand` / `UiAppServerReply`，逐一对应 Chat、Work、Session、Agent、Automation、Tools、Settings 原有操作回执。
- Tool discovery → `LocalCapabilityDiscovery` 统一由实时 ToolRegistry 导出；模型 `capability_search` 与 UI 目录共用匹配、精确选择；执行审批、连接和模型工具定义预算仍由原所有者校验。

## 使用方式

客户端注入 `AppServerUiPort`，读取 `manifest()` 以发现功能清单，读取 `session/chat/work/agents/automation/tools/settings/workRead` 现有强类型接口，监听 `notices` 并以当前会话身份读取快照；发送操作使用 `submit(UiAppServerCommand)`。UI 的普通读取仍可直接透过这个入口获得各 Feature 的窄投影。UI 层仅管理呈现状态，不接管任务、工具、Checkpoint 或调度。

独立智能体、团队、Fork 继续使用同一个 Agent Runtime 和 Job Owner。自动化任务仅在用户明确创建后由原 Scheduler 执行。工具页搜索与模型能力搜索均只查询当前已注册能力，搜索不会静默授予权限。

## 全功能契约检查

`tools/audit/check-app-server-contract.py` 对全部 `uicontract` 接口逐一生成消费路径索引，并检查 Feature Catalog、统一请求/响应、Hilt 唯一装配、工具发现及 Work/Tools/Automation 实际 UI 接入；`.github/workflows/architecture3-callgraph-audit.yml` 在每次相关 PR 变更运行并保存 `build/app-server-audit/interface-inventory.json`。完整编译和设备互动仍以相同提交的 Android 能力与 UI CI 验收。

### 审核边界

接口库存的静态引用只定位审计候选，是否冗余还须以装配、实际运行和迁移历史核验。此入口集中的是客户端访问方式；各 Feature 继续拥有单一写入权和独立的恢复事实。

## 统一状态读取

`read(UiAppServerRead)` 将 Chat、Work、Session、Agent、Automation、Settings、Capabilities 的已有快照组成一套查询协议。每次只访问对应功能的状态 Owner；`Capabilities` 复用实时 `ToolRegistry` 的目录/检索结果，按 limit 限制数据量。客户端可搭配 `notices` 收到状态变化后针对性刷新，不建立新的全局影子状态或重复持久化。
