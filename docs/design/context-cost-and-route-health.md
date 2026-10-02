# 上下文成本与路由健康治理

本文件记录真实长 Work 会话暴露出的模型成本与失败重放问题，以及当前已经落地的分层治理。历史实测数据来自 SessionEventLog / TokenUsage 诊断字段，用于说明问题规模，不作为固定性能承诺。

## 1. 历史实测

一次长会话曾出现：

| 指标 | 实测值 |
|---|---:|
| 模型请求次数 | 52 |
| 估算输入合计 | 2,788,769 tokens |
| 单次输入峰值 | 116,487 tokens |
| 首末请求 | 8,067 → 116,487 |
| 失败请求 | 12 次 |
| 失败请求累计估算输入 | 361,467 tokens |
| 用户消息 | 15 条 |
| 单次工具结果 | 80,000–120,000 字符 |

其中单一路由出现过 10 次同类 `CHATGPT_PLAN_STREAM_INTERRUPTED`。单次失败并不可怕，危险在于自动续轮再次选择同一故障路由并重新发送大上下文。

## 2. 已由 #381 收敛的成本根因

最新 main 已经完成以下治理：

- Work 请求超过稳态阈值后，派生“可信检查点 + 近期原文”的有界投影，不再让输入随完整历史无限线性增长。
- 旧工具结果先衰减，连续 system 前缀和当前运行约束保持原样。
- 成功请求按供应商真实 input Token 结算；未知受理使用校准后的 uncertain exposure。
- 请求取消携带 admission state，明确未发送取消释放预算，可能已送达才保守计入。
- Prompt cache comparison baseline 按“会话 + 物理路由指纹”隔离，并输出缓存诊断。
- 诊断导出有界读取 EventLog / TokenUsage，不另建第二套事实源。

因此本 PR 只处理仍独立存在的根因：**故障物理路由在不同 Work run / 自动续轮之间缺少共享健康状态**。

## 3. 本 PR：两层路由熔断

### 3.1 身份边界

传输健康按 `LocalModelProfile.routeFingerprint()` 归属。该指纹包含 provider、规范化 endpoint、model、auth kind、实际 protocol 和 credentialRef，排除仅用于 UI 的 profileId，也不包含凭据明文。

同一真实账户/协议/端点的多个 UI 档案共享健康状态；切换账户、协议、模型或端点会获得新的健康身份。

### 3.2 状态机

- durable account / credential 故障继续由每个 Work run 自己的 `MODEL_ROUTE_CIRCUIT_OPEN` 管理；
- transient transport 故障由进程级 `LocalModelRouteHealth` 管理；
- 连续 3 次可归因传输失败后进入 90 秒 cooldown；
- cooldown 期间在预算预留和 provider 调用之前直接抛出 `MODEL_ROUTE_CIRCUIT_COOLDOWN`，不产生模型消费；
- cooldown 到期进入 half-open，只允许 **一个**恢复探测，其它并发请求继续被挡住；
- 探测成功立即清零；
- 探测失败把 cooldown 翻倍，最大 900 秒；
- 冷却期间已经在途的兄弟请求失败只增加诊断 streak，不重复指数延长同一个窗口；
- 用户取消、本地上下文/预算拒绝和其它非传输故障不会累计 transient streak。

### 3.3 生命周期与资源边界

路由健康注册表最多跟踪 256 个物理路由，并清理超过 30 分钟且已不受保护的空闲状态。容量满时优先淘汰未处于 cooldown / probe 的旧状态；不会为了记录新路由而无限增长进程内存。

## 4. 与自动续轮的关系

流中断仍可以生成一个“继续当前工作”的独立任务续轮；若同一路由已经进入 cooldown，下一次模型 admission 会在预算预留之前被拒绝，所以不会再次产生模型 Token 消费。

后续若要把门禁进一步前移到“连续轮都不入队”，需要让失败事件携带精确物理路由身份；在没有可靠身份之前，不用 UI 当前档案猜测路由，避免误伤已经切换到其它模型的工作。

## 5. 后续方向

- 对供应商明确支持的增量续轮协议，可在 provider / model / protocol / auth / account 完全一致时研究增量发送；ChatGPT 套餐当前 Responses contract 禁止 `previous_response_id`，不得强行套用。
- 工具输出继续按现有有界投影治理；如实测仍占据主要成本，再基于 Token 账本决定是否引入独立份额，而非先加固定限制。
- 性能验收以诊断事实为准：关注单请求 input 分布、缓存命中、同路由失败重复次数、cooldown 拦截数和实际 provider 请求数。

## 6. 验证要求

专项测试至少覆盖：

- 连续失败阈值和稳定错误码；
- cooldown 到期只有一个 half-open 探测；
- 成功跨 run 清零健康状态；
- 探测失败按 90 → 180 → … → 900 秒延长；
- 用户取消/释放 half-open permit 不计新失败；
- durable account fault 不污染 transient registry；
- 400+ 路由 churn 下注册表仍保持容量上限；
- 主 Agent 与子代理都使用同一物理路由指纹进入 admission。
