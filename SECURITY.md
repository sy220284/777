# Security

777 能在 Android 本机执行 Agent，也能通过配对 HTTPS 中继控制远程 Harness。

完整信任模型见 [docs/SECURITY.md](docs/SECURITY.md)。

## 报告漏洞

请通过 GitHub Security Advisory 私下报告，不要公开提交 Issue：

- [Report a vulnerability](https://github.com/sy220284/777/security/advisories/new)

建议提供：

- 应用版本。
- 本机 / 远程模式。
- 复现步骤。
- 受影响能力。
- 是否涉及凭据、数据、文件、网络、设备权限或外部命令。
- 可稳定复现的日志或最小案例。

## 当前安全边界

- 远程控制只支持配对后的 HTTPS 中继；旧 LAN 扫描和明文直连路径已经移除。
- 模型密钥和中继凭据使用 Android Keystore 加密。
- 本机文件与命令执行受应用私有工作区、审批和能力边界约束。
- Web 工具执行 SSRF / DNS / 重定向目标校验，并限制请求和响应大小。
- Agent 恢复不会盲目重放结果未知的外部副作用。
- Android 无障碍、通知和 VirtualDisplay 通过显式 capability provider 暴露。
- 应用更新可优先使用构建时固定的公开 Gitee Release 镜像并在异常时回退 GitHub；无论来源都必须通过完整性、包名、签名和版本校验。

如果问题涉及这些边界之外的行为，请一并说明为什么现有保护不足。
