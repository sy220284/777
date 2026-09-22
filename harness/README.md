# 777 远程连接

应用的远程控制入口使用中继配对。局域网扫描、直连和 mDNS 发现已移除。

1. 在电脑运行 DeepSeek Harness，并保持服务仅监听本机回环地址。
2. 部署 [dsh-relay](https://github.com/sorsama/deepseek-harness-relay)，配置其与 Harness 的连接。
3. 在应用中打开远程控制，输入中继地址并完成配对。
4. 使用 HTTPS 部署中继；检查证书及配对信息后再连接。

连接失败时，检查中继进程、地址、证书、防火墙与配对令牌。不要将 Harness 的本机端口当作中继地址。

参见 [安全说明](../docs/SECURITY.md)。旧的局域网开放监听补丁不再适用于本应用。
