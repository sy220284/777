# R4 S1 / S2：群聊成员 Port 真正接入 Next（2026-10-14）

## 既有基础（非本次新增）

现有 `LocalChatRuntime.configureGroupChatMembers` 和 `LocalGroupChatMembershipCoordinator` 仍是唯一业务执行者，现有 V6 群聊成员选择器和图库主档照旧，Chat/Work 基础 Port 与 Next 会话独立包也已存在。本次**不重写成员持久化/决策/记忆，也不新增第二业务 Owner**。

## 本次新增

- `:ui-contract` 增加闭合 `ChatGroupUiPort`，仅含只读成员/可选图库 ID 与 `ConfigureMembers(identity, galleryIds)` 强类型意图；禁止把图库实体、Store、Chat Runtime 或执行服务暴露给 UI Pack。
- App `LocalChatGroupUiAdapter` 复用原 Chat Owner；共享前台锁验证运行模式、会话激活身份、模式切换及成员数；可选图库 ID 来自现有图库源，实际写入仍委托原成员协调器。Port 的 Accepted 仅代表 Owner 接纳，不代表成员对话完成/核验。
- Next 的 `chat.group_members` Scene 新增实际成员选择/保存面板，来源从 Owner 查询；准入需对应 Port 实际连接，不通过 Manifest 自报权限。
- 新增 Port 准入 JVM 测试及 Next Compose 交互夹具测试。S0 矩阵增加第 15 个已定位到源码 Command 的动作，但完整 E2E 验收状态仍为待验证。

## 尚未完成

当前 Next 的成员选择 UI 仍是会话切片的一部分，不代表世界书、角色编辑、图库/日记或全应用导航已移包。S2 尚缺真实模型、审批/重启旅程的两个完整包共同签收；S3 全局 Host、V6 全页面迁出和整包禁用试验也未完成。

如果最新 Android/CI 结果未通过，按实际失败记录修复，不能把“测试已编写”标成“运行通过”。此外，群聊公告、角色卡复杂编辑等操作仍待按独立 Port 逐项迁出。
