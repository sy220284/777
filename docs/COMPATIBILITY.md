# Compatibility

本文只描述当前版本的支持矩阵。

## 777

```text
App baseline: `.github/release-version` 为唯一事实源（当前 0.12.0-777.215）
minSdk: 36
targetSdk: 36
compileSdk: 37
Java / JVM target: 21
Kotlin: 2.4.20
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
