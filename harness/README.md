# 777 远程 Harness

远程模式通过配对 HTTPS 中继连接电脑上的 DeepSeek Harness。

当前链路：

```text
777 Android
→ HTTPS dsh-relay
→ DeepSeek Harness loopback listener
```

应用不提供旧的局域网扫描、mDNS 发现或明文 Harness 直连。

## 连接

1. 在电脑启动 DeepSeek Harness，并保持 Harness 自身监听在本机回环地址。
2. 安装并启动 [dsh-relay](https://github.com/sorsama/deepseek-harness-relay)。
3. 在 relay 中完成 Harness 连接和配对设置。
4. 在 Android 应用中打开远程控制并扫码 / 输入中继配对信息。
5. 确认证书和配对状态后连接。

README 中的快速开始给出了当前命令。

## 当前协议

远程 Web 协议基线：

```text
Harness 0.1.6-alpha.1
0d1f50007f9bca3f52b06e1c3074fa14d5fb0720
```

协议详情见 [../docs/PROTOCOL.md](../docs/PROTOCOL.md)。

## 排查

连接失败时依次检查：

- Harness 是否正常运行。
- relay 是否能访问 Harness loopback。
- 中继 URL 是否正确。
- HTTPS 证书是否可信。
- 配对令牌 / 会话是否有效。
- 防火墙 / 代理是否阻断 relay。
- 应用是否仍保存旧证书固定信息。

不要把 Harness 本机端口直接填到 Android 远程连接地址。

## 安全

安全边界见 [../docs/SECURITY.md](../docs/SECURITY.md)。

远程链路不依赖将 Harness 暴露到局域网，因此不需要旧的开放监听补丁。
