# 777 合并后 Android 验收闭环（2026-10-24）

## 来源与确定事实
- 原集成基线 `main d2725ad5`，与 #850 最终树一致。常规 CI [40063098938](https://github.com/sy220284/777/actions/runs/40063098938) 通过，包括 Android16/17 仪器测试。
- [#850 4 档矩阵 40063098891](https://github.com/sy220284/777/actions/runs/40063098891)：Android16/4KB、Android17/4KB、Android17/16KB 完成；Android16/16KB 首轮 Work 事件冷恢复指标尚未打印即被 LMKD 终止。
- `ApplicationExitInfo`：`reason=3 LOW_MEMORY, rss=396MB`；系统 logcat 同一时间段有 70 余条 `lowmemorykiller: Kill`，包括 GMS、权限系统服务和最终前台目标应用，原因均为低于 `min watermark`。这是**客体系统内存水位不足**的直接证据；不能据此判定 App 内存泄漏，也不能忽略前台进程被杀的问题。
- 原独立矩阵使用 `-memory 3072` 和刚启动的 GMS 镜像；原始产物 artifact `12548204095`。Android17 与 Android16/4KB 同一 APK 通过。

## 修复策略及风险控制

| 场景 | 代码/测试措施 | CI 准入 | 仍未覆盖 |
|---|---|---|---|
| Android16/16KB 低内存 | 四档统一 4096MB，16KB 等待开机广播稳定；输出 `/proc/meminfo`、失败时 `dumpsys meminfo`、logcat 和 exit-info；仍然失败即红灯 | 4 档必须各自 `CELL_COMPLETE`，含 10 轮且同宿主/同 APK | 低内存真机、并发后台服务 |
| 长历史性能 | 继续使用 2000 Work 事件（冷 20 / 增量 30）与 10000 工具行；每次报告真实 PSS/Java heap 快照；10 轮 CSV 和 P50/P95/内存汇总不得缺指标 | 10 轮全成功、指标完整，不得用缺失帧样本伪造 FPS | 更多事件、数小时稳定运行、长期 RSS 趋势 |
| 音频 A→B→A | 文件粒度 MediaPlayer Owner，旧 Owner 释放、异步回调所有权栅栏；AndroidTest 用真实 MediaPlayer + 两个 WAV 样本测 A→B→A、跨 Owner 释放和迟到回调 | Android16/17 `InlineSpeechPlayerOwnerAndroidTest` 通过；Compose UI 生命周期仍做专项回归 | 真实扬声器/蓝牙/前后台音频焦点 |
| 历史安装覆盖 | `verify-legacy-apk-upgrade.sh` 要求不同签名字节的旧/新**同签名** Debug APK，检查 package/version，执行 `adb install -r` + 启动，核对私有数据哨兵保留。仓库原有旧 JSON/JSONL 和旧 ZIP 转换测试继续确保语义保真 | 必须在全新 AVD 使用**历史版本 APK**和当前候选 APK 真正执行，记录 `UPGRADE_OK` | 已发布正式签名旧 APK → 正式签名新 APK，需要同一密钥和真实历史数据 |
| 跨进程中断恢复 | 原有 `SqliteStorageArchiveAndroidTest` 中断恢复、`SqliteSessionSafetyBoundariesAndroidTest` 删除中断和 MCP worker 真实 `killProcess` 均继续执行；新增覆盖升级脚本进行真实 `force-stop`/重启 | 既有 Android16/17 仪器断言全绿，恢复不可覆盖用户现存资料、不可重放未知副作用 | 在 DB 原子替换确切写入窗口杀 App 主进程 |
| 第三方 MCP | 新增 Android 内真实 loopback TCP/HTTP MCP peer，通过分离的 `:mcp_worker` Binder 工作进程进行 `tools/list` 与 `tools/call`，统计每个只调用一次；原 OAuth 仿真、worker death 测试仍执行 | Android16/17 MCP loopback 与 worker kill 测试通过，不自动重试写操作 | 公网真实服务/OAuth/账号、TLS 网络抖动 |

## 合并规则
- 本修复在 [PR #852](https://github.com/sy220284/777/pull/852) 的独立分支工作；保持 Draft 直到新 HEAD 的 JVM、ARM64、Android16/17 和 Android 4 档结果完成。
- 4 档任一环境低内存杀死应用、未输出完整指标或异常取消，均不得以其它三个环境通过替代。
- 已通过的模拟器协议仿真不能冒充真实第三方公网 MCP；真实音频 Owner 测试不能冒充硬件输出质量验证；保持剩余真机验收独立状态。
