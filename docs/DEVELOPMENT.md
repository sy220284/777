# 本地开发工具链

777 的标准开发宿主是 **Linux / WSL2 Ubuntu**。仓库构建脚本会把 Node / Python / Git Android Runtime 打进 APK，因此本机除了 Android Studio / JDK / SDK，还必须具备 GNU/Linux 命令行工具。

## AI / Agent 推荐入口

AI、Codex、自动化 Agent 不需要直接拼接底层 `setup-toolchain.sh` 参数，统一使用：

```sh
bash tools/dev/ai-toolchain.sh bootstrap
```

这个入口固定采用以下行为：

- 全程非交互。
- 默认准备 `build` 档环境。
- 先检查，已经满足时直接跳过安装。
- 缺失时才调用底层自动配置。
- 显式接受 Android SDK licenses。
- 不修改 shell rc。
- `sudo` 仅使用非交互模式；需要密码时立即失败，不等待输入。
- 状态写入仓库本地 `.777/toolchain/status.json`，该目录已忽略，不进入 Git。
- `bootstrap`、`check`、`status` 的 stdout 为机器可读 JSON；诊断日志写 stderr 和 `.777/toolchain/`。

常用命令：

```sh
# 一次性准备日常构建环境
bash tools/dev/ai-toolchain.sh bootstrap

# 只检查，不修改机器
bash tools/dev/ai-toolchain.sh check

# 查看最近一次机器可读状态
bash tools/dev/ai-toolchain.sh status

# 自动确保环境就绪，然后执行 Gradle
bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug

# 在工具链环境中执行任意命令
bash tools/dev/ai-toolchain.sh run -- ./gradlew :app:testDebugUnitTest
```

完整验证环境：

```sh
bash tools/dev/ai-toolchain.sh bootstrap --profile full
```

AI 入口支持通过环境变量覆盖目录，方便云沙箱、临时工作区和 CI：

```text
DEV777_TOOLS_ROOT
DEV777_ANDROID_SDK_ROOT
DEV777_TOOLCHAIN_ENV_FILE
DEV777_TOOLCHAIN_STATE_DIR
DEV777_REPO_ROOT
```

JSON 状态示例：

```json
{
  "schema": 1,
  "status": "ready",
  "reason": "environment_ready",
  "profile": "build",
  "exit_code": 0,
  "repo_root": "/workspace/777",
  "env_file": "/home/user/.config/777/dev-toolchain.env",
  "sdk_root": "/home/user/.local/share/777-dev/android-sdk",
  "tools_root": "/home/user/.local/share/777-dev",
  "state_dir": "/workspace/777/.777/toolchain",
  "log_file": "/workspace/777/.777/toolchain/check.log",
  "next_action": "bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug"
}
```

状态值约定：

- `ready`：当前 profile 已可直接使用，`reason=environment_ready`。
- `needs_bootstrap`：检查发现缺失项，`reason=missing_dependencies`。
- `error`：自动配置失败，`reason` 会区分 `bootstrap_failed`、`env_file_missing`、`post_check_failed`。
- `unknown`：尚未执行检查，`reason=not_checked`。


## 两个档位

### `build`

用于日常代码开发和真实 APK 构建：

- JDK 17。
- Android SDK platform / build-tools：与当前 CI 固定版本一致。
- `bash >= 4`。
- `curl`、`gpg`、`python3`、`dpkg-deb`、`readelf`、`sha256sum`、`unzip`、`tar`、`git`、`find`、`awk`、`sed`、`grep` 等 Runtime 准备脚本依赖。
- Gradle 使用仓库自带 Wrapper，不单独安装全局 Gradle。

### `full`

在 `build` 基础上增加完整工程验证能力：

- Node.js 22（官方 Harness fixture 来源验证）。
- `actionlint`（GitHub Actions 语义检查）。
- Android Emulator。
- Android 16 x86_64 system image。
- Android 17 / API 37 16 KiB page-size x86_64 system image。
- 可选创建 `777-android16`、`777-android17` 两个 AVD。

KVM 属于宿主虚拟化能力，脚本只能检测，不能替宿主开启 BIOS / Hyper-V / WSL 虚拟化。

## 先检查，不改机器

