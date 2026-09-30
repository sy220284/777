# Validation

本文描述当前主线的验证闭环。

目标不是“某个测试跑过”，而是确认：

```text
实现正确
+ 架构未退化
+ 性能未退化
+ Android 16 / 17 可运行
+ 最新 main 组合无回归
```

## 当前基线

```text
777: 版本以 `.github/release-version` 为准（本次基线 0.12.0-777.164）
Android: min 36 / target 36 / compile 37
Java: 17
Local Harness semantic reference: 0.1.7-rc.2 / 477b4f420...
Remote protocol baseline: 0.1.6-alpha.1 / 0d1f5000...
```

## CI

`.github/workflows/ci.yml` 的必需路径：

### preflight

1. 刷新并核验官方黄金 fixture 来源。
2. UI 硬编码中文门禁。
3. Design System 边界门禁。
4. Kotlin 风险模式门禁。
5. 本机性能不变量门禁。
6. 本机架构边界门禁。
7. 全模块单元测试。
8. 官方 Harness conformance。

单元测试覆盖：

```text
:core:test
:harness-core:test
:harness-runtime-android:test
:harness-interop:test
:harness-device-android:testDebugUnitTest
:mock-harness:test
:app:testDebugUnitTest
```

一致性：

```text
:reference-validation:test
```

### build

- `:app:lintDebug`
- `:app:assembleOptimized`
- Android APK 结构 / 16 KB 对齐验证
- optimized APK 体积预算

当前 CI APK 上限：

```text
94371840 bytes（90 MiB）
```

体积预算属于硬门禁；优化应降低实际体积，不通过提高预算解决失败。

### Android 16

x86_64 模拟器：

- connected Android tests
- debug APK 安装和启动
- optimized APK 安装和启动
- 运行时使用 `DSH_RUNTIME_ABIS=x86_64`

### Android 17

Android 37 / 16 KB page-size 模拟器：

- connected Android tests
- debug APK 安装和启动
- optimized APK 安装和启动
- 失败时输出 connected test XML

### merge-gate

`merge-gate` 只有在以下全部成功时通过：

```text
preflight
build
android-16-instrumented
android-17-instrumented
```

失败、取消或跳过任何必需 lane 都不能放行。

## 官方差分验证

`upstream/deepseek-harness.lock.json` 锁定本机语义参考。

`reference-validation` 的目标：

- 使用相同测试向量驱动本机核心。
- 对比官方黄金结果。
- 在官方基线更新时显式暴露行为差异。

日常 CI 不自动追随上游最新版，防止无意改变产品语义。

## 架构门禁

架构门禁至少保护：

- `LocalHarnessEngine` public surface。
- Engine 构造依赖。
- 已知热点文件行数。
- UI / Worker 直接依赖 Engine 的 allowlist。
- capability package 边界。
- 聚合状态规模。

门禁失败应下沉职责，不应提高预算。

## 性能门禁

性能门禁防止重新引入：

- transcript 全量物化。
- Session 历史热路径全扫描。
- streaming 字符串无界拼接。
- model history 无缓存重复计数。
- 已知高频路径的阻塞 / 重算模式。

## 功能专项验证

新增能力必须在通用 CI 外补自己的专项测试。

当前高风险领域：

- Session 恢复和未知副作用。
- 主 / 子代理 run 归属。
- Chat 连续性与人物记忆隔离。
- 发送排队 / 拒绝语义。
- Token 账本与去重统计。
- MCP / LSP 生命周期。
- VirtualDisplay 资源隔离。
- Runtime / APK 布局和实际执行。


### ChatGPT 套餐模型

专项验证至少覆盖：

