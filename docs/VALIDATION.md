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
- `response.completed`、`response.failed`、`response.incomplete` 与流中断分别处理。
- 套餐共享错误按 OpenAI 官方语义区分：用量限制、用量暂不可查、用户/工作区不可用、能力不支持、路由不支持、授权上下文异常。
- `subscription_sharing_usage_limit_exceeded` 只表示当前共享请求受限，不推断整个 Plus/Pro 套餐已经耗尽或自行推断重置时间。
- 流开始前兼容 `{"detail":"..."}` 直接准入错误；保留 HTTP status、request id、provider code/param 与合法 `Retry-After` 供诊断和退避。
- `Software caused connection abort`、connection reset 等真实断流映射为可恢复网络错误，用户界面不直接暴露底层 Java Socket 文案。
- 套餐额度不足不得静默切换到可能产生 API 费用的 API Key。
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
