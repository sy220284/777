# 原回复内交互卡片与渐进成果

2026-10-16，PR #705。本页说明这一条纵向功能链，整套 R4 仍按 R4-CODE-TASKS.json 验收。

## 正常使用链

1. 单聊、群聊、Work 的既有模型请求收到可选原生卡片协议。适合表单、对比表或图表时，模型可在可读正文后附加 `ui_document`；日常聊天和角色扮演保持普通回复。不会为自动制卡追加模型调用，既有手动“AI 设计”仍明确确认额外请求。
2. Feature 在质量修复和历史提交之前分离定义。拒绝的修复候选不能把卡片附到另一个正文；群聊按发言人分离。Shared Model 只提供每次 Provider attempt 的预览变换，不解释 UI 或业务语义。跨包 marker 拆分也不会显示 JSON。
3. 封闭 Codec 校验完整定义。source、documentId 和 revision 由实际提交 Owner/Session 指定；模型不能声明路径、执行工具、审批或完成事实。无效定义回退到已提供的正文，不新增修复请求。
4. 卡片可填写字段、选择选项、切换开关，表格可在本地筛选。`send_user_input` 只填入草稿；`submit_user_input` 在原生窗口确认冻结的完整文本后，作为普通用户输入交给原 Chat/Work Owner。相同的身份、revision、字段校验仍在 Owner 入口执行；保留真实队列、拒绝、失败和结果未知回执。
5. Work 在开始、阶段输出、工具开始/返回与终态后，通过原 Session 日志 CAS 更新同一卡。实际回执预览及模型阶段结论明确标明未核验，不能把工具返回当成任务通过。
6. 自动续跑用持久化 continuation→run→source 事实恢复原来源；进程恢复用 recovery run ID 找回原 turn/start。分页读取不推断“当前最后一条消息”。重开从原会话 EventLog 恢复，原 tombstone 阻止迟到发布复活已删除卡。

## 协议增量

`UiDocumentAction.SendUserInput.submit` 默认 false。编码 false 为原 `send_user_input`，true 为新 `submit_user_input`，参数仍是 `id,text,fieldIds`。`UiDocumentActionRequest.confirmed` 默认 false；仅原生确认后的请求置 true，未确认的 submit 意图拒绝。

自动回复只允许普通用户输入意图；审批与导航仍由原本已准入的显式生成/本地 Owner 路径提供。媒体引用、支付、权限与工具审批沿用原 Owner，不通过卡片获得新授权。

## 验证边界

新增回归覆盖实际日志重开、同卡 revision、删除后迟到拒绝、不同会话隔离、非法定义、修复候选与群聊发言归属、流式 marker 全拆分、超过单页的续跑来源以及 V6 确认发送后排队反馈。当前环境无仓库要求的 Android/JDK/Gradle 工具链，编译/JVM/Android 16+17 结果须使用最新提交对应 CI；源码和静态检查不替代设备验收。

旧版缺少 run_id/source 的事件不猜测关联。复杂实时数据重算、任意工具 Schema 自动转真实业务动作、远程媒体授权下载及完整跨包恢复验收不属于本次已交付证明。
