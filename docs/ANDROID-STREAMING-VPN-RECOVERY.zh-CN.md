# Android 代理/VPN 下的模型流式连接排查

## 星辰VPN（1.0.15 APK）

1. 首页：出站模式优先 **规则**，短时切换 **全局** 做分流对照；不要选 **直连** 排查需要代理的模型接口。
2. 首页：选择持续稳定的线路，流式回复期间不要改节点或规则；首次启动允许系统 VPN 授权。
3. 设置 → 访问控制：若“仅代理选中的应用”，勾选 777；若“绕过选中的应用”，不要勾选 777。
4. 设置 → DNS 穿透：仅 DNS 污染或解析失败时测试，变更会重启代理内核，避免在回复过程中切换。
5. 高级设置存在 TCP keep-alive interval 时先保持本 APK `app_config.json` 中的默认 30 秒；它不是 SSE 应用层续传。
6. Android 系统对星辰VPN和 777 的电池/后台限制应分别检查，避免同时运行两个 VPN，连接已建立后不要中途切网络。

APK 中的 `libclash.so` 为 Clash/Mihomo 类代理内核。官方 Android 教程：https://doc.xcvpn.me/android/client/ ；Mihomo 保活文档：https://wiki.metacubex.one/en/config/general/ 。

## 777 运行与恢复规则

- 兼容 Chat Completions 的读取空闲超时为 180 秒，Anthropic 与 Responses 为 300 秒，模型总调用不设绝对截止。
- 已被模型服务接收的请求禁止自动重放整轮推理，保留既有安全续接和已执行工具去重的边界。
- 底层 OkHttp 对默认可重传的 POST 请求体可能自动恢复并再次发送；777 的模型推理请求体现在明确标记为 **isOneShot**，由上层统一执行安全重试，避免代理断线时绕过受理状态门禁。
- 新增的 `transport_kind` 单独记录底层异常：`timeout`、`dns_failure`、`tls_failure`、`connection_reset`、`broken_pipe`、`unexpected_eof`、`stream_incomplete` 等；不会覆盖 `failure_kind=stream_interrupted`。
- 前台请求失败日志补充 `network_path_at_start` 与 `network_path_at_failure`，只包含 VPN/Wi-Fi/蜂窝与 Android 网络是否已验证，不记录 IP、SSID、代理节点或凭证。
- 对照 `request_uid` 和 `request_id` 把 777 失败日志与同一时刻代理连接日志关联，才能确认是网络切换、节点掉线、上游超时，还是 SSE 终止标记丢失。

## 必须验证

同一模型和节点做规则/全局对照；真机制造 Wi-Fi↔蜂窝切换、VPN 重启和前后台切换，确认失败有根因、不会重放已受理推理或工具副作用、已完成部分能够安全续接。JVM 单测不能替代真实代理链路测试。
