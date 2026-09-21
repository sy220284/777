# 官方 Harness 差分验证

这里维护 777 Android 原生 Harness 与官方参考实现之间的差分测试入口。

- 官方基线由 `upstream/deepseek-harness.lock.json` 人工锁定。
- `official-runner.ts` 只在刷新黄金结果时运行于锁定的官方仓库工作区。
- `refresh-official-fixtures.sh` 会临时拉取该锁定提交并更新
  `reference-validation/src/test/resources/official/`。
- 日常 CI 不自动跟踪官方 master，只比较已提交的固定黄金结果。
- Kotlin 原生实现的输出由 `:reference-validation:test` 生成并比较。

当前首批覆盖：

1. 无工具普通对话；
2. 单工具调用后继续下一步。

后续按规划扩展并行工具、失败、超时、重试、取消、审批、Ask User、Plan、Goal、Todo、
Job、Subagent、Workflow、fork/resume、compaction 与旧 Session 迁移。

刷新命令：

```sh
bash tools/reference-validation/refresh-official-fixtures.sh
./gradlew :reference-validation:test
```

刷新官方基线属于人工维护动作。只有差分结果解释清楚且原生实现完成相应移植后，才允许修改锁文件。
