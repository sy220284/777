# 国内更新镜像

777 的正式版本仍以 GitHub Release 为发布源，同时支持把相同的 APK、校验文件、增量包和更新清单同步到阿里云 OSS 公网 Bucket Endpoint。

这条链路不需要购买域名、不需要接入 CDN，也不需要给自定义域名备案。

## 运行方式

发布链：

```text
main 通过 CI
→ 构建正式签名 APK
→ 生成完整包 / 增量包 / SHA256SUMS / update-manifest
→ 发布 GitHub Release
→ 同步全部版本资产到 OSS
→ 公网 HEAD 回读校验版本资产
→ 最后更新 latest.json
→ 公网 GET 回读校验 latest.json
```

客户端：

```text
手动检查更新
→ 已配置 OSS 镜像：先读取 <OSS 公网地址>/latest.json
→ 镜像有效：只使用镜像，不访问 GitHub
→ 镜像连接失败 / 元数据无效：回退 GitHub Release
→ 下载 APK 或增量包
→ SHA-256
→ 包名
→ 签名证书
→ versionCode
→ Android 系统安装器
```

镜像可达且内容有效时，国内用户的检查、增量包下载和完整 APK 下载都不依赖 GitHub。

## OSS 要求

建议单独创建一个只用于 777 更新分发的 Bucket，并选择中国大陆地域。

直接使用 OSS 自动分配的公网 Bucket Endpoint，例如：

```text
https://your-777-update.oss-cn-hangzhou.aliyuncs.com/777/
```

不要使用：

- `*-internal.aliyuncs.com` 内网 Endpoint。
- HTTP 地址。
- 自定义域名。
- CDN 地址。

Bucket 中只有公开发行资产，不应放模型密钥、账号数据、日志、用户文件或其他私有内容。

为了让客户端可以直接下载，Bucket 需要允许这些发行对象被匿名读取。建议使用独立 Bucket，将公开读取范围与其他业务数据彻底隔离。

## GitHub 仓库配置

Repository → Settings → Secrets and variables → Actions。

增加一个 Repository variable：

```text
UPDATE_MIRROR_BASE_URL
```

值为 OSS 公网 Bucket Endpoint，并带独立前缀，例如：

```text
https://your-777-update.oss-cn-hangzhou.aliyuncs.com/777/
```

增加两个 Repository secrets：

```text
UPDATE_OSS_ACCESS_KEY_ID
UPDATE_OSS_ACCESS_KEY_SECRET
```

上传凭据只存在 GitHub Actions Secret，不会编译进 APK。APK 内只包含公开的 `UPDATE_MIRROR_BASE_URL`。

建议给上传身份使用独立 RAM 用户，只授予这个 Bucket / 前缀的对象上传权限，不授予删除 Bucket、管理账号或其他业务资源的权限。

## 对象布局

发布后对象结构为：

```text
<base>/
├── latest.json
└── releases/
    └── v0.12.0-777.xxx/
        ├── app-release.apk
        ├── SHA256SUMS.txt
        ├── update-manifest.json
        └── delta-....hpatch
```

版本目录对象按不可变资源发布。

`latest.json` 使用禁止缓存策略，并且永远最后写入。这样即使版本资产上传中途失败，旧客户端仍然只会看到上一份完整可用的发布状态。

## 故障行为

OSS 完全未配置时：

```text
构建结果不包含镜像地址
→ 更新行为保持原 GitHub 链
```

OSS 已配置但临时不可达时：

```text
镜像短超时
→ GitHub API / Release 回退
```

OSS 发布失败时：

```text
GitHub Release 已正常发布
→ 镜像步骤标记失败
→ 不切换 latest.json
→ GitHub 发布结果不回滚
```

如果镜像元数据被篡改，客户端仍会拒绝不能通过最终 APK SHA-256、包名、签名证书和版本号校验的更新。

## 首次启用检查

配置完成后，下一次正式 Release 应同时满足：

1. Release workflow 的 `Publish mainland OSS update mirror` 步骤执行成功。
2. `<UPDATE_MIRROR_BASE_URL>/latest.json` 可通过普通公网 HTTPS 读取。
3. `latest.json` 中 APK 的 `path / size / sha256` 与 Release 资产一致。
4. 对应 APK 可匿名下载。
5. 国内普通网络关闭 VPN 后，应用“检查更新”能够发现该版本并进入安装流程。
6. 下载后仍通过 APK 摘要、包名、签名证书和版本号校验。
