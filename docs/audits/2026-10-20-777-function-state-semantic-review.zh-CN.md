# 777 函数及状态转移人工语义复核台账｜2026-10-20

> **审计基准**：main `3bab047d86a450757bb960b1d150b17118ca116a`；并列审查尚未合并的 [#782](https://github.com/sy220284/777/pull/782) 头部 `ad5aa3f0639f42c3d939f78bdb53f506048ec014`。此台账记录**实际读过的函数与关键分支**，不以全局静态检索、CI 全绿或查看类名冒充所有源码的人工逐行审查。
>
> **覆盖边界**：仓库现有 [979 份生产 Kotlin + 476 份测试源的静态门禁](./2026-10-20-777-full-chain-audit.zh-CN.md) 与 [全体 Kotlin blob 清单](./2026-10-20-777-kotlin-source-manifest.json) 是基线；该清单不是“所有函数已人工验收”的证明。本台账只记录下表中有完整调用链依据的语义结论；未列出的函数、异常输入/设备竞争时序仍未宣称通过。

## A. 会话写入、删除及恢复（已按函数和上下游检查）

| 文件/函数与范围 | 正常→失败/取消→恢复语义 | 本次判断 |
| --- | --- | --- |
| `LocalSessionRepository.enqueue` 119–126 | 最新接受快照进入 in-memory/pending；删除 tombstone 阻止新入队 | 当前设计合理；关注删除边界后的迟到写入 |
| `LocalSessionRepository.writeNow` 134–189 | 加锁持久写→更新 index/projection；提交失败重新恢复旧 pending；磁盘写成功但派生通知异常需另行分界 | 主要前置/失败转移已复核；非确定性 callback 异常待对抗测试 |
| `LocalSessionRepository.delete` 192–206；#782 改动 | 先插入 tombstone→移除 pending/cache→删除磁盘→删除摘要；#782 仅当文件仍存在时恢复 tombstone 和原待写快照 | **原缺陷 P-01 经故障注入；底层修复仍不等于整条链闭环** |
| `LocalSessionRepository.releaseDeletionBarrier` 213–221 | 删除阶段结束并确认无 pending/latest 后才解除进程 tombstone | 正向所有权设计合理；失败路径不要无条件调用 |
| `LocalSessionRepository.readWithLegacyApproval` 226–246 | 读取最新已接受快照或磁盘并保留历史审批语义 | 审批不得从 pending 快照随意推断；损坏持久记录时显示行为仍需注入 |
| `LocalSessionRepository.summaries/loadSummaries` 248–318 | index 命中与降级扫描；损坏跳过且报告，不自动删原记录 | 分支有显式日志；与 UI 状态对账仍需验证 |
| `VersionedSessionStore.read` 85–116 | primary→backup 救援；未来版本拒绝降级；恢复后 adopt catalog | 恢复优先级合理 |
| `VersionedSessionStore.delete` 158–175；#782 `hasRecoverableSnapshot` | 主备删除成功后 unregister；删除失败仍有文件时不能当作已删除；#782 以文件存在性为可恢复近似 | 核查“存在但内容不可读”的故障边界；状态恢复不能直接推断为业务成功 |
| **`LocalSessionLifecycleCoordinator.deleteSessionIds` 361–465** | **390–429 先激活空白新 session；433–445 才删磁盘；失败 finally 只清 loading，没有恢复/对账已有会话和取消任务** | **新增重要缺陷：#782 上层业务删除仍半提交**；已在 #782 留审查意见 |
| `LocalSessionStorageRuntime.publishSummaries` 132–141 | 写盘后从 repository 读取并投影 summary，错误报告到 UI | 关键消费端已核对，不应将 index 成功等同存储成功 |

## B. 持久后台任务及外部副作用（#782 一并审核）

| 函数/代码段 | 状态转移与数据保护判断 | 本次判断 |
| --- | --- | --- |
| `HarnessJobManager.startPersistentResult/startInternal` 209–232、454–535 | allocate ID→RUNNING 内存→持久 metadata 成功后启动；预提交失败不 launch | 合理 fail-closed |
| `HarnessJobManager.resumePersistent` 234–289 | interrupted/waiting_slice/dormant→RUNNING；要求 owner/slot；提交失败回滚 prior record | 正向保护合理，但与新配置/迟到结果并发仍需复现 |
| `HarnessJobManager.launchRecord` 571–653 | RUNNING→COMPLETED/DORMANT/FAILED/CANCELLED/INTERRUPTED/WAITING_SLICE，最后进行终态落盘 | **进度 report() 587–595 不检查 RUNNING 或当前 owner，可于终态后覆盖 output**；已在 #782 留审查意见 |
| `HarnessJobManager.interruptContinuableAgent(s)` 683–814 | 先提交中断状态，再真正 cancel job；失败恢复旧状态 | 目标语义合理；需验证 cancel 与迟到 callback 重叠 |
| `HarnessJobManager.kill` 816–868 | dormant persistent 先提交取消；running 取消并发布；资源释放依赖协程落地 | 需补运行中外部操作已发生、取消回执先行场景 |
| `HarnessJobManager.sendInput/acknowledgeMessages` 884–1029 | 同步 publicationLock + lock 并写 inbox；持久 writer 失败回滚消息/时间戳 | 正向防重复与持久 ack 设计合理 |
| `HarnessJobManager.removeOwnedAndJoin` 1073–1090 | 会话删除前移除 owned jobs 并 join；即使磁盘会话删除失败，取消不可逆 | 与上层 P-01 共同构成部分执行后失败 |
| `HarnessJobManager.persistTerminalState` 1148–1173，#782 改动 1183–1210 | 原失败易冒充业务 FAILED；#782 重试耗尽后保守置 INTERRUPTED/未知 | 已证实 P-03 根因修复方向合理；注意 UI 结果/持久代际仍可能不同 |
| `HarnessJobManager.markRecoveryUnverifiable`（#782，427–455） | unknown resumeKind 后保留 INTERRUPTED 和 output；写入失败回滚 | “未知不等于 FAILED”合理；仍须验证 replay/inbox 与 owner 代际 |
| `LocalPersistentJobRecoveryCoordinator.resumePass`（#782 改动 265–299） | 按类型区分 read-only 可恢复、未来协议不可自动重放 | 正向 fail-closed；冷启动 owner/unknown 路径需对应测试 |

## C. Settings → Agent/Model 配置（已阅读持久事务相关函数）

| 函数/范围 | 成功与失败转移 | 判断 |
| --- | --- | --- |
| `LocalHarnessSettingsCoordinator.configureRuntimeLimits` 89–134 | 四键同一 Editor.commit→失败快照回滚→成功后一次发布 StateFlow | #780 修复已在 main；静态语义正确且关联 main CI 绿 |
| `LocalAgentRuntimeSettings.prepare/stage/snapshotForRollback/stageRollback` 33–56 | 校验与持久键所有权留在 Agent domain | 合理 |
| `LocalModelExecutionSettings.prepare/stage/snapshotForRollback/stageRollback` 59–100 | Worker profile 参照真实可用配置；null 删除旧键 | 合理；状态读取快照变更需与配置写入序列化 |
| `LocalRuntimeProjection.updateSettingsRuntimeLimits` 198–214 | 一次 copy 四项运行快照 | 合理，不能替代事务持久提交 |
| **`LocalHarnessSettingsCoordinator.configurePersonalization` 55–83、137–149** | **只将值投递给 CONFLATED channel，UI 调用立即返回；真正持久写入和 state update 延后** | **已证实异步受理被 UI 当作完成** |
| `SettingsUiPort.submit(Personalization)` 104–120 | 对尚未写盘的请求返回 Applied，而非 Submitted | 同一缺陷源，见 [#783](https://github.com/sy220284/777/issues/783) |
| `SettingsViewModel.configureLocalMemory` 299–301、`MemorySettingsSections` 145–185 | 调用后直接显示“保存成功”并关闭编辑层，无提交 ack | **#783：正常成功提示时机错误，需代际 ack/失败保留草稿** |
| `UserProfileStore.read/write` 34–47、`RecoveringDocumentFile.read/write` 19–73 | 原子写/备份/损坏恢复有独立策略；消费端提前反馈仍不正确 | 持久化底层合理，缺陷在 UI 与异步契约 |
| `LocalModelProfileStore.commitConfiguration/commit` 159–183 | 模型 metadata 同批提交、commit false 回滚配置快照 | 合理 |
| `LocalModelMutationGate.run`（整个函数）+ `LocalModelRuntime.selectModel/removeModel/saveConfiguration/commit` 53–109、170–180 | 模型持久事务加锁，但返回后跨协程 UI 投影不在同一 gate 内；select 私锁未覆盖 remove/save | **[#784](https://github.com/sy220284/777/issues/784) 需要可控并发复现**，避免贸然修改保护顺序 |

## D. Work 目标与等待表单

| 函数 | 状态转换/不变量 | 判断 |
| --- | --- | --- |
| `hasOutstandingWorkReplyForm` 17–30 | form-await 事件直到后续 USER durable send；model/tool 的 autonomous 事件不解锁 | 正向安全设计合理；核验真实 form submission 事件身份 |
| `shouldAutoContinueWorkGoalAfterForm` 33–41 | awaitingSubmission=true 时拒绝 Goal 自动推进 | 设计合理 |
| `decideLocalWorkGoalDrive` 16–36 | STALE/WAITING/VERIFICATION_REQUIRED/COMPLETED/CONTINUE_QUEUED/PARTIAL 的判断链 | 规则有 fail-closed 条件 |
| `driveLocalWorkGoalAfterTurn` 91–157 | 等待表单优先→检查事件序列及工具结算→确认 Goal 状态→入队新 continuation | 入口及状态检查位置合理；动态交错未全部证明 |
| `hasUnresolvedWorkGoalToolEffects` 124–140 | 有未结算外部副作用则拒绝自动追续 | 合理防御 |
| `queueWorkGoalProgressFollowUp` 204–292 | 原 goal/steering/revision/progress fingerprint + pending inbox + unknown tool 保护，成功后发布新队列计数 | 设计合理，仍需晚到用户输入/事务取消对抗 |

## E. 工作区技能、固定工具及安装缓存

| 函数 | 保护与判断 |
| --- | --- |
| `LocalSkillStore.refreshInstalledBuiltIns`（约 197–242） | 原 24K 用户规则错误覆盖已经 [#776](https://github.com/sy220284/777/pull/776) RED→GREEN 修复 |
| `LocalPinnedInvocationStore.current/toggle/remove/read/save` 67–123 | 损坏文件 fail-closed，禁止 UI 写入空列表覆写真实文件；可恢复后重试。rename 非完全持久化证明，设备掉电须单测 |
| `UpdateCache.prepare` 35–60 | 新安装尝试可解除旧 handoff，删除失败显式报错 |
| `UpdateCache.markInstallerHandoff/writeHandoff/readHandoff` 63–85、156–205 | 只允许所属 APK、按 marker 原子替换并 fsync；标记无效时不推断未交接 |
| `UpdateCache.cleanupStale` 97–154 | 完成安装/过期才清缓存，损坏标记按有限保护期处理 | 正向防误删合理；真实安装器中断与无法删除缓存的场景未验证 |
| `LocalWorkspace.readRaw/edit/readSkill/skillMetadata`（相关方法） | 路径 sandbox、读前指纹、原子写与有界读取；需真实 symlink 并发 TOCTOU 故障注入 |

## 已证实 / 待复现 / 合理防御结论

**已证实缺陷或明确错误状态表示**：F-01 技能长规则覆盖（#776 已合并）；F-02 Settings 四参数非原子提交（#780 已合并）；P-01 会话删除 IO 失败导致持久对象被 tombstone 隐藏（#782 底层整改）；P-03 已成功外部操作因终态回执写失败被伪报 FAILED（#782 改动）；**本轮新增**用户个性化保存 UI 提前报成功（#783）、会话删除失败 UI 停留空白新 session（#782 审查留言）、后台任务迟到 report 更改终态 output（#782 审查留言）。

**待确定性复现**：模型 mutation commit 与 projection 跨协程乱序（#784）、会话批量删除部分提交及真实故障 IO、未知恢复协议在 owner/冷恢复间的语义、语言服务器 rename 失败及 Android 安装器时序。

**合理防御而非漏洞**：磁盘提交前不发布四参数状态；工作区对损坏技能/固定项 fail-closed；Work 未知工具结果阻止自动追续；Webhook 不可辨认外部副作用只承认 INTERRUPTED；更新 installer handoff 保护现存安装包。

## 仍未达成的完成条件

- 未对全仓 1,455 份 Kotlin 源码中每个函数的所有语句逐行审阅；上表是**明确列举**的已检查路径，不偷换为全部函数覆盖。
- 未在真实 Android 设备上逐项执行磁盘损坏/权限/掉电/OS Installer 中断/异步乱序故障注入。
- PR #782 的 repo 修复可以有全绿 CI，但没有上层 `deleteSessionIds` 真实 UI 失败后的恢复测试；不能凭仓储单测签发全链路 PASS。
- 除既有 CI 外，当前台账只做源码追踪，没有独立运行新的 instrumentation；完成声明必须以当前 git head 的新 CI 和可复现测试结果为证。

**审计方式**：每项继续以状态入口→Owner→副作用→durable fact→失败/取消→恢复→UI 逐层审，只有真正阅读函数主体和对应状态分支才标记人工语义已查；单纯匹配 `catch`、`getOrNull` 不算。
