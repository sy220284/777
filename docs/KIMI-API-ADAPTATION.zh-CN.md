# Kimi 开放平台模型适配

更新：2026-10-14。以 777 主线的统一模型网关为基础，新增官方 Kimi API 模型，而非新增平行业务引擎。

## 账户与模型

- **Kimi 开放平台 API Key**：预置地址 `https://api.moonshot.cn/v1`，协议 Chat Completions；完整聊天接口 `/v1/chat/completions`，模型列表 `/v1/models`。在设置中添加模型并填写开放平台 API Key；按实际使用量计费。
- **Kimi Code 编码套餐**：保留已有 `https://api.kimi.com/coding/v1`、`k3` 等配置。这与开放平台 API 不是同一账户与额度体系；旧凭据、路由和配置 ID 不迁移、不混用。
- 正式 API 型号：`kimi-k3`（1,000,000 token）、`kimi-k2.7-code`、`kimi-k2.7-code-highspeed`、`kimi-k2.6`（后三者 256,000 token）。用户可从预置模型选择，模型目录读接口仍用统一查询功能。
- 旧 `kimi-k2.5`、`kimi-latest`、`kimi-k2`、`moonshot-v1` 系列已下线，不加入新预置；不删除用户自定义第三方代理。

## 统一的协议行为

| 型号 | 思考控制 | 温度 | 视觉与工具 |
| --- | --- | --- | --- |
| K3 | 始终思考；`reasoning_effort=low/high/max`；快速档映射 low，不能关闭 | 固定，不传 temperature | 官方支持图片/视频理解、工具调用 |
| K2.7 Code / 高速版 | 始终思考；不发送 thinking/effort 覆盖 | 固定，不传 temperature | 官方支持图片/视频理解、工具调用 |
| K2.6 | `thinking.type=enabled/disabled`；不发送 reasoning_effort | 固定，不传 temperature | 官方支持图片/视频理解、工具调用 |

主线已经有同一个 OpenAI 兼容聊天传输、SSE 流式增量、工具调用、请求取消及终态验证、会话历史回放和 Token 用量账本。本次仅在官方路由写入对应参数，不复制这些设施，也不修改第三方兼容代理的行为。

- 思考内容 `reasoning_content` 继续由统一传输单独解析，正文和内部推理不会混排。K3 与 K2.7 保留式思考要求的 assistant 完整消息，复用既有同路由私有历史回放；切换账户/协议/模型时不带入上一服务商的原生思考元数据。
- 自动上下文缓存与服务商返回的 `usage` 仍按现有共享账本读数；没有服务商实际账单时，不把 token 使用量伪装成已核实人民币费用。
- 官方具备视频输入理解，不代表 Android 客户端已经实现视频文件的上传/引用；通用聊天传输当前实际支持的是文字与图片。图片/视频生成、配音等专业创作 API **未因此接通**，不新增虚假的“生成”入口。
- K3 官方不支持公网图片 URL：现有原生图像输入使用本地内容的 data URI；未声称网络 URL 或 `ms://` 上传能力已完成。
- 全部模型按开放平台实际 Key 与可用额度进行服务商鉴权。无 Key 或服务商拒绝时，沿用统一错误与诊断处理，不静默改用 Kimi Code 额度。

## 验证范围

新增 `LocalKimiCompatibilityTest`：官方预置与冒名地址隔离、K3 思考强制下限、K2.6 开关、K2.7 固定思考、固定温度、聊天与 SSE 请求字段、用量记录、同路由思考历史回放与跨路由隔离。须由仓库 JVM / Android CI 执行确认。

未提供真实 Kimi 开放平台 API Key，因此没有声称实际计费请求成功，也没有完成真机长聊、弱网切换、多图及工具链实机回归。

## 官方参考

- 模型目录与下线公告：https://platform.kimi.com/docs/models
- K3 模型、视觉、工具、思考：https://platform.kimi.com/docs/guide/kimi-k3-quickstart
- K2.7 Code：https://platform.kimi.com/docs/guide/kimi-k2-7-code-quickstart
- K2.6：https://platform.kimi.com/docs/guide/kimi-k2-6-quickstart
- 保留式思考：https://platform.kimi.com/docs/guide/use-thinking-models
- K3 推理档位：https://platform.kimi.com/docs/guide/use-reasoning-effort
