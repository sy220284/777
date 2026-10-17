# R4 / S4：只读结构化事实表格增量（2026-10-14）

> **历史快照，停止执行（2026-10-16）**：本文保留当时提交与审计证据，不是当前 R4 任务或现有代码清单。所述 Next 包/切片已经从 #705 删除，原双 UI 切换/双包验收已取消；不得据此恢复 Next 或重新创建待办。**现行执行唯一依据**：[R4 34 项任务清单](R4-CODE-TASKS.json)及[当前 R4 方案](PLAN-R4.zh-CN.md)。


> **提交范围与归属：** 本文只记录本轮由 ChatGPT 提交的 S4 表格批次，不将之前已经实现的 UiDocument、Local Builder、V6 / Next 渲染器、Session sidecar、审批防重放重复计算为本轮工作。

## 本轮新增（本 PR 提交，非主线功能）

- `UiDocumentNode.Table`：只读表格，受限列名/行宽、最多 8 列、重复/空列名拒绝；表格单元格计入 `UiDocumentBudget.maxNodes`，文案计入文本预算；无按钮、支付、审批、工具能力。
- `LocalUiDocumentCodec`：闭合 Schema 增加 `table`，嵌套 string-array 类型校验；Encode/Decode 与既有来源、revisions、预算和 Shared Session 保存使用同一管线，拒绝未知扩展属性。
- 双 UI Pack 分别用自己 Compose 原生控件呈现表格，窄屏以逐行字段/值方式展示，避免水平溢出，不强制 V6 材质进入 Next。
- `UiLocalDocumentData.TableData`：接受调用者给出的明确类型化标题、表头和行内容；新 `FACTS_TABLE` 入口读取已经存在的 Chat/Work Owner 的会话状态、阶段及排队输入数，在 V6/Next 两包制作**生成时快照**。无第二次模型请求、无写业务权限、无自动执行。
- 新增协议、Builder、Codec 测试源码：合法表格、单元格累计预算、列形状错误、未知属性、Round-trip、无授权动作。

## 既有基础（非本轮新增）

`:ui-contract`、`UiDocument`、`LocalUiDocumentBuilder` 原有三种模板、`UiDocumentPatch.matches`、Shared Session sidecar、V6 / Next 交互卡、AI 可选调用与用量 Owner。此批只是给现有结构加新**只读展示能力**。

## 不应误判的完成度

- 本次 `FACTS_TABLE` 仅表示**制作时的事实快照**；实时进度、成果、证据及媒体仍需要从原 Owner 的持续事件/文件投影绑定，不能把静态表格当实时任务完成。
- 本次未实现**任意工具 Schema 自动推断 UI**、模型 JSON 自动提取可信事实、通用本地布局推理、交互图表与完整媒体播放器。
- `schemaVersion=1` 保留闭合节点集兼容性策略：旧包遇到不支持的 `table` 时应使用已有失败/文本回退，不可回退为可执行动作。正式协议升级/版本迁移与设备历史恢复测试尚未完成。
- `S2` 双包真实模型旅程、`S3` V6 整体移包与 Next 全应用替换、`S5` 无障碍/主题/Android16/17 设备验收均未完成。
- 本文件的“已提交”仅指 Git 对象和 PR 分支；没有绑定最终 SHA 的成功 CI 或设备回执前，不宣称构建或验收通过。

## 设计边界

共享层定义受限、只读的表格数据；不规定材质、颜色、字号、圆角。V6 继续使用 V6 当前基础组件；Next 保持独立 Material 渲染。不得通过一个表格伪造已核验、已收费、任务成功或可重试等业务判断。


## 本轮第二个提交：Work EventLog 的工具活动只读表格

**本轮新增** `WORK_TOOL_ACTIVITY` 入口仅在 Work 模式提供。适配器仍由激活身份锁准入，通过已经存在的 `WorkReadUiPort.toolActivitiesForUi(sessionId)` 读取原 Work Owner 的有界 EventLog 投影，最多选取最近 8 条工具活动，并使用同一 `UiLocalDocumentData.TableData → LocalUiDocumentBuilder → Codec → Session sidecar → V6/Next` 管线生成**零额外模型请求**的静态工具事件表格。显示工具名、`LocalToolUiPhase` 原始阶段、错误码；不进行工具重试、不授予审批、不把 `COMPLETED` 解释为“需求已核验”。Chat 模式调用这一模板明确拒绝。

**此前已有、非本轮贡献**：`WorkReadUiPort`、工具活动 EventLog 投影、阶段枚举和原 Work Owner、双包现有生成按钮、身份与归档逻辑。

**仍待补齐**：基于工具 Schema 的自适应字段/交互控件、实时增量投影而非静态快照、证据/媒体节点、跨进程完整恢复、两包实际设备验证。此增量在尚未获成功 CI 证据前仅标记源码已提交。
