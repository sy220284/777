# DSH Go 与 777 的运行时持久化对照（2026-11-13）

## 结论

777 已有会话与任务的持久化体系，适合保留。DSH Go 更值得借鉴的是**启动失败后自动进入可用的安全模式**、**健康运行后保存上次可用配置**以及**运行环境更新时保留用户目录**。这些是运行时故障恢复，不应将进程常驻和数据持久化混为一谈。

## DSH Go 源码事实

1. **进程归属**：`RuntimeController` 作为单进程控制器管理 DSH 进程、故障原因及安全模式；Flutter 与 `DshService` 均使用该控制器。进程数据仅在应用内存中保存，安全模式、连续故障数和最近失败信息保存在 `SharedPreferences`。来源：[RuntimeController.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/RuntimeController.kt)。
2. **前台保活**：`DshService` 显示持续通知并按需持有 partial wakelock；`onStartCommand` 返回 `START_NOT_STICKY`。它不会仅凭系统重建服务就自动恢复先前进程。来源：[DshService.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/DshService.kt)。
3. **受控重启**：正常运行超过一分钟的被外界终止，最多尝试 3 次，延迟为 5 / 15 / 45 秒，窗口为 10 分钟；连续两次故障可进入安全模式。安全模式使用独立救援配置，不覆盖主配置。来源：[SafeMode.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/SafeMode.kt)。
4. **上次可用配置**：正常模式持续运行 2 分钟后，将本次启动时使用的插件组合与配置写到 `filesDir/runtime/lkg/lkg.json`；恢复时仅调整插件启用状态和设置层，不擅自安装或降级依赖。来源：[LastKnownGood.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/LastKnownGood.kt)。
5. **运行环境安装与升级**：应用私有存储中的 `runtime/rootfs` 为运行环境；新版先进入 staging，再通过重命名切换，使用 `home.carry` 保留用户的 `/root` 目录；中断后可按标记恢复。来源：[RuntimeInstaller.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/RuntimeInstaller.kt)。
6. **恢复写入保护**：恢复配置前核查旧进程是否退出，未清理的进程阻止覆盖配置，避免旧、新运行时同时写同一目录。来源：[RuntimeRecoveryGate.kt](https://github.com/ccch1mneyyy/dsh-Go/blob/main/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/RuntimeRecoveryGate.kt)。

## 777 现状

1. **会话事实持久化**：SQLite WAL 的事件日志与序列号同事务写入，支持跨进程恢复及重建索引。来源：[SqliteSessionEventDatabase.kt](https://github.com/sy220284/777/blob/main/app/src/main/java/com/labteto/dshmobile/local/session/SqliteSessionEventDatabase.kt)。
2. **任务持久化**：`LocalPersistentJobStore` 将恢复元数据先写入，再启动任务；同步写入并保留上一有效快照；`LocalJobManager` 在启动时加载历史任务。来源：[LocalPersistentJobStore.kt](https://github.com/sy220284/777/blob/main/app/src/main/java/com/labteto/dshmobile/local/jobs/LocalPersistentJobStore.kt) 和 [LocalJobManager.kt](https://github.com/sy220284/777/blob/main/app/src/main/java/com/labteto/dshmobile/local/jobs/LocalJobManager.kt)。
3. **前台执行归属**：`LocalExecutionService` 获得真正的 foreground service 权限后才准入执行，持有唤醒锁；存在活动执行时返回 `START_STICKY`，空闲或失败时 `START_NOT_STICKY`。服务本身不拥有真实执行。来源：[LocalExecutionService.kt](https://github.com/sy220284/777/blob/main/app/src/main/java/com/labteto/dshmobile/local/runtime/LocalExecutionService.kt)。
4. **运行时初始化**：`LocalRuntimeKernel` 统一负责启动一次、恢复协调及启动失败投影，业务状态归各自模块；不应把全部恢复规则堆进 Kernel。来源：[LocalRuntimeKernel.kt](https://github.com/sy220284/777/blob/main/app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeKernel.kt)。
5. **可靠提交**：`DurableFileCommit` 采用临时文件、原子替换、目录同步及备份，保护断电或进程崩溃时的文件状态。来源：[DurableFileCommit.kt](https://github.com/sy220284/777/blob/main/app/src/main/java/com/labteto/dshmobile/local/persistence/DurableFileCommit.kt)。

## 后续建议（仅做审计，没有改动运行时逻辑）

- **保留 777 现有 WAL 与任务快照方案**。这是已具备的可靠数据保存路径。
- **优先补充可用配置回退**：面向可导致核心启动失败的插件/模型路由与启动参数，只在已通过健康检测后保存配置快照，故障时提供可核实的恢复建议。
- **再补充受限安全启动模式**：连续启动失败时以最小依赖启动诊断页面，保护用户会话和本地文件；从安全模式返回时先试运行再确认健康。
- **持续用进程身份、执行准入与持久快照协同验证**；不自动重放具有副作用的工具操作。

本报告仅比较已核对的源码路径。DSH Go 的重启策略和 Android 厂商后台限制不是“永久保活”承诺。\n
## 权限引导改造验证附注

PR #897 增加首次启动及设置页共用的权限引导，保留授权、服务连接和访问设置页等独立状态；已有 Kotlin 与 Compose 测试。

## 权限向导最终复核项目

设备权限由用户按需逐项授权；访问设置页、系统授权与服务连接分别显示。缺失授权时有明确反馈和修复入口。

## 编译复核记录

Android Instrumentation Kotlin 编译中发现新测试导入错误；已移除无效导入，改为 Compose SemanticsNodeInteraction 自带的 assertExists() 调用。后续以最新分支 CI 验证为准。
