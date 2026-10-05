# 本地开发工具链

777 的标准开发宿主是 **Linux / WSL2 Ubuntu x86_64**。

本地工具链执行一条硬规则：

> **开发依赖只允许来自 GitHub Actions 生成的工具链 Artifact。**

这里的“只允许”约束的是依赖来源。本地不会通过 `apt`、Google Android 仓库、Gradle/Maven 在线解析、Node 官网等渠道补齐开发依赖。

项目源码统一使用 Kotlin；构建环境接受 JDK 21+，CI 与兜底 Artifact 固定使用 JDK 21 LTS；项目编译目标固定为 JVM 21。Node 接受 22+，不锁 minor / patch。

## 按需下载模型

工具链不再提供一个必须整体下载的超大 build/full 包。

先检查本机已有环境，再只下载缺失组件：

```text
本机检查
→ 计算缺失组件
→ 下载对应 GitHub Actions Artifact
→ SHA-256 校验
→ 只安装缺失组件
→ 再检查
→ Gradle --offline 真实构建
```

已有且版本满足要求的工具直接复用。例如宿主已经有完整 JDK 21 或更高版本，就不会要求重新下载 JDK Artifact。

AI / Agent 查询缺失项：

```sh
bash tools/dev/ai-toolchain.sh plan --profile build
```

输出示例：

```json
{
  "schema": 1,
  "profile": "build",
  "missing_artifacts": [
    "777-toolchain-android-core-latest",
    "777-toolchain-gradle-runtime-latest",
    "777-toolchain-gradle-deps-part-01-latest",
    "777-toolchain-gradle-deps-part-02-latest",
    "777-toolchain-gradle-deps-part-03-latest",
    "777-toolchain-gradle-deps-part-04-latest",
    "777-toolchain-runtime-cache-latest"
  ]
}
```

`full`：

```sh
bash tools/dev/ai-toolchain.sh plan --profile full
```

## 组件 Artifact

基础开发组件：

| Artifact | 内容 |
|---|---|
| `777-toolchain-jdk-latest` | JDK 21 LTS 兜底包，包含 `java` / `javac`；本机 JDK 21+ 可直接复用 |
| `777-toolchain-android-core-latest` | Android command-line tools、platform-tools、API 37 platform、build-tools 37.0.0、licenses |
| `777-toolchain-gradle-runtime-latest` | Gradle 9.6.0 完整分发 |
| `777-toolchain-gradle-deps-part-01..04-latest` | Gradle Wrapper 分发缓存、Kotlin 2.2.10、AGP 9.4.0 与项目依赖离线缓存；4 个固定分片 |
| `777-toolchain-runtime-cache-latest` | 777 APK Runtime 构建缓存 |

完整验证的扩展组件：

| Artifact | 内容 |
|---|---|
| `777-toolchain-node-latest` | Node.js 22+ |
| `777-toolchain-actionlint-latest` | actionlint 1.7.12 |
| `777-toolchain-emulator-latest` | Android Emulator |
| `777-toolchain-android-image-16-part-01..06-latest` | Android 16 x86_64 system image；6 个固定分片 |
| `777-toolchain-android-image-17-part-01..06-latest` | Android 17 / API 37 16 KiB x86_64 system image；6 个固定分片 |

另有小型入口包：

```text
777-toolchain-bootstrap-latest
```

它只包含当前安装/检查脚本、组件清单和文档，故体积很小。它明确叫 `bootstrap`，不会再与真正包含编译工具的组件产物混淆。

## 下载与安装

先运行 `plan`，然后只下载 `missing_artifacts` 中列出的 GitHub Actions Artifact。

**Artifact 来源必须限定为默认分支 `main` 最新一次成功的“开发工具链”运行。** 不要按名称跨分支挑“最新 Artifact”，也不要使用 PR / 功能分支运行生成的同名产物；否则可能拿到与当前主线安装器不兼容的旧包。

工具链相关文件合并到 `main` 后会自动刷新组件 Artifact；每月定时刷新仍作为保底。

把下载后的 Artifact 分别解压到同一个目录，例如：

```text
/tmp/777-artifacts/
├── 777-toolchain-android-core-latest/
├── 777-toolchain-gradle-runtime-latest/
├── 777-toolchain-gradle-deps-part-01-latest/
├── 777-toolchain-gradle-deps-part-02-latest/
├── 777-toolchain-gradle-deps-part-03-latest/
├── 777-toolchain-gradle-deps-part-04-latest/
└── 777-toolchain-runtime-cache-latest/
```

安装：

```sh
bash tools/dev/ai-toolchain.sh bootstrap \
  --profile build \
  --artifacts-dir /tmp/777-artifacts
```

安装器会再次检查当前机器。下载后如果某个组件已经由其他方式变成满足版本要求，该组件仍会跳过安装。

人工入口也可以使用：

