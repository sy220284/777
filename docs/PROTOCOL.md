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

官方 OpenAI 兼容接口返回的 `content` 中 `<think>` 段属于原始协议事实；流式、非流式及 SSE fallback 都只向界面输出清理后的正文。原始 `content` 保存在既有 route-bound replay envelope 中，同一路由续轮还原原字段；不把标签内文本搬成服务商的 `reasoning_content`。服务商独立返回的 `reasoning_content`、`reasoning_details` 与工具元数据保持原字段含义，账号或路由变化后不转发私有重放内容。持久历史还原后遵循相同隔离规则。流式响应即使在 `<think>` 段内提前中断，未闭合的思考片段也不显示在助手正文中；续轮重放仍保留原始提供方字段。

依据：[MiniMax 官方 OpenAI SDK，多轮函数调用原文保留说明](https://platform.minimax.io/docs/api-reference/text-openai-api)。

### API 凭据身份与历史恢复

API 模型档案可选保存非秘密的 `credentialBinding`，由配置 Owner 在同一凭据事务中写入。旧档案缺少该字段时保持原路由指纹；只调整配置或保存同一密钥不会改变已有身份，真正更换密钥后旧私有重放与中断恢复不得沿用。瞬时限流及缓存同时按实际账户身份隔离。

付费媒体操作在内存中一次解析凭据，回执签名与实际请求使用同一绑定。恢复旧媒体入口仅在原凭据签名可证明一致时接受旧格式路由，不放宽全局会话恢复身份，也不自动重放结果未知的付费请求。

## MCP 工具参数 Schema

所有工具参数（原生工具与 MCP `inputSchema`）均使用同一标准 JSON Schema 引擎，参数根为 object。未声明 `$schema` 时按 Draft 2020-12；显式 Draft 4/6/7/2019-09/2020-12 由标准引擎解释。nullable 类型、组合条件、`$defs`/`definitions`、局部引用/anchor、布尔子 Schema、字符串/数值/容器约束不会在接入时被删改。工具注册时只编译一次参数定义，编译后的校验器属于该次不可变工具注册，普通调用、`tool_call` 和 Code Mode 的最终执行共用它。权限分级与审批策略由工具注册信息独立确定，不通过额外的 Schema 校验分支实现。

引用只解析该工具定义内已声明的资源，不会为 `$ref` 自动访问网络。缺失引用、未知方言或无效定义使该工具保持未注册，原因通过连接结果、服务发现与 UI 详情公开，其他有效工具仍可使用。远端 Schema 或 annotations 不授予执行权限，MCP 工具仍逐次按高权限审批。

Code Mode 的结果引用在前序步骤完成后解析。预检以同一标准引擎检查已知且独立的结构和字段；依赖未知值的 `oneOf`/`not`/条件分支等留到值确定后判断，不能把占位对象当真实参数或擅自选择分支。进入实际执行器之前，始终对已解析参数执行完整原始 Schema 校验；校验失败不会发出远端调用。
