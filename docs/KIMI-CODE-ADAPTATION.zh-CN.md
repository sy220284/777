# Kimi Code 订阅模型适配（777）

更新：2026-11-16。本方案接入 Kimi Code 会员专用 API Key，和按量计费的 Kimi API 开放平台相互独立。所有对话与工具通过唯一 `LocalModelGateway` / Session / Agent 运行链路完成。

## 用户配置和协议

777 设置 → 模型配置 → 添加模型 → Kimi Code，可从 6 个预置模型中选择。使用国内官方地址 `https://api.kimi.com/coding/v1`，新增预置默认 Responses，完整路径 `/coding/v1/responses`。用户可以在预置编辑界面手动选择 Chat Completions（`/coding/v1/chat/completions`）后测试连接。历史已保存的 Chat 路由保留，不暗中改写凭据、协议或会话路由。

Kimi 官方文档推荐具备原生 Responses 的工具优先使用 Responses；响应包括语义流、reasoning、function calling。用户名、套餐 Key、模型权限由官方控制；不能混用 Kimi 按量平台 Key，也不伪造官方客户端身份。

## 模型、权益及能力

| 模型 ID | 实际型号 | 默认本地上下文 | 思考控制 | 原生输入 |
| --- | --- | --- | --- | --- |
| `k3.1-pro` | K3.1 Pro | 256K（合格套餐可手动升至 1M） | 仅思考；low/high/max | Responses 文字、图片；Chat 支持视频 |
| `k3.1-pro-highspeed` | K3.1 Pro 高速 | 1M（需要 Pro/Allegretto 以上权益） | 仅思考；low/high/max | Responses 文字、图片；Chat 支持视频 |
| `k3` | K3 | 256K（合格套餐可手动升至 1M） | low/high/max | 文字、图片；Chat 协议可处理视频 |
| `k3-256k` | K3 256K | 256K | low/high/max | 文字、图片 |
| `kimi-for-coding` | K2.8 Preview | 1M | low/high/max | 文字、图片；Chat 协议可处理视频 |
| `kimi-for-coding-highspeed` | K2.7 Code 高速 | 256K | 固定思考；不展示手动档位 | 文字、图片；Chat 协议可处理视频 |

会员档位由官方权益决定。K3 / K3.1 Pro 只接受 262144、1048576 两档的用户上限请求，默认 256K；填写 1M 不会使不具备权限的账户自动升级。K3.1 Pro 始终使用思考模式，快速模式映射到 low，禁止在请求里设置 `none` 关闭思考；新型号建议新建会话，避免跨代历史影响缓存与效果。

## 图片、视频及连续工具运行

- Responses 路由在本机将图片附件转成 `input_image`，通过 `reasoning.effort` 设置思考，按 OpenAI Responses 流式事件提取正文、思考、函数调用和 Token 用量，沿同一路由恢复工具结果与对话。
- **Kimi 官方 Responses 目前不支持视频输入。** 777 对该路由不声明视频能力，错误发送 `video_url` 时显式拒绝，不将视频悄悄丢弃。用户需要视频时，在模型编辑页选择 Chat Completions；旧 Chat 多模态路径保留 `video_url` 和 20MB 输入上限。
- 对可用 Chat 视频路由，`k3.1-pro`、`k3.1-pro-highspeed`、`k3`、`kimi-for-coding`、`kimi-for-coding-highspeed` 具备现有视频格式编码；`k3-256k` 不支持视频。仅按实际协议向设置页提供能力标识。
- 聊天、工作模式、子智能体共用同一 Session 和附件持久引用；图像/视频 Base64 在日志中脱敏，历史文件引用不会每轮重新上传。
- Kimi 的账号权益属于订阅配额，本地 Token 账本只记录服务商返回的用量，不虚构已支付的按量金额。调用失败保留供应商原始 HTTP 诊断。

## 验收和来源

源码回归覆盖六个预置模型、模型上下文上限、协议切换、Kimi 原生 Responses 图片/思考/工具、Chat 视频旧路线以及附件隐私投影。真实订阅 Key 的在线联调与设备 UI 测试以实际回执为准。

- [Kimi Code 官方模型配置](https://www.kimi.com/code/docs/kimi-code/models.html)
- [Codex 原生 Responses 文档](https://www.kimi.com/code/docs/third-party-tools/codex.html)
- [官方模型更新公告](https://www.kimi.com/code/docs/kimi-code/whats-new.html)
