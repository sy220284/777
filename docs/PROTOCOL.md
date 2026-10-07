# Protocol

本文只描述当前版本实际使用的协议边界。

777 同时存在两条独立链路：

1. **远程模式**：手机通过 HTTPS 中继连接电脑上的 DeepSeek Harness，使用 Harness Web Client Protocol。
2. **本机模式**：Android 原生 Harness 直接在 APK 内运行，不通过远程 Web 协议；它以锁定的官方 Harness 语义基线做差分验证。

两条链路不要混为一套版本。

## 当前基线

### 远程协议

```text
Harness baseline: 0.1.6-alpha.1
Commit: 0d1f50007f9bca3f52b06e1c3074fa14d5fb0720
Code: core.DshCore.PROTOCOL_BASELINE / PROTOCOL_COMMIT
```

### 本机语义参考

```text
Harness semantic reference: 0.2.1-alpha.1
Commit: 5badb15009ae1756c3afe0ae0cef1faafc290ccc
Session format reference: V4
Source: upstream/deepseek-harness.lock.json
```

本机模式不运行官方 Node Harness；参考版本只用于语义、事件和一致性验证。

## 远程传输

远程控制只支持配对后的 HTTPS 中继。

```text
Android
→ HTTPS dsh-relay
→ Harness /api
```

应用不再提供局域网扫描或明文直连 Harness。

中继配对凭据由 Android Keystore 加密保存；证书固定发生变化时需要重新建立信任。

## RPC

普通远程调用使用：

```http
POST /api/<namespace>/<method>
```

请求 envelope：

```json
{
  "type": "client-request",
  "rpcId": "<uuid>",
  "method": "session/list",
  "payload": {
    "args": {}
  }
}
```

成功响应：

```json
{
  "type": "server-response",
  "rpcId": "<same uuid>",
  "result": {
    "ok": true,
    "value": {}
  }
}
```

业务失败：

```json
{
  "type": "server-response",
  "rpcId": "<same uuid>",
  "result": {
    "ok": false,
    "error": {
      "code": "session/agent-busy",
      "message": "...",
      "details": {}
    }
  }
}
```

HTTP 状态只描述传输 / 认证边界；业务错误读取 `result.error`。

当前客户端对未知字段宽松解析，对未知事件 / 内容类型保留 passthrough，而不是因为上游新增字段直接崩溃。

## Remote mux

长连接使用：

```text
/api/remote.mux
```

一个 WebSocket 承载多个可独立打开和取消的逻辑流。

客户端：

```json
{"type":"open","streamId":"1","endpoint":"session/follow","payload":{"args":{}}}
{"type":"cancel","streamId":"1"}
```

服务端：

```json
{"type":"item","streamId":"1","value":{}}
{"type":"error","streamId":"1","error":{}}
{"type":"end","streamId":"1"}
```

当前主要流：

- `$events`：ready、审批、提问和宿主通知。
- `session/follow`：当前会话快照、持久事件和实时 assistant stream。
- `session/control`：队列、任务和会话投影。
- `workspace/follow`：工作区投影。

连接 ready 后的 `clientId` 绑定当前连接代际；审批和提问的回答必须使用当前代际。

## Session

远程会话由 snapshot + durable event + transient assistant stream 组成。

```text
session/follow
→ opening snapshot
→ durable events
→ optional assistant-stream frames
```

实时 assistant stream 只用于显示当前生成过程，不进入持久历史游标。

模型尝试最终由持久 settlement 结算；settlement 到达后替换临时流式显示。

历史分页通过 `session/page` 获取，并使用当前 follow snapshot 的游标保证分页和实时尾部属于同一日志切面。

## 审批与提问

审批 / Ask User 通过 `$events` waterfall 到达。

回答走：

```text
POST /api/$events/result
```

结果必须绑定：

- 当前 `clientId`
- 对应 `eventId`

连接重建后，旧代际回答不能拿来结算新代际请求。

## 文件

文件先上传，再由消息引用 receipt。

首选二进制路由：

```http
POST /api/session/uploadFileBinary
```

不提供该路由的宿主可退回 `fileUploads/upload` Remote。

上传 receipt 受会话作用域约束，不能跨会话复用。

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

远程版本支持范围见 [COMPATIBILITY.md](COMPATIBILITY.md)。
