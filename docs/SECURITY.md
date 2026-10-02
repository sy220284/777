# Security

777 可以在手机本机运行 Agent，也可以通过 HTTPS 中继控制远程 Harness。

Agent 具备读取 / 修改工作区文件、执行进程、访问网络和调用设备能力的权限，因此安全边界属于产品核心能力。

## 凭据

- 模型密钥和中继凭据使用 Android Keystore 加密保存。
- 完整凭据不进入模型上下文、Session Event Log 或普通诊断日志。
- Token usage 日志记录 usage、模型、动作和运行归属，不要求保存完整 prompt 正文。
- 敏感剪贴板、凭据和高权限操作保持显式权限边界。


## ChatGPT 账户登录

本机模式可以通过 OpenAI 的 “Continue with ChatGPT” 授权 ChatGPT 套餐模型调用。

- OAuth 使用系统浏览器、PKCE、`state` 和 `nonce`。
- 回调只绑定 `127.0.0.1` 的随机端口，不开放 LAN 监听。
- 首次动态注册返回的 issued client id 与稳定主机标识持久保存。
- ID Token 校验签名、issuer、audience、过期时间和 nonce。
- 只有授权 scope 包含 `chatgpt.tokens.use.direct` 时才视为套餐调用可用。
- access token、refresh token、ID Token 和账户注册记录使用 Android Keystore 加密保存，不进入 Session Event Log、模型上下文或诊断日志。
- 多账户按 issued client id + subject 隔离；切换账户后重新读取对应可见模型。
- ChatGPT 登录不授予 777 读取 ChatGPT 历史会话或 Memory 的权限。
- 手机本机的 ChatGPT OAuth 凭据不会自动转发给远程 Harness；远程执行主机需要独立授权边界。

## 远程连接

远程控制只支持 HTTPS 中继配对。

- 不提供 LAN 扫描或明文 Harness 直连。
- 配对可固定中继证书指纹。
- 证书变化不会静默继承旧信任。
- 认证请求禁止 HTTPS → HTTP 降级。
- 手机不跨网络携带 Harness 自己的浏览器 Session Cookie；由中继管理上游 Harness 会话。

## 本机工作区

本机文件工具验证应用私有工作区与配置的用户共享存储根目录（`userRoots`）。Shell 默认目录是工作区，但进程权限由 Android 应用权限和工具审批控制，路径工具的边界不等同于操作系统级 Shell 沙箱。

- 路径必须规范化并验证。
- 递归文件操作拒绝通过符号链接越出工作区。
- 未验证外部路径不能直接进入写入 / 删除操作。
- 工作区写入、进程、网络、设备等操作按工具策略与用户配置进入审批链；安全自动审批只覆盖明确标记为安全的只读或受工作区边界约束的操作，Shell 始终保持显式审批。

## 工具与插件

- 内置和插件工具经过统一 Tool / Capability Registry。
- 未注册工具 fail closed。
- 只读子代理不能调用状态修改工具。
- 平台 provider 由受信任的应用组合根构造。
- 不动态加载未知 APK / Dex 作为 Harness 插件。
- MCP stdio / LSP 启动外部进程时仍受进程审批边界约束。

## Agent 运行与恢复

前台、子代理和 Automation 使用 run checkpoint。

恢复原则：

- 已开始但结果未知的副作用不会被自动重放。
- `TOOL_OUTCOME_UNKNOWN` 和 `TOOL_NOT_STARTED` 会作为下一轮显式事实。
- 外部取消会取消并等待当前拥有的 Agent run。
- 交互式审批 / 提问会阻断需要用户决定的后台执行。

发送入口不会在拒绝时静默清空用户草稿。

## Web 与网络

网页工具对目标进行安全校验。

- 拒绝环回、私网、链路本地、组播和保留地址。
- DNS 解析结果固定到验证后的地址。
- 重定向后重新执行目标校验。
- 文本读取和响应体有大小上限。
- 非安全 HTTP 方法不自动跟随重定向。
- 二进制响应使用文件 / 下载能力，不通过普通文本 fetch 无限读入内存。

## Webhook

本机 Webhook：

- 只监听 `127.0.0.1`。
- 使用 bearer token。
- 请求大小有上限。
- 旧配置不能开启外部明文监听。

## Android 设备能力

无障碍、通知和 VirtualDisplay 都通过显式 capability provider 暴露。

- 子代理只能使用分配给自己的虚拟屏。
- 设备写操作保持审批 / 能力约束。
- UI 自动化不能绕过 Android 系统权限模型。

## 数据存储

- 会话、工作区、用户规则、人物记忆和 usage ledger 存在应用私有存储。
- 平台 backup / device transfer extraction 关闭。
- Session / memory 文件使用原子写入、备份和损坏恢复策略。
- 会话日志的 8 MiB 上限是分段阈值，完整历史仍持久保存；不能把分段视为总保留上限。聊天待归并内存窗口最多 64 回合、每段文本 4000 字符，完整事实保存在日志中。Token 明细保留 90 天、最多 10000 条（超限收缩至 9000 条），单条最多 4 KiB；累计 API 用量独立保存。

## 更新

应用内更新必须验证：

- 下载完成性。
- SHA-256。
- 包名。
- 签名证书。
- 目标 versionCode 高于当前安装版本。

验证失败不会继续打开安装器。

## 报告漏洞

请使用 GitHub Security 页面私下报告安全问题，并提供：

- 应用版本。
- 运行模式。
- 复现步骤。
- 受影响能力。
- 是否涉及数据、凭据、网络或设备权限。
