# Protocol

当前 APK 只运行本机 Android Harness。模型请求、Session、Agent 事件与工具执行都使用本机运行链；产品不提供远程 Harness Web 控制、配对、RPC 或中继事件流。

## 本机语义参考

```text
Harness semantic reference: 0.2.1-alpha.1
Commit: 5badb15009ae1756c3afe0ae0cef1faafc290ccc
Session format reference: V4
Source: upstream/deepseek-harness.lock.json
```

锁定的官方版本用于本机语义差分验证。

## 本机 Session 与 Agent 协议

本机模式没有远程 RPC 层。

核心关系：

```text
user input
→ AgentLoop turn
→ model request
→ assistant / tool calls
→ tool results
→ next step
→ turn settlement
→ Session Event Log
```

本机事件日志是主要事实源；运行时快照、UI 投影和模型历史都是有界派生状态。

前台、子代理和 Automation 使用统一 run checkpoint：

```text
sessionId
→ runId
→ parentRunId
→ agentId
→ tool call
```

中断恢复不会重放结果未知的副作用：

- 已开始但没有可靠结果：`TOOL_OUTCOME_UNKNOWN`
- 尚未开始：`TOOL_NOT_STARTED`

## Token usage

本地 Token 总量以成功模型响应中提供方实际返回的 input / output usage 为准；失败、取消、断流或未返回 usage 的请求不由客户端估算。

```text
API usage = 实际总量
prompt breakdown = 输入构成诊断
```

系统提示词、人物状态、记忆、历史和工具定义不会再次加到总量。

Web、Vision、Subagent、Automation 的模型请求继承父 run 归属。

## 兼容策略

777 不根据猜测的 Harness 版本分支行为。

当前策略：

- 已验证的字段和 endpoint 使用固定当前契约。
- 未知 JSON 字段忽略。
- 未知事件保留可诊断信息。
- 缺失的可选能力按 capability unavailable 处理。
- 协议基线升级必须更新代码、fixture 和一致性测试。

本机兼容范围见 [COMPATIBILITY.md](COMPATIBILITY.md)。

### MiniMax 标签式思考的展示与重放

官方 OpenAI 兼容接口返回的 `content` 中 `<think>` 段属于原始协议事实；流式、非流式及 SSE fallback 都只向界面输出清理后的正文。原始 `content` 保存在既有 route-bound replay envelope 中，同一路由续轮还原原字段；不把标签内文本搬成服务商的 `reasoning_content`。服务商独立返回的 `reasoning_content`、`reasoning_details` 与工具元数据保持原字段含义，账号或路由变化后不转发私有重放内容。持久历史还原后遵循相同隔离规则。

依据：[MiniMax 官方 OpenAI SDK，多轮函数调用原文保留说明](https://platform.minimax.io/docs/api-reference/text-openai-api)。
