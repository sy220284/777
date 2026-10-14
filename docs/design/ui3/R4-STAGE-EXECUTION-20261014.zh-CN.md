# UI 3.0-R4 分阶段推进记录（2026-10-14）

> 对照 `docs/design/UI-3.0.zh-CN.md` 和 `docs/design/ui3/PLAN-R4.zh-CN.md`。当前动作映射数量以 [场景矩阵](../../UI-SCENE-MATRIX.json) 为唯一事实源；本文历史批次不代表完整交付。

本报告**严格区分本轮源码、之前的基础、代码未验收和设备未知**。此处出现“已提交”不代表 CI/真机通过，不代表 R4 已完成，也不代表未来自动继续运行。

## 已存在基础（非本轮贡献）

- S-1：MCP 未检测不冒充健康；Chat/Work 队列事实投影；实际审批 waiter 身份。
- S0：77 个现行 Scene、路由/设置台账，22 项旧问题阶段去向。
- S1：Chat/Work 命令、Shared Session、Files/Media/Tools/Automation/Usage 等中立 Port。
- S2：Next 独立 Material 会话切片、已有 Compose 行为夹具与共享 Port。
- S3：V6 16 个基础组件文件拆包、Host 初版准入注册。
- S4：受限动态 UiDocument、本地/可选 AI 生成、V6/Next 原生表单、表格与 Work 工具事件快照、Session sidecar。
- S5：部分静态、JVM、Android 门禁及早期 CI 成功记录；不覆盖当前分支。

## 本轮新增代码和测试（逐提交归属）

| 阶段 | 提交 | 本轮做的 | 剩余缺口 |
|---|---|---|---|
| **S0** | `78869c67` | 77 Scene/163 Action 的独立 `actionProof` 结构（均从未自动宣称通过）；新增 `.github/scripts/check-ui-r4-scene-matrix.py`，接入静态 CI，核对场景 ID、Owner（静态文字值）、Kind、动作完整性及禁止无回执冒充 VERIFIED | 隐蔽入口的全部人工核查及 163 个动作的真实结果/恢复签收 |
| **S0** | `1df5b4c5` | 从已存在的 `ChatUiPort` / `WorkConversationUiPort` 源码填入 **14 个**动作的真实命令、统一回执类型；`status` 保持待验证 | 其余 **149** 个动作尚未填 Owner/回执映射，全部 163 个仍缺完整 E2E |
| **S1** | `7d15f4f4` | `ChatSceneSnapshot.group` 只读类型化成员列表，由原 Chat Owner `LocalGroupChatState` 投影；Next 显示真实群聊成员，切为 Work 立即不暴露 Chat 信息；补 JVM 测试 | 群聊成员编辑、人物/图库/世界书等完整独立命令 Port 仍缺 |
| **S2/S4** | `dc0a00ea` | `UiDocumentFormKey` 绑定 `document.revision`，V6/Next 共用；Host 旧 4 字段草稿能迁移至带版本的 5 字段格式，旧 revision 草稿不串入新卡；新增源代码回归测试 | Android 设备恢复、敏感草稿持久化策略和容量治理仍待验证 |
| **S2** | `92d02903` | Next Android 渲染夹具测试：群聊成员信息随 Port Snapshot 更新后不留旧 UI | 夹具不是正式模型/群聊真实设备完整旅程 |
| **S2** | `8d400f9a` | 新 Android 测试经过**实际 App 组合层** `LocalConversationUiPortComposition`（身份准入）、Next Compose、原 Chat/Work Execution Port 的边界，验证模式切换后发送进入对应 Owner；执行端替身不会启动真实模型 | 尚未同一真实模型/真实审批在两个 UI Pack 跑通；设备/CI 回执缺失 |

## 尚未完成，按照阶段前置推进

- **S-1**：`McpServerSnapshot` 只有现存连接记录/工具名；缺 live heartbeat、Selected / Authenticated 独立事实及插件部分故障隔离回归；不可从注册/工具清单推断实时健康。真实 TalkBack/壁纸对比度、IME 需设备复现。
- **S0**：尚缺所有 163 个 Action 的完整 `Owner → Capability → Receipt → Restore → test` 联合证据。目前 14 项已知静态接口关联，但**已完成 E2E 计数仍为 0**。
- **S1**：人物/图库/群聊管理、Work 团队/证据写入、剩余附件预览及生产旧回调迁移；本轮只补了群聊只读状态，不能据此称 S1 完成。
- **S2**：已接 App 真实身份校验与 Execution Port 测试，执行端仍是测试双替身，缺真实 Codex/模型发送、审批、重启恢复 **V6 与 Next 同一事实**的黑盒验收。
- **S3**：V6 页面迁出、Next 完整产品路由、全局 Host Scene 注册与导航、可切换 Pack、全包反向替换；在 S2 黑盒完成之前仍不能标“已可插拔”。
- **S4**：本地 Schema/结构化响应自动制 UI、媒体/证据/渐进进度、Work 运行内发布、版本迁移/有界真实历史恢复；本轮只补同文档 revision 的表单隔离。
- **S5**：当前头提交 CI、38 原生场景/57 CI 截图、Android16/17、TalkBack/200% 字号、弱网/未知费用/真实支付任务恢复都没有绑定本批新代码的通过证据。

## 代码合入及验证边界

本批为 GitHub API 直接向 `feat/ui-r4-pluggable` 提交源码与测试。因当前工具不提供本地完整 Android 构建环境或对**最新提交**触发工作流的写入口，本轮没有取得最新 SHA 的 Gradle、instrumentation 或真实设备结果。此前 `2d243b92` 的失败工作流不能代表本批结论，早期成功运行也不能代表当前状态。

PR #705 尚为 Draft，当前分支相对于 main 有未同步提交，GitHub 报告无法直接合并；不得通过未经合并验证的两父提交、强制推送或忽略 CI 规避冲突。

**阶段门槛约束：** 先把 S-1/S0 的真实缺口逐项闭环，完成 S1 能支撑 S2 的接口；S2 要用真实双包业务操作签收，再推进 S3 全场景迁移；S4/S5 的自动化/设备证据都必须绑定完成后的 head。此台账保留每个阶段未完成部分，不能用“源码已提交”打勾。

## 第二批 S1 / S2 可执行群聊接口（新增于 `36a07cfd`）

- 从已有 Chat Owner 读取群聊成员和图库合法 ID，经中立 `ChatGroupUiPort` 将选择动作回交 Owner；Next 独立绘制成员管理，并由 Host 校验 Port 的真实连接身份。没有另建成员写入者。
- S0 现已**33/172** 个动作写入源码 Command 映射，**139/172** 个仍需补 Owner/Receipt 路径；全部动作的正式 E2E 证据仍为待办。
- 新增 Compose UI 测试与 Host 能力拒绝测试**源码**，最新编译/设备结果尚需独立回执。
- 此批是 Group Members 的完整纵向切片，不代表群聊公告、角色世界书、图库和全 App UI 包切换都已完成。参见 [独立专项](R4-GROUP-PORT-20261014.zh-CN.md)。
