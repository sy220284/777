# 777 全量代码静态扫描与跨模块防御性审查｜2026-10-20

## 基准与完成证据

- 本次审计冻结的主线 SHA：`e82b1b98f2adfa3ac39e532411cbe0778cf0b911`。
- GitHub 递归 Git tree 非截断；**Kotlin 1,455 份，其中生产 979、单测 435、AndroidTest 41**。逐文件清单、Git blob SHA 见 [Kotlin manifest](./2026-10-20-777-kotlin-source-manifest.json)。
- 最新主线 CI `39374410012`：`Kotlin risk-pattern guard passed (979 production Kotlin files checked)`、`[test-quality] OK: 476 test source files checked`，unit tests、Android 16/17 instrumented、ARM64 build 和 merge gate 全绿。
- 前序对主线 Kotlin 已顺序读取/模式筛查至第 892 份，本次通过 GitHub `fetch_blob` 补齐最后第 893–979 份，无读取失败。**逐文件静态筛查 ≠ 逐行人工语义审计 ≠ 全部跨模块路径动态验证。**本报告不虚构该部分证据。
- 审计关注异常吞没、取消传播、跨 Feature 状态所有权、持久提交时机、备份恢复、工具权限、安全边界和 UI 投影；任何 `catch`、`getOrNull` 命中必须结合上下文判定，不能把安全解析或清理时兜底直接判为漏洞。

## 已证实缺陷

| ID | 领域/实际路径 | 根因与影响 | 状态 |
| --- | --- | --- | --- |
| F-01 | `LocalSkillManager.kt` → `LocalWorkspace.kt` → 内置规则刷新 | 将 UI 的 24,000 字节编辑上限误当作 128 KiB 技能文件的用户所有权限制，覆盖合法长文本自定义规则 | 已在 [PR #776](https://github.com/sy220284/777/pull/776) RED→GREEN 并合并 |
| F-02 | `SettingsUiPort.configureRuntimeLimits` → `LocalSettingsRuntime` → `LocalHarnessSettingsCoordinator` → `LocalModelExecutionSettings` → `LocalRuntimeProjection` | 一次用户保存四项运行参数拆成两次 `SharedPreferences.Editor.apply`，先发内存状态，再异步写第二部分；进程退出、后次持久化失败可能导致状态/磁盘配置代际分叉 | 修复在 [PR #780](https://github.com/sy220284/777/pull/780)；单次 Editor `commit()` 并检查结果，再一次发布四项状态；旧实现的负向回归已证明失败，新提交 CI 待最终确认 |

## 需要故障注入/复现的候选风险

| ID | 链路与证据 | 验收所缺证据 |
| --- | --- | --- |
| P-01 | `LocalSessionLifecycleCoordinator.deleteSessionIds` → `LocalSessionCoordinator.delete` → `LocalSessionRepository.delete` → `VersionedSessionStore.delete`。Repository 在触盘之前置 `deletedIds` tombstone，并删除 pending/cache；当删除因 IO、catalog 失败抛错时，看不到同函数中恢复 tombstone 的逻辑。 | 故障注入模拟 primary/backup 删除失败，验证剩余可恢复数据在**当前进程**仍可读、重试删除无旧写入复活、重启表现一致，再设计针对性修复。不能先武断清除 tombstone 造成旧任务复活。 |
| P-02 | `OnDemandLanguageServerManager` 等组件安装、更新回退中使用 `renameTo` 和目录清理。 | 模拟 staged 目录 rename 失败、掉电/空间不足，检查保留旧组件或自我恢复；需查组件可再下载和用户数据是否同树。 |
| P-03 | `HarnessJobManager` 的启动前持久化、终态重试和通知投影分离。存储失败时多处回滚，但 `publish` 内部吞掉派生快照通知异常。 | 注入反复失败与取消同时到达，核对外部副作用未知时不会自动重放、队列 slot 最终释放、UI 不显示虚假完成。 |

## 符合合理防御性设计、当前无已证实漏洞的已核查路径

| 领域 | 正向代码证据 | 结论边界 |
| --- | --- | --- |
| 工作区文件 | `LocalWorkspace` 按真实路径/观察指纹做读写闸门；编辑必须先读取，写入使用有界长度与原子提交 | 静态设计合理；未做并发符号链接 TOCTOU 红队验证 |
| 模型配置 | `LocalModelProfileStore.commitConfiguration` 使用 `commit()` 检查及回滚；`LocalModelConfigurationTransaction` 通过 journal/恢复区分提交状态 | 模型配置内部路径有保护，不能替代 F-02 的设置组合边界 |
| 技能文件 | `LocalSkillStore` 用户内容保留与升级基线、备份逻辑；长文本阈值修复已合并 | 已有专项回归，其他工作区外部损坏仍需设备级用例 |
| 更新安装缓存 | `UpdateCache` handoff 标记原子替换、过期保护和损坏标记防误删；安装前另有签名、包名/哈希验证链 | 保护有正向代码与回归；未做真实系统安装器异常恢复场景 |
| 后台任务 | `HarnessJobManager.startInternal` 先持久保留任务再启动，terminal snapshot 写入重试 | 核心控制逻辑有防护；保留 P-03 的时序注入验收 |
| 会话恢复 | `VersionedSessionStore` 保留 backup、未来版本拒绝降级、catalog 对账；有当前主线单测 | 恢复基本路径合理，不能因此忽略 P-01 异常删除边界 |

## 严格完成定义

**已完成**：以不可变 Git 树枚举全体 Kotlin；生产 979/979 静态检查；主线 CI 和 476 测试源质量门禁；此轮最后 87 份的 GitHub 内容读取和风险模式筛查；上表跨层调用链定位；F-01 既有修复及 F-02 独立修复 PR。

**尚未证明**：1,455 份全部人工逐行语义验证、每个函数的所有运行路径、P-01/P-02/P-03 的真实故障注入、#780 最新 head 的全部 CI 通过。禁止把静态扫描覆盖率说成所有潜在隐藏问题均已排除。

如主线继续推进，应按新 `main` 与 `e82b1b98f2adfa3ac39e532411cbe0778cf0b911` 的差异追加审查、重新运行门禁，防止基准漂移。