- API Key 与 ChatGPT 套餐 profile ID 隔离。
- OAuth callback 编码、state/nonce/PKCE 与 ID Token 验证路径。
- access token 过期时 single-flight refresh，refresh token 轮换后原子替换。
- Responses 请求固定 `store=false`、`stream=true`；不发送套餐共享暂不支持的采样字段。
- ChatGPT 套餐共享的 function/custom tools 必须按 SIWC 预览契约放入 namespace；标准 API Key Responses 继续使用普通顶层 function tools，不相互污染协议。
- 协议选择以模型档案的认证类型与协议为边界：ChatGPT 套餐固定走受限 Responses 契约；API Key 的 Responses / Chat Completions 保持各自原有行为。
- OpenAI GPT-6 系列按模型能力发送采样字段：Astra 不发送 `temperature`；Sol/Luna 在 Chat Completions 需要采样或工具调用时显式使用 `reasoning_effort=none`；其他供应商不继承该限制。
- API Key Responses 必须使用该模型档案自己的 base URL 拼接 `/responses`；只有 ChatGPT 套餐共享强制使用 OpenAI 官方 Responses 地址。
- Vision 与“测试连接”必须复用同一协议路由，禁止 Responses-only 模型在旁路功能中退回 `/chat/completions`。
- Responses SSE 的顶层 `error`、`response.failed`、`response.incomplete`、refusal 与 completed 均需保留真实语义；标准 Responses 错误不得映射成 ChatGPT 套餐错误。
- DeepSeek 官方网页搜索只允许读取 DeepSeek 官方 API Key，不得把 OpenAI、Gemini、Qwen、ChatGPT OAuth 等当前模型凭据误发给 DeepSeek 搜索接口。
- system message 不进入 Responses input，转换为 `instructions`。
- 文本、图片、function tool、function_call_output 和 continuation item 转换。
- 切换到 Responses 时，旧 Chat Completions 的每个 tool_call 都转换为同 call_id 的 function_call；原始 Responses output 优先重放，不重复添加调用。
- Responses function schema 显式保留 strict 设置；未声明时使用 non-strict，不能把可选工具参数隐式变成必填。
- 子代理按 profileId 绑定独立 model/baseUrl/protocol/authKind/credentialRef；不得修改父代理活动档案。同名 API/套餐或多账户模型必须消除来源歧义。
- list_subagent_models 只展示有凭据的已保存档案，不硬编码 DeepSeek 列表。
- 自定义 API 协议选择要同时用于保存和连接测试，并在重新加载、替换 Key 后保留。
- 套餐模型目录结构异常必须报错，不得静默解释为空目录而覆盖已有档案；标准 API data[].id 不能被当作套餐共享授权依据。
- Vision 对所有供应商记录实际 API usage；价格未知时不得套用其他供应商价格。
- HTTP/2 `stream was reset:CANCEL`（冒号无空格）和有空格的形式使用同一断流分类；用户主动取消仍遵守 coroutine cancellation。
- 浏览器授权未完成、网页失败或用户返回应用时，可显式取消当前 loopback listener 并重新开始授权；取消后不得残留“授权进行中”互斥状态。
- 重新授权已有记录必须复用已保存 issued client_id；“添加账户”仍允许创建新的注册记录，同邮箱/同 subject 的不同 issued client_id 不得静默合并。
- 终止性 refresh token 错误只清空失效 token，保留 issued client_id + identity 映射用于重新授权；临时网络/服务端错误不得清凭据。
- ChatGPT“测试连接”必须走该授权记录自己的套餐 profile，并以真实 Responses 完成作为成功条件；不得只用 /models 可达代替推理连通。
- 多条授权记录可由用户显式移除；移除一条记录不得误删其他 client_id 的模型档案或凭据。
- refresh token 被确认失效并清空后，设置页必须读取最新持久化状态并回到“需重新授权”，不得继续用旧快照显示已连接。
- ChatGPT 套餐连通测试属于账户/模型边界职责，放在独立 tester 中；不得继续扩大 `LocalHarnessEngine` 热点文件。
- `response.completed`、`response.failed`、`response.incomplete` 与流中断分别处理；收到 `response.completed` 后立即按成功终态结算，不再继续等待连接 EOF，避免终态后的 TCP/HTTP2 收尾抖动触发重复请求。
- 统一 `withCancellableModelResponse` 只让读取/协议阶段错误决定请求成败；成功结果产生后的 `Response.close()` 清理异常必须降级为 best-effort，不能反转成功并触发重复发送。
- `response.completed` 前发生可恢复网络中断、超时或临时服务错误时继续使用有限次数退避重试；用户主动取消、参数/权限错误、套餐限制等不可恢复状态不得盲目重放。
- 本机 Token 账本只记录成功返回的 `LocalModelReply` 中 API 实际 `usage`；失败或中断且未形成成功终态的尝试不得伪造 Token 记录，也不得把提示词估算再次叠加到总量。
- 套餐共享错误按 OpenAI 官方语义区分：用量限制、用量暂不可查、用户/工作区不可用、能力不支持、路由不支持、授权上下文异常。
- `subscription_sharing_usage_limit_exceeded` 只表示当前共享请求受限，不推断整个 Plus/Pro 套餐已经耗尽或自行推断重置时间。
- 流开始前兼容 `{"detail":"..."}` 直接准入错误；保留 HTTP status、request id、provider code/param 与合法 `Retry-After` 供诊断和退避。
- `Software caused connection abort`、connection reset 等真实断流映射为可恢复网络错误，用户界面不直接暴露底层 Java Socket 文案。
- 套餐额度不足不得静默切换到可能产生 API 费用的 API Key。
- Responses namespace 必须完整包含非空 `type/name/description/tools`；内部 function 必须完整包含非空 `type/name/description/parameters`。动态/MCP 工具缺少描述或参数定义时必须在本机适配层补齐合法默认值，非法参数 schema 必须在发起网络请求前本地拒绝。
- Responses 工具未声明 `strict` 时显式发送 `strict=false`，保持既有 Chat Completions 宽松工具语义；只有明确声明 `strict=true` 时启用 Structured Outputs 严格约束。
- OpenAI 专属递归 strict schema 校验只对 ChatGPT 套餐和 OpenAI 官方 Responses 路由生效；第三方/自定义 Responses 仍执行公共 function/parameters 结构校验，但不继承 OpenAI 的 Structured Outputs strict 子集限制。
- `strict=true` 的 function parameters 必须递归满足 OpenAI 约束：每一级 object 均为 `additionalProperties=false`，所有 `properties` 字段均列入 `required`；可选参数使用包含 `null` 的联合类型表达。严格模式下拒绝 `allOf/not/dependentRequired/dependentSchemas/if/then/else` 等当前不支持关键字。OpenAI 官方同时限制 schema 最多 10 层、5000 个 object properties 等总体复杂度；由于 `$ref` 与递归 schema 受支持，这类全图限制由 OpenAI 服务端作为最终权威校验，客户端不做可能误判递归引用的简化深度计算。
- ChatGPT 套餐共享继续遵守 SIWC Responses 限制：`store=false`、`stream=true`、完整历史放入 `input`，system 转 `instructions`；不得发送 `background/conversation/max_output_tokens/max_tool_calls/metadata/moderation/multi_agent/prompt/prompt_cache_retention/safety_identifier/temperature/top_logprobs/top_p/truncation/user/previous_response_id`，本地 function/custom tools 继续使用 namespace，不启用该路由不支持的 hosted MCP、file search、Code Interpreter、native computer、tool_search 或 programmatic tool calling。
- Chat、Work、主 Agent、子代理、Automation、群聊、人物辅助和 Vision 使用相同模型凭据解析边界。
- 多账户切换、断开、模型被移除、进程重启和旧 `model_profiles_v2` 迁移。

## PR 放行

最终验证必须针对：

```text
current main + current PR head
```

以下情况旧 CI 作废：

- main 已更新。
- PR 新增提交。
- 解决冲突 / merge / rebase。
- 测试、CI、Runtime 或构建配置变化。
- 相关横向功能先进入主线。

只看到“PR 可合并”或“旧 CI 全绿”不能作为最终放行依据。

完整作业规范见 [../AGENTS.md](../AGENTS.md)。
