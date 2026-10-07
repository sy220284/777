# 官方 Harness 差分验证

这里维护 777 Android 原生 Harness 与锁定官方 Harness 参考实现之间的差分验证。

## 当前基线

来源：

```text
upstream/deepseek-harness.lock.json
```

当前语义参考：

```text
deepseek-ai/deepseek-harness
0.1.7-rc.2
477b4f420553e8a52c2fbccc464d7561b239c443
Session format reference: V4
```

日常 CI 不自动追踪官方 master。

## 结构

- `official-runner.ts`：只在刷新基础 AgentLoop fixture 时运行。
- `official-semantic-runner.ts`：只在刷新高级官方语义快照时运行；直接调用锁定官方包，不经过 777 实现。
- `refresh-official-fixtures.sh`：拉取锁定提交并重新生成两类官方黄金结果。
- `reference-validation/src/test/resources/official/`：基础 AgentLoop 固定黄金结果。
- `reference-validation/src/test/resources/official-semantic/`：高级语义固定黄金结果。
- `:reference-validation:test`：运行 Android 原生实现并与两类黄金结果比较。

## 覆盖

黄金 fixture 用于验证 AgentLoop 的核心模型调度语义，例如：

- 普通文本。
- 空文本 / 多语言。
- 单工具 / 多工具。
- 连续多工具 step。
- 同名工具重复调用。
- 正文与工具并存。
- JSON 参数。
- 空工具输出。

基础黄金 fixture 只证明锁定官方版本的 AgentLoop 核心调度结果；它不代表整个 Android Harness 与官方所有高级子系统语义等价。

高级语义 fixture 与基础回复向量分开维护。当前首个高级 fixture 覆盖 Session Projection Registry：

- `stateVersion=0` 合法；
- 同 key + 同 stateVersion 共享首个注册 unit；
- 同 key + 不同 stateVersion 明确拒绝；
- 同版本重复注册采用引用计数，最后一个 disposer 退出后能力消失；
- 多 projection/多事件读取使用同一 `asOfSequence` 事件切面。

高级 fixture 同样只能由锁定官方源码运行生成，禁止手写“预期值”冒充官方行为。

不适合表达为模型回复向量的状态，由对应模块测试覆盖，包括：

- Cancel。
- Approval / Ask User。
- Plan / Goal / Todo。
- Job / Subagent / Workflow。
- Session 恢复。
- Agent run checkpoint / Checkpoint 事件水位。
- request evidence / Tool & Context Surface 关联。
- tool execution admission identity。
- compaction provenance。
- durable Agent Inbox / continuable subagent / cold resume / terminal settlement。
- Session Projection registry / stateVersion / asOfSequence。
- Plugin Tool View。
- Android device。

## CI

preflight 会：

```sh
corepack enable
bash tools/reference-validation/refresh-official-fixtures.sh
git diff --exit-code -- reference-validation/src/test/resources/official
./gradlew :reference-validation:test
```

因此：

- fixture 不能手写冒充官方输出。
- 锁定 commit 改变后必须重新生成。
- 本机行为偏离官方语义会在差分测试里显式暴露。

## 刷新基线

```sh
bash tools/reference-validation/refresh-official-fixtures.sh
./gradlew :reference-validation:test
```

只有在以下条件同时满足时才能修改锁文件：

1. 明确知道为什么升级官方参考版本。
2. fixture 差异已经审计。
3. Android 原生实现已完成必要适配。
4. 单元测试和 conformance 通过。
5. Android 16 / 17 回归通过。

完整验证体系见 [../../docs/VALIDATION.md](../../docs/VALIDATION.md)。
