# Compatibility

本文只描述当前版本的支持矩阵。

## 777

```text
App baseline: 0.12.0-777.22
minSdk: 36
targetSdk: 36
compileSdk: 37
Java: 17
Kotlin: 2.2.10
AGP: 9.4.0
```

Android 16 以下设备不在支持范围。

## 本机模式

本机 Harness 运行在 Android 应用进程内。

默认发行 ABI：

```text
arm64-v8a
```

模拟器 / x86_64 构建：

```sh
DSH_RUNTIME_ABIS=x86_64 ./gradlew :app:assembleDebug
```

当前 APK 内工具运行环境包括：

- Node.js
- Python
- Git
- Android shell / managed process
- MCP HTTP / stdio
- LSP 客户端
- Android accessibility / notification / virtual display providers

这些运行时是 Agent 工具环境，不承载 Harness Core。

## 本机语义基线

当前锁定：

```text
deepseek-ai/deepseek-harness
0.2.1-alpha.1
5badb15009ae1756c3afe0ae0cef1faafc290ccc
Session format reference: V4
```

来源：

```text
upstream/deepseek-harness.lock.json
```

这个版本只用于本机原生 Harness 的差分验证。

## 远程模式

当前远程协议基线：

```text
Harness 0.1.6-alpha.1
0d1f50007f9bca3f52b06e1c3074fa14d5fb0720
```

中继：

```text
dsh-relay 0.2.1
HTTPS pairing
```

远程访问只支持配对中继，不支持旧的 LAN 扫描 / 明文直连路径。

## 兼容策略

777 以当前协议形状为准，不维护一套按 Harness 版本号切换的协议实现。

兼容规则：

- 未知 JSON 字段忽略。
- 未知事件 / 内容块保留 passthrough 或诊断信息。
- 可选 endpoint 返回 404 时按“能力不可用”降级。
- 认证失败、Host/Origin 拒绝和业务失败分别处理。
- 协议基线只在完成 fixture 刷新和一致性验证后升级。

## 平台差异

Android 本机模式目标是语义等价，不要求复制桌面宿主的物理实现。

不会承诺桌面等价语义的能力包括：

- 任意 Node 包动态插件 / 热重载。
- PowerShell / Windows 工具。
- 完整桌面 Chromium / Stagehand 宿主。
- 桌面 Office 转换链。
- Android 系统无法保证的秒级常驻调度。
- 任意外部进程在应用进程被系统杀死后透明续跑。

遇到平台限制时必须显式降级或标记不支持，不能伪装成兼容。

## 验证

兼容性放行依赖：

- 官方 fixture provenance。
- `reference-validation`。
- 核心与 Android 单元测试。
- Android 16 / Android 17 仪器测试。
- optimized APK 安装 / 启动。
- 当前 main + PR head 的最终组合 CI。

详见 [VALIDATION.md](VALIDATION.md)。
