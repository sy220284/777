# 本地开发工具链

777 的标准开发宿主是 **Linux / WSL2 Ubuntu x86_64**。

本地工具链执行一条硬规则：

> **开发依赖只允许来自 GitHub Actions 生成的工具链 Artifact。**

本地安装器不会调用 `apt-get`、`curl`、`wget`、`sdkmanager` 下载、Gradle/Maven 在线解析、Node 下载或其他外部补齐路径。Artifact 缺项时直接失败，回到工作流重新生成完整产物。

## Artifact 中包含什么

### `build`

日常开发、单元测试和 APK 构建需要的编译环境直接打进产物：

- JDK 17，包含 `java` 与 `javac`。
- Android command-line tools。
- Android platform-tools。
- Android API 37 platform。
- Android build-tools 37.0.0。
- Gradle 9.6.0 完整分发。
- Gradle Wrapper 分发缓存。
- Kotlin 2.2.10、AGP 9.4.0 与项目 Gradle 依赖的离线缓存。
- 777 Runtime 构建缓存，覆盖 APK 内 Node / Python / Git Runtime 的准备链。

### `full`

在 `build` 基础上增加：

- Node.js 22。
- actionlint 1.7.12。
- Android Emulator。
- Android 16 x86_64 system image。
- Android 17 / API 37 16 KiB page-size x86_64 system image。
- 可用产物内镜像创建 `777-android16`、`777-android17` 两个 AVD。

KVM 属于宿主虚拟化能力，Artifact 只能检测，无法替宿主开启 BIOS / Hyper-V / WSL 虚拟化。

## Artifact 名称

开发工具链工作流同时生成：

```text
777-dev-toolchain-build-latest
777-dev-toolchain-full-latest
```

两份产物保留 30 天，并在默认分支每月 **1 日和 20 日**重新生成，最长刷新间隔 19 天。

## 人工安装

先从 GitHub Actions 的 **开发工具链** 工作流下载需要的 Artifact ZIP。

解压一次：

```sh
unzip <下载的 Artifact ZIP> -d 777-dev-toolchain
cd 777-dev-toolchain
```

日常环境：

```sh
bash install.sh build
```

完整环境：

```sh
bash install.sh full --create-avds
```

安装器会先校验 `SHA256SUMS`，再解开产物内 `payload/toolchain.tar.gz` 并安装 JDK、Android SDK、Gradle、离线依赖缓存及 Runtime 构建缓存。

仓库里的 `tools/dev/install.sh` 如果没有 Artifact payload，会直接拒绝安装。这是预期行为，用于防止退回外部下载路径。

默认目录：

```text
工具根目录       ~/.local/share/777-dev
JDK              ~/.local/share/777-dev/jdk
Android SDK      ~/.local/share/777-dev/android-sdk
Gradle           ~/.local/share/777-dev/gradle
Gradle 离线缓存  ~/.local/share/777-dev/gradle-user-home
环境文件         ~/.config/777/dev-toolchain.env
```

需要新终端自动加载时，可显式增加：

```sh
bash install.sh build --configure-shell
```

## AI / Agent 推荐路径

AI、Codex、自动化 Agent 先下载对应 Artifact，再从解压后的产物目录执行：

```sh
bash tools/dev/ai-toolchain.sh bootstrap
```

完整环境：

```sh
bash tools/dev/ai-toolchain.sh bootstrap --profile full
```

常用命令：

```sh
# 只检查当前环境
bash tools/dev/ai-toolchain.sh check

# 最近一次机器可读状态
bash tools/dev/ai-toolchain.sh status

# 固定使用产物内 Gradle，并强制 --offline
bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug

# 在已安装环境中执行命令
bash tools/dev/ai-toolchain.sh run -- git status
```

当环境未就绪且当前脚本目录没有 Artifact payload 时，`bootstrap` 返回 `artifact_required`，不会尝试其他下载渠道。

状态写入仓库本地：

```text
.777/toolchain/status.json
```

该目录已忽略，不进入 Git。

可覆盖目录：

```text
DEV777_TOOLS_ROOT
DEV777_ANDROID_SDK_ROOT
DEV777_GRADLE_HOME
DEV777_GRADLE_USER_HOME
DEV777_TOOLCHAIN_ENV_FILE
DEV777_TOOLCHAIN_STATE_DIR
DEV777_REPO_ROOT
```

状态示例：

