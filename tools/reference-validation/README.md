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
0.2.1-alpha.1
5badb15009ae1756c3afe0ae0cef1faafc290ccc
Session format reference: V4
```

日常 CI 不自动追踪官方 master。

## 结构

- `official-runner.ts`：只在刷新官方 fixture 时运行。
- `refresh-official-fixtures.sh`：拉取锁定提交并重新生成官方黄金结果。
- `reference-validation/src/test/resources/official/`：提交到仓库的基础 AgentLoop 黄金结果。
- `reference-validation/src/test/resources/official-semantic/advanced.json`：锁定官方源码生成的高级语义黄金结果。
- `:reference-validation:test`：运行 Android 原生核心实现并与适用的官方黄金结果比较。

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

基础黄金 fixture 证明锁定官方版本的 AgentLoop 核心调度结果。

此外，刷新脚本会在同一个锁定官方 checkout 中运行 `official-advanced-runner.ts`。官方结果作为 777 的语义下限，不作为能力上限；777 可以提高 stateVersion、增加字段和扩展行为，但不能丢失或弱化官方已经验证的语义。基础 Projection Registry 仍做严格差分；应用层 Agent Team 使用“官方子集必须被原生实现完整覆盖”的兼容下限校验：

- Session Projection `stateVersion`；
- per-projection `asOfSequence` 水位；
- fold 后状态值；
- 同 key + 同 stateVersion 共享首个 projection unit 并引用计数；
- 同 key + 不同 stateVersion fail-closed；
- 最后一个 disposer 释放后移除 projection capability。

高级官方结果提交到 `official-semantic/advanced.json`，但只能由刷新脚本在锁定官方 checkout 中重生成；fixture-provenance 要求工作树与重生成结果完全一致，禁止手写伪 golden。

当前高级官方差分包括 Session Projection Registry，以及官方 `agentTeam` Projection stateVersion=4 / V2 member-task-message whole-value 事件。Agent Teams 的 Android Work 生产 Projection 直接读取同一份官方 advanced golden 作为保底基线：官方已有字段、值、顺序与 mailbox / Task DAG 语义必须继续成立；777 的 stateVersion 只允许等于或高于官方，允许新增字段和更强能力，不复制一套“官方逻辑”的 Kotlin 假实现。

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
- Session Projection registry / stateVersion / asOfSequence（官方高级差分）。
- Agent Teams roster / durable mailbox / Task DAG / CAS / fail-loud bounds：官方可执行 Projection 部分进入 advanced golden；Android Activation / Job / Cold Resume 等平台特化行为继续由 Work-owned 领域回归覆盖，不为测试把 App 领域下沉到 harness-core。
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
- 本机行为低于或破坏官方已有语义会在验证中显式暴露；高于官方的版本、字段和增强能力允许保留。

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
