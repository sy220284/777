# 本地开发工具链

777 的标准开发宿主是 **Linux / WSL2 Ubuntu**。仓库构建脚本会把 Node / Python / Git Android Runtime 打进 APK，因此本机除了 Android Studio / JDK / SDK，还必须具备 GNU/Linux 命令行工具。

## 人工使用推荐入口

从仓库内使用：

```sh
bash tools/dev/install.sh
```

从 GitHub Actions 下载工具链 Artifact 后，解压一次进入目录，直接：

```sh
bash install.sh
```

无参数会进入向导；也可以直接：

```sh
bash install.sh build
bash install.sh full
bash install.sh check build
```

`build` 用于日常 APK 开发；`full` 会额外安装 Node.js 22、actionlint、Android Emulator、Android 16 / 17 system image，并创建 `777-android16`、`777-android17` 两个 AVD。

`full` 开始下载前会展示安装内容并检查剩余磁盘空间：低于 6 GiB 会停止，低于 12 GiB 会提示空间偏紧。

检查发现缺失项时会直接给出推荐修复命令；KVM 这类宿主能力会明确标成需要人工处理。

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

检查模式不会安装软件、不会修改 shell 配置。缺失时会直接显示推荐安装命令。

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

`--accept-android-licenses` 仍是底层非交互参数。人工通过 `install.sh` 使用时由 sdkmanager 在终端中显示 license 并确认；AI / CI 使用显式非交互接受。

默认安装位置：

```text
可移植工具  ~/.local/share/777-dev
Android SDK  ~/.local/share/777-dev/android-sdk
环境文件     ~/.config/777/dev-toolchain.env
```

只有显式传入 `--configure-shell` 才会向 `~/.bashrc` / `~/.zshrc` / `~/.profile` 增加一条幂等加载入口。

## 从 GitHub Actions 产物安装

仓库工作流 **开发工具链** 会生成同名 Artifact：

```text
777-dev-toolchain-latest
```

GitHub 下载的是 ZIP，ZIP 内直接包含：

```text
install.sh
SHA256SUMS
安装说明.txt
tools/
docs/
```

因此只需要解压一次：

```sh
unzip <下载的 Artifact ZIP> -d 777-dev-toolchain
cd 777-dev-toolchain
./install.sh
```

`install.sh` 会先用 `SHA256SUMS` 校验包内文件，再进入安装 / 检查流程。

工具链相关 PR 会自动执行两条回归：

```text
build
→ Artifact 下载
→ SHA-256 校验
→ AI bootstrap
→ check
→ 第二次 bootstrap 幂等检查
→ :app:assembleDebug

full
→ 同一 Artifact 下载
→ SHA-256 校验
→ full 自动安装
→ Node / actionlint / Emulator / Android 16 / 17 image
→ 创建 Android 16 / 17 AVD
→ full 再检查
```

Artifact 单份保留 30 天。GitHub Actions Artifact 不能直接延长原文件到期时间，因此工作流会在默认分支每月 **1 日和 20 日**自动重新生成一份新的 `777-dev-toolchain-latest`。最长刷新间隔 19 天，旧产物到期前会有新产物接替，相当于自动续期。

在 GitHub Actions 页面手动运行 **开发工具链** 时仍可选择：

- `build`：验证普通 APK 构建环境。
- `full`：验证完整工具链环境。

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