```sh
bash tools/dev/setup-toolchain.sh --check --profile build
```

完整环境：

```sh
bash tools/dev/setup-toolchain.sh --check --profile full
```

检查模式不会安装软件、不会修改 shell 配置。

## 自动配置

日常构建环境：

```sh
bash tools/dev/setup-toolchain.sh \
  --auto \
  --profile build \
  --accept-android-licenses \
  --configure-shell
```

完整验证环境：

```sh
bash tools/dev/setup-toolchain.sh \
  --auto \
  --profile full \
  --accept-android-licenses \
  --create-avds \
  --configure-shell
```

自动模式当前支持 Debian / Ubuntu 系 Linux 与 WSL。系统包通过 `apt-get` 安装；Android command-line tools、Node 22 和 actionlint 使用仓库固定版本与 SHA-256 校验后安装。

`--accept-android-licenses` 必须显式传入。没有该参数时，交互终端会显示 Android SDK license 确认；非交互环境会直接停止。

默认安装位置：

```text
可移植工具  ~/.local/share/777-dev
Android SDK  ~/.local/share/777-dev/android-sdk
环境文件     ~/.config/777/dev-toolchain.env
```

只有显式传入 `--configure-shell` 才会向 `~/.bashrc` / `~/.zshrc` / `~/.profile` 增加一条幂等加载入口。

## 从 GitHub Actions 产物安装

仓库工作流 **开发工具链** 会生成：

```text
777-dev-toolchain-linux/
├─ 777-dev-toolchain-linux.tar.gz
└─ 777-dev-toolchain-linux.tar.gz.sha256
```

工作流会先生成产物，再在一台 GitHub Ubuntu runner 上 **从这个产物解压 → 自动配置全新 SDK 目录 → 再次检查 → 实际执行 `:app:assembleDebug`**。因此产物不是只打包脚本，安装链本身也会被回归。

下载后：

```sh
sha256sum -c 777-dev-toolchain-linux.tar.gz.sha256
tar -xzf 777-dev-toolchain-linux.tar.gz
cd 777-dev-toolchain
bash tools/dev/setup-toolchain.sh --check --profile build
```

确认无误后再选择 `--auto`。

在 GitHub Actions 页面手动运行 **开发工具链** 时可以选择：

- `build`：验证普通 APK 构建环境。
- `full`：额外安装 Node 22、actionlint、Android 16 / 17 模拟器组件。

工具链相关 PR 会自动跑 `build` 档安装测试、AI 入口二次幂等 bootstrap、机器状态检查和真实 APK smoke build。Artifact 默认保留 30 天。

## 自定义安装目录

```sh
bash tools/dev/setup-toolchain.sh \
  --auto \
  --profile build \
  --tools-root /data/777-dev \
  --sdk-root /data/android-sdk \
  --env-file "$HOME/.config/777/custom-env" \
  --accept-android-licenses
```

如果系统层依赖已经由镜像、公司开发机或 CI 预装，可以加：

```sh
--skip-system-packages
```

此时脚本不会调用 `apt-get`，但最终检查仍会要求所有必备命令真实存在。

## 平台边界

- Linux x86_64：完整自动配置路径。
- Linux ARM64：Node / actionlint 可自动配置；Google Linux Android command-line tools 当前自动包只按 x86_64 路径验证，需预先提供可工作的 `sdkmanager`。
- Windows 原生：不作为标准构建宿主，使用 WSL2 Ubuntu。
- macOS 原生：当前 Runtime 脚本依赖 GNU `readelf`、`dpkg-deb`、`sha256sum` 和 Bash 4+，不作为标准构建宿主。

## 构建与验证

配置完成后：

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleOptimized
```

常用验证：

```sh
./gradlew \
  :core:test \
  :harness-core:test \
  :harness-runtime-android:test \
  :harness-interop:test \
  :harness-device-android:testDebugUnitTest \
  :mock-harness:test \
  :app:testDebugUnitTest

./gradlew :reference-validation:test
./gradlew :app:lintDebug
./gradlew :app:assembleOptimized
```

最终放行规则仍以 [VALIDATION.md](VALIDATION.md) 和当前 `.github/workflows/ci.yml` 为准。