```sh
bash tools/dev/install.sh plan build

bash tools/dev/install.sh install build \
  --artifacts-dir /tmp/777-artifacts
```

完整验证环境：

```sh
bash tools/dev/install.sh install full \
  --artifacts-dir /tmp/777-artifacts \
  --create-avds
```

## 工具来源约束

组件包由 `.github/workflows/dev-toolchain.yml` 在 GitHub Actions 联网环境中生成。

允许联网获取原始依赖的地方只有 Artifact 生成阶段：

```text
GitHub Actions
→ 获取并固定 JDK / Android SDK / Gradle / Node / actionlint
→ 预热 Kotlin / AGP / 项目依赖 / Runtime
→ 拆成组件
→ 每组件生成 SHA256SUMS
→ 分别上传 Artifact
```

外部下载只发生在 GitHub Actions。安装机只消费已经生成的组件 Artifact。

## AI / Agent 常用入口

```sh
# 查看当前缺什么
bash tools/dev/ai-toolchain.sh plan

# 安装已经下载好的缺失组件
bash tools/dev/ai-toolchain.sh bootstrap \
  --artifacts-dir /tmp/777-artifacts

# 检查环境
bash tools/dev/ai-toolchain.sh check

# 查看最近状态
bash tools/dev/ai-toolchain.sh status

# 使用产物内 Gradle，固定离线构建
bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug

# 完整环境缺项
bash tools/dev/ai-toolchain.sh plan --profile full
```

环境目录可以覆盖：

```text
DEV777_TOOLS_ROOT
DEV777_ANDROID_SDK_ROOT
DEV777_GRADLE_HOME
DEV777_GRADLE_USER_HOME
DEV777_TOOLCHAIN_ENV_FILE
DEV777_TOOLCHAIN_STATE_DIR
DEV777_REPO_ROOT
DEV777_ARTIFACTS_DIR
```

默认目录：

```text
工具根目录       ~/.local/share/777-dev
JDK              ~/.local/share/777-dev/jdk
Android SDK      ~/.local/share/777-dev/android-sdk
Gradle           ~/.local/share/777-dev/gradle
Gradle 离线缓存  ~/.local/share/777-dev/gradle-user-home
环境文件         ~/.config/777/dev-toolchain.env
```

## 检查规则

`build` 要求：

- JDK 21 或更高版本，且必须有 `javac`。
- Android command-line tools。
- platform-tools。
- API 37 platform。
- build-tools 37.0.0。
- Gradle 9.6.0。
- Gradle 离线依赖缓存。
- Gradle Wrapper 分发缓存。
- 777 Runtime 构建缓存。

`full` 额外要求：

- Node.js 22+。
- actionlint 1.7.12。
- Android Emulator。
- Android 16 x86_64 system image。
- Android 17 16 KiB x86_64 system image。

KVM 属于宿主虚拟化能力，只能检测，Artifact 无法替宿主开启 BIOS / Hyper-V / WSL 虚拟化。

## 宿主基础命令

Artifact 负责项目开发工具和编译依赖。Linux 宿主仍需要脚本运行所需基础命令：

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

缺失时直接报错，不会调用系统包管理器安装。

## 离线构建

推荐统一：

```sh
bash tools/dev/ai-toolchain.sh gradle :app:assembleDebug
bash tools/dev/ai-toolchain.sh gradle :app:assembleOptimized
```

该入口固定使用安装自 Artifact 的 Gradle，并附加 `--offline`。

## CI 如何证明“真的有产物”

“开发工具链”工作流现在有两层验证。

第一层：每个组件必须真实上传，且设置 `if-no-files-found: error`。

第二层：验证 Job 会重新从 GitHub Actions 下载刚刚生成的 Artifact：

```text
bootstrap
→ 先 plan
→ 证明已有 JDK 21+ 时不会要求下载 JDK
→ 只下载 build 当前缺的 Android / Gradle / Runtime 组件
→ 安装
→ plan 必须变成 missing_artifacts=[]
→ Gradle --offline assembleDebug

然后：
→ 单独下载 JDK Artifact
→ SHA-256 校验
→ 解包并真实执行 javac

再：
→ plan full
→ 下载 full 扩展组件
→ 安装 / 创建 Android 16、17 AVD
→ plan full 必须清零
→ Gradle --offline assembleOptimized
```

因此工作流全绿的前提包含：

- Artifact 实际存在。
- Artifact 能从 Actions 再下载。
- 包内不是空壳。
- SHA-256 正确。
- 工具可执行。
- 按需安装逻辑正确。
- 最终能完成真实离线构建。

组件 Artifact 保留 30 天。工具链相关文件每次合并到 `main` 都会自动重新生成全部组件；默认分支每月 **1 日和 20 日**额外定时刷新，防止长期无变更时 Artifact 过期。

最终放行规则仍以 [VALIDATION.md](VALIDATION.md) 和当前 `.github/workflows/ci.yml` 为准。
