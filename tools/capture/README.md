# Harness capture

`tools/capture` 用于连接一个**正在运行的 DeepSeek Harness**，录制远程 Web 协议交换，供协议审计、回放和黄金结果分析。

它是维护 / 验证工具，不是 Android 应用的运行时依赖。

## 前提

- Node.js 18+。
- 已运行的 DeepSeek Harness。
- 对该 Harness 的合法启动 token 或浏览器 Session Cookie。
- `npm install --prefix tools/capture`。

默认目标：

```text
http://127.0.0.1:3080
```

如需从 Android 设备访问电脑本机 Harness 做开发抓取，可使用 `adb reverse`。这只用于维护工具，不代表应用恢复旧 LAN 直连模式。

## 使用

```sh
npm install --prefix tools/capture
node tools/capture/capture.mjs
```

环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `DSH_URL` | `http://127.0.0.1:3080` | Harness 基础地址 |
| `DSH_TOKEN` | 空 | Harness 启动 token |
| `DSH_COOKIE` | 空 | 已存在的浏览器 Session Cookie |
| `DSH_SECONDS` | `5` | 流录制时长 |

`DSH_TOKEN` / `DSH_COOKIE` 至少提供一个。

token 和 cookie 都属于凭据，不要提交到仓库或日志。

## 录制内容

当前脚本会抓取：

- `session/list`
- `session/modelCatalog`
- `commands/list`
- `session/page`
- `$events`
- `session/control`
- `workspace/follow`
- `session/follow`

流量写入仓库根目录的 `capture-output/`，该目录已 gitignore。

典型输出：

```text
session.list.json
model.catalog.json
commands.list.json
session.page.json
events.ndjson
session.control.ndjson
workspace.follow.ndjson
session.follow.ndjson
```

## Remote mux

流使用：

```text
/api/remote.mux
```

客户端发送 `open / cancel`，宿主返回 `item / error / end`。

完整当前协议见 [../../docs/PROTOCOL.md](../../docs/PROTOCOL.md)。

## 与官方差分验证的关系

capture 用于观察真实远程 Web 协议。

Android 本机 Harness 的正式差分基线由：

```text
upstream/deepseek-harness.lock.json
reference-validation/
tools/reference-validation/
```

维护。

两者目的不同，不要用 capture 结果替代本机语义黄金 fixture。
