# 官方 Harness 差分验证

这里维护 777 Android 原生 Harness 与官方参考实现之间的差分测试入口。

- 官方基线由 `upstream/deepseek-harness.lock.json` 人工锁定。
- `official-runner.ts` 只在刷新黄金结果时运行于锁定的官方仓库工作区。
- `refresh-official-fixtures.sh` 会临时拉取该锁定提交并更新
  `reference-validation/src/test/resources/official/`。
- 日常 CI 不自动跟踪官方 master，只比较已提交的固定黄金结果。
- Kotlin 原生实现的输出由 `:reference-validation:test` 生成并比较。

当前固定黄金集为 22 个官方参考案例，覆盖普通文本、多语言与空文本、单/多工具同一步、
连续多工具步骤、同名工具重复调用、正文与工具并存、嵌套/混合 JSON 参数、空工具输出等
AgentLoop 核心调度语义。

取消、审批、Ask User、Plan、Goal、Todo、Job、Subagent、Workflow、Session 迁移与自愈
由对应模块的确定性单元/集成测试覆盖；这些状态不塞进无法表达它们的模型回复向量。

CI 会从锁定的官方提交重新生成全部黄金结果并检查工作树无差异，防止手写 fixture 冒充官方基线。

刷新命令：

```sh
bash tools/reference-validation/refresh-official-fixtures.sh
./gradlew :reference-validation:test
```

刷新官方基线属于人工维护动作。只有差分结果解释清楚且原生实现完成相应移植后，才允许修改锁文件。