```json
{
  "schema": 2,
  "status": "ready",
  "reason": "environment_ready",
  "profile": "build",
  "exit_code": 0,
  "repo_root": "/workspace/777",
  "env_file": "/home/user/.config/777/dev-toolchain.env",
  "sdk_root": "/home/user/.local/share/777-dev/android-sdk",
  "tools_root": "/home/user/.local/share/777-dev",
  "gradle_home": "/home/user/.local/share/777-dev/gradle",
  "gradle_user_home": "/home/user/.local/share/777-dev/gradle-user-home"
}
```

主要状态：

- `ready`：当前档位可直接使用。
- `needs_bootstrap`：当前环境缺项。
- `error / artifact_required`：必须下载 GitHub Actions 工具链 Artifact。
- `error / artifact_install_failed`：Artifact 校验、架构、宿主命令或安装过程失败。
- `error / post_check_failed`：产物安装完成后复检仍有缺项。
- `unknown`：尚未执行检查。

## 宿主基础命令

Artifact 负责项目开发工具与编译依赖。Linux 宿主仍需具备运行脚本与 Runtime 处理所需的基础命令：

```text
bash >= 4
python3
dpkg-deb
readelf
sha256sum
tar
gzip
git
find
awk
sed
grep
head
tr
cp
rm
mkdir
```

这些基础命令缺失时，安装器会明确报错并停止，不会调用系统包管理器补装。

## 检查

安装完成后可直接检查：

```sh
bash tools/dev/setup-toolchain.sh --check --profile build
```

完整环境：

```sh
bash tools/dev/setup-toolchain.sh --check --profile full
```

`setup-toolchain.sh` 当前只负责检查与版本基线自检。旧的 `--auto`、`--accept-android-licenses`、`--skip-system-packages` 等联网安装参数已经禁用。

## 离线构建

推荐始终通过 AI 工具链入口执行 Gradle：

```sh
bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug
bash tools/dev/ai-toolchain.sh gradle :app:assembleOptimized
```

该入口调用 Artifact 内的 Gradle 9.6.0，并固定附加 `--offline`。

安装后也可手工：

```sh
source "$HOME/.config/777/dev-toolchain.env"
gradle --offline :app:assembleDebug
```

仓库 Gradle Wrapper 的分发缓存同样包含在 Artifact 中，兼容已有 Wrapper 流程；自动化路径仍优先使用上面的离线入口。

## 工作流如何生成产物

`.github/workflows/dev-toolchain.yml` 在 GitHub Actions 联网环境中完成以下步骤：

```text
锁定版本
→ 准备 JDK / Android SDK
→ 预热 Gradle、Kotlin/AGP、单元测试与 Runtime 依赖
→ full 档补齐 Node / actionlint / Emulator / Android 16/17 image
→ 组装 payload
→ 生成 SHA256SUMS
→ 上传 build / full Artifact
```

外部下载只发生在 GitHub Actions 的 Artifact 生成阶段。本地安装阶段只消费已经生成并校验的产物。

工具链相关 PR 会对 `build` 与 `full` 两套产物分别执行：

```text
下载当前工作流刚生成的 Artifact
→ SHA-256 校验
→ 从 payload 安装
→ JDK / Android SDK / Gradle / 离线缓存复检
→ AI check
→ 使用产物内 Gradle --offline 真实构建 :app:assembleDebug
→ full 额外验证 Node / actionlint / Emulator / Android 16/17 AVD
```

## 平台边界

- Linux x86_64：当前 Artifact 的标准宿主。
- WSL2 Ubuntu x86_64：当前 Artifact 的标准 Windows 开发路径。
- Linux ARM64：当前 GitHub Actions Artifact 尚未提供 ARM64 payload，安装器会按架构拒绝误装。
- Windows 原生：不作为标准构建宿主。
- macOS 原生：当前 Runtime 脚本依赖 GNU/Linux 工具，不作为标准构建宿主。

## 项目验证

工具链就绪后，常用验证统一通过离线 Gradle 入口：

```sh
bash tools/dev/ai-toolchain.sh gradle \
  :core:test \
  :harness-core:test \
  :harness-runtime-android:test \
  :harness-interop:test \
  :harness-device-android:testDebugUnitTest \
  :mock-harness:test \
  :app:testDebugUnitTest

bash tools/dev/ai-toolchain.sh gradle :reference-validation:test
bash tools/dev/ai-toolchain.sh gradle :app:lintDebug
bash tools/dev/ai-toolchain.sh gradle :app:assembleOptimized
```

最终放行规则仍以 [VALIDATION.md](VALIDATION.md) 和当前 `.github/workflows/ci.yml` 为准。
