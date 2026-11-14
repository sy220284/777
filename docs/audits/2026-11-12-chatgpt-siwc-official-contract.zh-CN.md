# ChatGPT 登录与套餐授权：官方 SIWC 契约复核（2026-11-12）

## 官方依据

- [开源应用注册与登录](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [令牌响应字段](https://developers.openai.com/siwc/token-sharing-open-source/token-reference)
- [账号、刷新与注销](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [错误与恢复](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)
- [模型目录与推理](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)

## 777 的唯一登录链

1. `ChatGptHostIdentityStore` 维护当前安装稳定的 `ext_agent_host_id`，首次使用 `dynamic_agent_client`；`ChatGptAuthCoordinator` 负责本次 `state/nonce/PKCE` 和浏览器授权。
2. `ChatGptOAuthCallbackServer` 只接收 `127.0.0.1/auth/callback`；授权状态由 Coordinator 使用本次 `state` 校验。回调页面仅说明信息已返回，不能宣布账号已连接。
3. 首次注册回调取 issued `client_id` 并记住；已有账号续用其 issued ID。对 `invalid_grant` 仅允许重新获取一次新的授权码。
4. `ChatGptSessionManager` 负责授权码换令牌、刷新、撤销；`ChatGptTokenProtocol` 是令牌 HTTP 响应唯一解析边界，分别保留 HTTP 状态、JSON 结构类型、错误码与请求 ID（不记录原始令牌）。
5. `ChatGptIdTokenVerifier` 验证 RS256 签名、issuer、audience、exp、nonce、subject；账号标识绑定 issued `client_id + sub`。身份验证成功后才提交选中账号。
6. 只有同时取得 `chatgpt.tokens.use.direct` 与 `resource.invoke` 才启用套餐调用；身份登录与套餐权限分别显示。明确要求启用套餐时才请求 `prompt=consent`；官方 `force_reconsent` 需获部署确认后再使用。
7. 刷新令牌按官方旋转契约持久化替换值，确认失效代码才清除凭据。断开时先尝试撤销，清理本地凭据并明确提示远端撤销失败。模型权限变化由原有账户模型对账同步。
8. 设置页只是呈现入口；OAuth 长任务归属应用级账户 Owner，系统浏览器导致页面切换时仍继续；再次进入账户页、回到前台时读取权威账号状态。

## 修复的历史缺口

- PR #329 首次引入 `jsonPrimitive` 强制类型转换，对非普通 JSON 字段抛出内部异常，丢失真正的 OAuth 失败原因；这次收口在令牌协议解析边界并补对象/字符串错误测试。
- PR #765 曾修复回调监听与页面协程归属，因版本整合而关闭且未合入 `main`；这次以最新主线重新集成必要行为，同时补齐超时与浏览器探测测试。
- 旧刷新流程接受缺少替换 `refresh_token` 的成功响应，可能导致轮换后继续使用过期令牌；现按官方刷新契约显式拒绝并保留原始错误。

## 功能行为

- 单元测试：正常令牌、未知嵌套元数据、可识别的 OAuth 字符串/对象错误、非法字段类型、有效期、OAuth 回调超时及浏览器探测。
- Android 仪器测试：有效刷新令牌旋转、权限降级、取消、迟到刷新不能覆盖被删除或重连账号。
- 真机联机：首次动态注册、已登记账号重登、无套餐授权登录、手动启用套餐、多账户切换、浏览器返回、刷新轮换、服务端撤销及错误恢复。
- 用户能明确区分“浏览器回调已收到”“身份已登录”“套餐可用”“模型连接待验证”四个终态；最终成功以本机账户记录及服务端授权读取为准。

此文档记录检查清单；执行通过情况以对应 SHA 的 GitHub CI 和真实设备/账户结果为准。

## 2026-11-12：Android 运行时矩阵版本同步

- 真实 CI 从带 Termux 签名和 SHA-256 校验的包索引得到：Git 2.56.1、Python 3.14.10；早先锁定的 2.56.0 / 3.14.8 已经漂移，导致 APK 构建在 Kotlin 编译前失败。
- 本 PR 已同步 Android 内置运行时固定版本，仍须通过官方签名、索引哈希、安装包哈希、ELF 动态库依赖与 16 KiB 页面对齐验证，绝不允许静默接受仓库未知版本。
- Android 真机测试改为检查启动后的 Git/Python 实际版本与生成的 APK 版本标记完全一致，避免今后每次安全升级都要维护重复版本字面量。
