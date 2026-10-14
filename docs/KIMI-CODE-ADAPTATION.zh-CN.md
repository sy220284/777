# Kimi Code 订阅模型适配（777）

更新：2026-10-14。这里接入的是 **Kimi Code 会员订阅接口**，不是按量计费的 Kimi API 开放平台。使用统一模型网关，不新建一套会话/工具/计费逻辑。

## 用户如何配置

777 的“添加模型”预置 API 菜单仅保留 DeepSeek、MiniMax、Kimi Code（国内）。Claude、通义千问、Gemini、GLM、OpenAI API 已从预置菜单移除；ChatGPT 订阅套餐的 OAuth 账户与动态模型目录保持独立且不受影响。既有用户配置及密钥保留，用户仍可自行管理自定义 API 路由，不执行数据删除。

1. 在 [Kimi Code 控制台](https://www.kimi.com/code) 创建**订阅专用 API Key**，该 Key 与开放平台 Key 不通用；会员资格和可用模型以账号权益为准。
2. 777 设置 → 模型配置 → 选择提供商「Kimi Code」，从预置中选择模型 ID 并保存订阅 Key，协议为「Chat Completions」。
3. 仅保留国内订阅入口 `https://api.kimi.com/coding/v1`；完整请求为 `/coding/v1/chat/completions`。海外 `https://api.kimi.ai/coding/v1` 不再属于内置支持的官方订阅路由。
4. **不要**填 `https://api.moonshot.cn/v1`、`https://api.moonshot.ai/v1` 或开放平台 API Key；本次没有新增这些按量计费路由或模型。

## 订阅模型与限制

| 777 Model ID | 2026-10-14 官方对应模型 | 默认上下文 | 推理控制 | 输入能力 |
| --- | --- | --- | --- | --- |
| `k3` | K3 | 安全默认 **256K**；Pro/Allegretto 及以上可在模型配置中明确指定 **1,048,576** | `reasoning_effort=low/high/max`；Fast 对应 low，不自动关闭思考 | 文字、图片；官方另支持视频输入 |
| `k3-256k` | K3 256K 版 | 262,144 | 同上 | 文字、图片；**不支持视频** |
| `kimi-for-coding` | K2.8 Preview | 1,048,576 | 同上 | 文字、图片；官方另支持视频输入 |
| `kimi-for-coding-highspeed` | K2.7 Code 高速版 | 262,144 | 官方声明 Thinking ON；没有开放独立的档位选择 | 文字、图片；官方另支持视频输入 |

- Model ID 必须与上表一致。不同 Kimi 会员套餐拥有不同的模型和上下文权限：新套餐 Plus 可以使用 K3 的 256K 上下文；Pro 及以上才有 K3 的 1M 与高速版，老套餐使用 Moderato/Allegretto 对应档位。K3 预置编辑页现可填写上下文窗口（留空默认 256K，Pro/Allegretto 及以上可填写 `1048576`；超过官方 1M 的自定义值不会生效）。配置了大窗口并不代表账号已开通；权限不足时返回服务商原始错误，不偷换模型与账户。
- 官方目前说明关闭 K3 系列 Thinking 会路由到 K2.8 Preview 的非思考版本。为避免界面仍显示 K3 却暗中换模型，777 当前只提供 `low/high/max` 三档，快速模式对应 low。
- 切换 Model ID 或思考档位会影响提示词缓存；K3 转入 K3-256K 时，超过 256K 的历史应先压缩（复用 777 原有历史预算与摘要）；不要在新型号中沿用超限的视频输入。
- 777 **已接通 Kimi Code 的文字、图片理解链路**：聊天附件只持久化工作区引用，在请求时转为 `data:image/...;base64,...` 的 `image_url` 内容块，统一适配器及 SSE/非流式接口透传；PDF 可以解析文字及按图片预算渲染页面。增加了实际附件→规范消息→Kimi 请求体的回归用例。此处仅代表本地代码链路，不等于已使用真实订阅 Key 的线上测试。
- Kimi Code 模型的采样温度使用官方固定值：不会向订阅 Chat Completions 的普通或流式请求附加自定义 `temperature` 参数。
- **没有直接视频文件传输，也没有因此接通图片/视频/音乐/语音生成**：视频文件在现有附件底座被当作一般文件，不会编码为 Kimi 专属视频请求；官方 `video_in` 不等于应用已具备视频理解按钮和协议。音频录入、ASR/TTS 及媒体创作没有 Kimi Code 专用业务适配。

## 实现与账本

- Kimi Code 走同一 `LocalModelGateway` → OpenAI 兼容 Chat adapter → `DeepSeekClient`；带真实会员 Key 的 Bearer 授权，不伪造官方客户端标识或复用其他账户。
- 统一解析 SSE 增量、独立的 `reasoning_content`、工具调用、取消及错误终态。原生思考历史只在同一请求路由续轮保留，不跨订阅 Key、模型或兼容代理回放。
- Kimi Code **按会员权益、使用额度与窗口限流**，不是按请求 token 现金结算。现有 Token 账本可以记录服务商返回用量，但未获得服务商账单前不估计成已支付 API 金额。
- 新套餐（Plus/Pro）保留每 5 小时用量窗口，取消旧套餐的每周窗口；老套餐仍有每周与滚动窗口。账户有月度共享权益上限，实际可用量以 Kimi Code 控制台为准，不虚构本机额度查询 API。HTTP 401 可能是 Key 错误、套餐不支持模型或上下文；HTTP 403 可能是 5 小时/周/月额度不足。运行时保留服务商返回的原因，不盲目重试。

## 验证边界

`LocalKimiCompatibilityTest` 验证官方国内路由、拒绝海外官方识别、4 个订阅 Model ID、套餐默认上下文及用户明确升级窗口、Fast/Deep 推理参数、非流式与 SSE 思考内容、Token usage、Kimi 图片请求体与同路由回放隔离；静态/单测通过不等于账户实际登录成功。暂未拿到用户订阅 Key，真实多轮工具调用、会员配额消耗以及移动设备网络条件还需真实账号验收。

Kimi Code 的官方定位是**智能编程及第三方开发工具**，不同于开放平台面向产品集成的 API。客户端使用须遵循 Kimi 的服务条款和订阅权益范围，尤其不要冒充官方产品的客户端标识。

官方来源：
- https://www.kimi.com/help/kimi-code/models
- https://www.kimi.com/code/docs/
- https://www.kimi.com/help/kimi-code/benefits
- https://www.kimi.com/code/docs/kimi-code/error-reference.html
