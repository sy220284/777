# 国内更新镜像

777 的正式版本继续发布到 GitHub Release，同时可以把同一套 APK、增量包、校验文件和更新清单同步到 Gitee Release。

这条链路直接使用 `gitee.com` 平台域名，不需要购买域名、不需要接入 CDN，也不需要为自定义域名备案。

## 为什么使用 Gitee Release

2025—2026 年国内多家对象存储陆续收紧默认公网域名策略，新建 Bucket 使用默认域名分发 APK 往往要求绑定自定义域名，和本项目“不买域名、不上 CDN、不备案”的目标冲突。

Gitee Release 本身提供：

- `/releases/latest` 最新发行版入口。
- `/releases/download/<tag>/<filename>` 固定附件下载路径。
- Release 附件 API，可由 GitHub Actions 自动同步。
- 国内可直接访问的平台域名。

当前 777 正式 APK 体积受 CI 90 MiB 预算约束，低于 Gitee Release 单个附件 100 MB 的镜像预算。发布脚本也会再次检查大小，防止未来体积回归后静默生成不可用镜像。

## 运行方式

发布链：

```text
main 通过 CI
→ 构建正式签名 APK
→ 生成完整包 / 增量包 / SHA256SUMS / update-manifest
→ 创建或复用同版本 Gitee Release
→ 上传 APK / 增量包 / 校验文件
→ 公网回读并做 SHA-256 + 大小校验
→ 最后上传 update-manifest.json
→ 确认 Gitee latest 已指向本版
→ 发布 GitHub Release
```

`update-manifest.json` 最后上传。发布未完成时，客户端读不到有效清单，会把 Gitee 视为临时不可用并回退 GitHub，不会拿到半套更新资产。

客户端：

```text
手动检查更新
→ 已配置 Gitee 镜像：先读取 Gitee 最新正式版
→ 新版存在：读取该 Release 的 update-manifest.json
→ 构造同 Release 下 APK / 增量包地址
→ Gitee 元数据有效：本次更新不依赖 GitHub
→ Gitee 不可达 / 清单缺失 / 清单非法：回退 GitHub
→ 下载 APK 或增量包
→ SHA-256
→ 包名
→ 签名证书
→ versionCode
→ Android 系统安装器
```

## 首次准备 Gitee 镜像仓库

1. 在 Gitee 创建一个公开仓库，例如 `777-update`。
2. 仓库只承担发行版镜像用途即可，但必须至少有一个默认分支和一次初始提交。
3. 在 Gitee 创建个人访问令牌，令牌只授予该镜像仓库发布 Release 所需权限。
4. 不要把令牌提交进代码。

镜像仓库可以与 GitHub 源码仓库分开。客户端只读取它的公开 Release，不需要 Gitee 账号或令牌。

## GitHub 仓库配置

进入 GitHub Repository → Settings → Secrets and variables → Actions。

增加一个 Repository variable：

```text
UPDATE_MIRROR_BASE_URL
```

值必须精确为公开 Gitee 镜像仓库根地址：

```text
https://gitee.com/<owner>/<repo>/
```

例如：

```text
https://gitee.com/example/777-update/
```

增加一个 Repository secret：

```text
GITEE_MIRROR_ACCESS_TOKEN
```

令牌只在 GitHub Actions 发布阶段使用，不会进入 APK。APK 里只包含公开的 Gitee 仓库地址。

## 未配置时的行为

如果 `UPDATE_MIRROR_BASE_URL` 和 `GITEE_MIRROR_ACCESS_TOKEN` 都没有配置：

```text
Release workflow 跳过 Gitee 镜像
→ APK 不写入镜像地址
→ 更新功能保持现有 GitHub Release 链
```

如果只配置其中一项，发布流程直接失败，避免产出“客户端已经指向 Gitee、发布端却没有同步权限”的半配置版本。

## 镜像一致性

启用镜像后，Gitee 发布步骤位于 GitHub Release 之前，并且不是可忽略失败。

因此：

```text
Gitee 成功
→ 才继续 GitHub Release

Gitee 失败
→ 本次正式发布停止
→ 不产生 GitHub 新正式版
```

这样 Gitee 不会长期落后于 GitHub 正式版。

同一个 tag 的附件视为不可变。如果 Gitee 已存在同名附件，发布脚本会从公开下载地址重新读取并比较大小和 SHA-256：

- 完全一致：复用。
- 内容不同：停止发布，不覆盖同版本资产。

## 客户端安全边界

镜像地址必须满足：

- HTTPS。
- Host 固定为 `gitee.com`。
- 路径精确为两个仓库段：`/<owner>/<repo>/`。
- APK、增量包名称只允许安全文件名，不接受绝对 URL、路径穿越或任意跨域地址。

无论资产来自 Gitee 还是 GitHub，安装前都继续执行：

1. 下载完整性 / 预期长度。
2. SHA-256。
3. Android 包名。
4. APK 签名证书与当前安装版本一致。
5. 目标 `versionCode` 必须更高。

镜像改变的是网络分发路径，不降低 APK 信任边界。

## 首次启用验收

配置后下一次正式 Release 至少检查：

1. Gitee Release 与 GitHub Release tag 一致。
2. Gitee 中存在 `app-release.apk`、`update-manifest.json`、`SHA256SUMS.txt`，有增量包时也存在对应 `.hpatch`。
3. Gitee APK 公网下载大小和 SHA-256 与本地构建产物一致。
4. 关闭 VPN 后，应用“检查更新”能够发现新版本。
5. 更新下载地址来自 `gitee.com`。
6. APK 仍通过摘要、包名、签名证书和版本号校验。
7. 临时让 Gitee 请求失败时，客户端仍能进入 GitHub 回退链。
