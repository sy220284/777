# 777 模型能力统一合同：A 阶段落地记录

## 目标与所有权
只做架构 3.0 Shared Model Capability 组合入口与等价迁移，不新增第二套模型配置、预算、请求客户端、账户或 Token 账本，也不改变任何服务商的既有限额数值。

- LocalEffectiveModelContracts：只读的即时组合入口，聚合型号、能力、协议、思考、温度、缓存、上下文和 Work 预算。不访问真实账号、不复制账本、不保存 Feature 状态。
- LocalModelGateway.resolveRoute：通过已有账户和协议解析，引用合同 runtime，仍由原适配器发送请求。
- LocalRunModelSurface：使用合同冻结运行中的路由协议、身份与能力；不会重置原始会话历史。
- LocalReasoningControls、LocalConversationTemperatureUi：读取同一合同，不再自行在 UI 解析厂商。
- LocalWorkAgentTurnExecutor：执行片读取合同预算，子代理与任务共享调度器不改变。
- LocalAgentModelCatalog：同一个协议和运行能力来源。

## 信息与证据
| 合同字段 | 意义与权威来源 |
| --- | --- |
| documentedModalities | 预置型号声明，LocalModelPresets |
| clientModalities | 当前 777 已编码输入模态，LocalModelPresets |
| imageInput / toolCalling | 支持、未知、不支持及证据；未知不能宣称已支持 |
| runtime | 请求层工具、输出、前缀缓存等原策略 |
| reasoning / temperature | 专属思考与采样策略，仍由旧 owner 决定 |
| executionBudget / cache | Work 执行片预算和压缩缓存策略 |
| operationalInputLimitTokens | 产品操作输入上限，不是提供商物理最大上下文 |
| documentedContextWindowTokens | 预设或用户明确覆盖；null 表示未确认 |
| routeFingerprint | 身份快照的指纹，不保存 bearer token |

账户额度、套餐可用性、密钥有效性、设备压力、媒体生成与费用并不由模型档案直接判断。未知能力与不可用能力区分；用户端权限应以真实服务商和客户端实现结果为准。

## 与最近开发的关系
- 模型思考与可见输出仍由现有规范化器和协议 adapter 处理，不重复实现标签解析。
- 多模态创作由现有统一媒体运行时与 Media Catalog 管理，不把聊天 IMAGE/VIDEO 标签当作创作能力。
- UI 视觉重构独立验收，本阶段只迁移模型思考与温度查询入口。

## 交付阶段
阶段 A（本 PR）：统一只读能力与运行策略快照，接入模型网关、运行快照、Chat 控件和 Work 预算；以全部预置型号、官方路由/代理、套餐/API、自定义型号对照旧规则做等价回归。
阶段 B：Chat、Work、子代理在整个请求生命周期冻结合同，迁移缓存、上下文、工具与恢复路径，确保跨模型任务按实际模型使用策略。拆迁前先有回归证据。
阶段 C：统一模型设置、服务商额度和创作入口对能力合同的消费；只展示真正已编码且有来源的操作。
阶段 D：实报 Token 校准、可解释的策略诊断，最后删除已无消费者的旧判断，不提前清理。

验收以 PR 真实 CI 的静态、架构、JVM、ARM64 与有条件的设备回归为准，不能以该合同推断付费云端接口实测成功。