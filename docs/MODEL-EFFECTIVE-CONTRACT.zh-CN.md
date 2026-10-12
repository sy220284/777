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

## A～D 阶段完成范围（提交供审计）

### A · 模型能力事实与策略合同
- `LocalEffectiveModelContracts` 为唯一只读组合器，按路由和协议拼出身份、模态、可执行能力证据、思考、采样、缓存、预算、上下文和已接线的媒体服务目录。提供商协议和套餐限制仍由原权威模块决定。
- 模型网关、运行时快照和 Agent 模型清单从统一合同读取请求能力，原有 API Key、ChatGPT Plan、MiniMax 官方/代理等特殊分支保留。

### B · Chat、Work、子代理的执行链
- `LocalRunModelSurface` 在运行开始冻结合同；主请求和子代理共享已有 `LocalAgentModelRequestRuntime` 及路由准入入口。
- 准入对象可携带不可变的上下文上限快照；`LocalModelRequestCoordinator` 与 Work `executeWithModelAdmission` 一致使用该值。缺快照的旧调用继续按原模型查询。
- Work 片预算、持久历史压缩、聊天思考/温度控制、人物温度设置及聊天输入框使用同一个能力策略入口。主代理预算按主模型配置，子代理实际模型保留自己的配置、路由及资源调度。
- 不取消任何硬性安全或重试机制，不更改 MiniMax 的 160/256 次工作预算和其他路由的 64 次默认值。

### C · 可见产品能力与独立媒体服务
- 模型设置列表从合同展示客户端输入模态、已接线的创作种类、该模型上下文状态和 Work 执行片次数。
- 媒体服务通过现有 `LocalMediaCatalog.offers` 提供，**不**从聊天 IMAGE/VIDEO 标签推断视频生成；OAuth 套餐和自定义代理不继承官方创作 API 权限。
- “已接线”代表客户端有调用路径和来源符合白名单，**不**意味着账户已购买套餐、剩余额度或真实生成服务一定可成功；服务端权限和价格仍由各业务模块在实际调用时判断。
- 新文案使用 values/strings.xml，兼容仓库 UI 国际化与静态门禁。

### D · 请求实报校准、可解释性与清理
- `LocalRequestPressureStore` 在成功报告使用量时按物理路由指纹记录估算/实报比例；最多 64 个路由，使用 EMA（旧值 75%、新值 25%），单次比例限制为 0.70–1.15，至少 4 次有效样本后用于 Work 软压缩压力。
- 当原始估算达到该路由操作输入上限的 85% 时，不再使用折扣比例；Chat/Work 的硬性上下文准入始终采用未经折扣的估算，账本始终采用服务商实报 Token。
- `request/header` 新增合同版本、原始容量、工作预算、图像/工具状态及软压缩系数；`request/completed` 新增未折扣估算、校准样本数和估算系数。不输出 API Key、OAuth Token 或媒体任务密钥。
- 没有创建平行模型管理器、账本或媒体框架。策略 owner 仍保留原实现，新合同只做组合；被替代的 UI/Work 直接厂商策略查询已收敛，必要的模型档案与硬限制权威函数不应删除。

## 审计与验收矩阵
| 场景 | 应验收的事实 |
| --- | --- |
| 全部预置型号 | 旧 runtime、缓存、温度、思考、预算、窗口值与新合同一一一致 |
| MiniMax 官方/代理 | 官方 256/160 工作片与大上下文不能泄露到未知代理 |
| ChatGPT Plan/API | 同名型号分别采用真实协议和账户指纹，媒体资格不跨鉴权方式 |
| 跨模型子代理 | 每个请求使用该路由自己的冻结输入上限，不继承主代理的厂商特殊参数 |
| 多模态 | 聊天输入和独立图片/视频/配音/转写服务严格区分 |
| Token 误差 | 4 样本前不校准；校准只用于软压缩；接近硬边界自动停用折扣 |
| 用户设置 | 与有效合同显示一致，未知窗口保持未知，不宣称免费或无限额度 |
| 故障诊断 | 事件显示运行合同版本、原始估算、实报和生效软校准，且不含明文密钥 |

## 验证边界
GitHub CI 的静态、架构、JVM 单测与 ARM64 构建是本轮必需结果；设备矩阵只可按照 workflow 实际执行/跳过状态陈述。云端付费模型和媒体账户的实时额度、真实输出质量、复杂断线恢复仍需要有实际授权的端到端环境单独验证，不能因代码测试通过就虚称线上完美。

此文档对应 PR #655 的代码供用户审计。**审计前保持 PR 开放，不直接合并 main。**
