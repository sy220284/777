# 2026-10-09 main 合并后回归修复（#577 / #579 / #580）

## 代码与 CI 证据

- 主线 `d574c762` CI [37916834555](https://github.com/sy220284/777/actions/runs/37916834555)：Android 16 共 183 项仪器化测试、2 项失败，均为 `SidebarSystemBackRegressionTest`，`BackHandler` 期望计数 1，观察到 0；同 run Android 17 成功。
- 当前主线 `24d2ca42` CI [37918180041](https://github.com/sy220284/777/actions/runs/37918180041)：此前已有静态/架构/JVM/Relay 成功，Android 设备回归继续以该 run 实际结果为准。
- 主线 `18235fa2` 开发工具链 [37917041018](https://github.com/sy220284/777/actions/runs/37917041018)：`:app:testDebugUnitTest` 到达 Gradle 任务配置的 2 分钟超时；同一 Gradle 调用并行包含 Android lint、APK 和 R8。PR #579 的原始按需工具链构建曾通过，故先隔离资源争用，再核证是否存在真正挂死。
- 工具链代码变更：JVM 单测与 lint / APK / R8 拆为前后两次 Gradle 调用，全部原有任务保留，单测 2 分钟 fail-closed 超时也保留；无额外组件下载源。
- Android 回归变更：保留真实 `input keyevent KEYCODE_BACK` 注入；在两个以 `createComposeRule` 新建界面的测试中，先核查根节点已显示且 Compose idle，再发系统返回，等待真实回调完成后断言**恰好一次**。禁止通过重发按键、改为直接触发 `onBack` 或跳过用例取得假绿。

## 验收

最新 PR HEAD 应通过 `static-gates`、`architecture-3-gates`、全模块 `unit-tests`、`relay-conformance`、ARM64 与 x86 构建、Android 16/17 `instrumented`、`merge-gate`，以及 `dev-toolchain` 的 artifact 生成、JDK 和 build/full 按需安装回归。不得将此前主线的失败或其他 HEAD 的成功视作当前提交的签收。
