# 777 功能独立存在与可用性调查

对上一轮14项无确认入口和76项仅有文件级线索进行重新检查。这是代码级证据报告，运行验收不足时将状态标为待验证。

## 概览

- 原14项不明：7项具有具名生产实现/兼容入口，7项实质为工程回归测试范围。
- 已确认退出主动使用的旧控制器：1项，仅保留数据兼容；部分实现范围尚不完整的描述：1项。
- 文件级弱证据：76项；有与文件名对应的主要类/对象声明53项，能够发现其他具名声明的76项；在其他生产文件发现同名符号引用76项、在测试文件发现同名符号67项。
- 49项弱证据条目与其他树叶共用同一生产文件。0项未找到外部源码或测试符号引用，应优先核实是否纯内部实现、入口断链或待退役代码。共用源码本身不能证明某项功能独立或失效。

## 原14项逐项独立性与使用判断

| 功能描述 | 独立产品入口 | 代码层判断 |
|---|---|---|
| 16类人物资料按完整事实取用 | 未发现单独入口 | integrated_character_profile；code_and_tests_present_execution_unverified |
| 模型温度独立采样 | 未发现单独入口 | active_sampling_subfeature；code_and_tests_present_execution_unverified |
| 历史调参兼容读取，退出反应控制 | 未发现单独入口 | retired_controller_compatibility；legacy_fields_decodable_active_controls_retired |
| 共同经历与采样设置分离 | 未发现单独入口 | cross_component_invariant；code_and_tests_present_execution_unverified |
| 用户与作者表达偏好及重复诊断 | 未发现单独入口 | partial_expression_guidance；partial_scope_code_and_tests_present |
| 工具输出格式和结构化验证 | 未发现单独入口 | shared_tool_pipeline；code_and_tests_present_execution_unverified |
| Node／Python／Git 工具运行环境 | 未发现单独入口 | composite_bundled_runtimes；code_and_instrumentation_sources_present_device_unverified |
| 团队任务与 UI 回归 | 未发现单独入口 | test_suite_not_product_feature；test_cases_declared_not_verified_executed |
| 技能中文名称和编辑回归 | 未发现单独入口 | test_suite_not_product_feature；test_cases_declared_not_verified_executed |
| 官方语义样本差分验证 | 未发现单独入口 | test_suite_not_product_feature；test_cases_declared_not_verified_executed |
| 会话登记升级、备份损坏和并发写入回归 | 未发现单独入口 | test_suite_not_product_feature；test_cases_declared_not_verified_executed |
| 单聊群聊工作共存及历史读取隔离回归 | 未发现单独入口 | test_suite_not_product_feature；test_cases_declared_not_verified_executed |
| 人物日记纠正与能力状态界面回归 | 未发现单独入口 | test_suite_not_product_feature；test_cases_declared_not_verified_executed |
| 交付要求、成果版本和记忆来源证据回归 | 未发现单独入口 | test_suite_not_product_feature；test_cases_declared_not_verified_executed |

明确的源码引用和测试方法，以及各项中文结论见 JSON 明细。以上“存在测试”只证明可执行测试代码的存在，未核实本次设备/模型运行通过。

## 仍需调查的76项

所有76项均逐项记录真实生产文件、具名类或函数、跨文件引用及测试关联。多功能共用源码时，独立入口仍须看真实调用和用户行为。

| 功能描述 | 源码入口线索 | 其他生产引用文件 | 测试引用文件 | 共用源文件 |
|---|---|---:|---:|---|
| 会话列表和历史读取按单会话故障隔离 | summaries | 27 | 8 | 是 |
| V4事实字段规范与旧消费者一致性校验 | canonicalV4 | 4 | 2 | 尚未发现 |
| 明确点名与一般提及分离，允许成员自然沉默 | LocalGroupChatState | 15 | 10 | 是 |
| 关系推进、阶段与变化 | CharacterEvolutionState | 2 | 3 | 是 |
| 日记纠正、停用与当前状态同步 | ChatDiaryStore | 6 | 9 | 是 |
| 日记来源会话及消息标识与跳转 | CharacterDiaryScreen | 1 | 1 | 尚未发现 |
| 同源长期事实变动触发派生日记失效 | recoverPendingDiaryInvalidations | 3 | 0 | 是 |
| 未完成日记清理的重启补偿与召回隔离 | captureAutoMemoryDirective | 5 | 0 | 尚未发现 |
| 角色独立记忆与群聊发言上下文约束 | editAndResendUserMessage | 8 | 3 | 尚未发现 |
| 自主选择公开历史对话和消息来源 | workHandoffSummaryWithSelectedMessages | 1 | 1 | 是 |
| 加载更早分页、筛选并保留已选消息 | workHandoffSummaryWithSelectedMessages | 1 | 1 | 是 |
| 交接摘要携带来源会话、消息编号与节选 | workHandoffSummaryWithSelectedMessages | 1 | 1 | 是 |
| 选中附件使用受限路径和 SHA-256 引用 | workHandoffSummaryWithSelectedMessages | 1 | 1 | 是 |
| 模型、MCP、插件和设备当前能力提示 | project | 27 | 31 | 是 |
| 团队未结消息和失败成员的完成声明复核 | inspect | 9 | 2 | 是 |
| 交付要求与真实成果事件来源绑定 | projectRequirementEvidenceLinks | 1 | 2 | 尚未发现 |
| 交付成果当前版本及证据变化提示 | localWorkFileSha256 | 2 | 3 | 是 |
| 工作目录与文件浏览 | resolve | 42 | 34 | 是 |
| 文件预览和分享 | resolve | 42 | 34 | 是 |
| 文件修改与补丁应用 | resolve | 42 | 34 | 是 |
| 成果来源调用与事件序号可追溯 | projectLocalWorkArtifacts | 3 | 2 | 尚未发现 |
| 成果首次创建和当前内容哈希对照 | localWorkFileSha256 | 2 | 3 | 是 |
| 从原任务内容优化指令 | complete | 63 | 33 | 是 |
| 工具和插件清单 | refresh | 24 | 6 | 是 |
| 分类浏览、配置和状态展示 | refresh | 24 | 6 | 是 |
| 工具安装和管理入口 | refresh | 24 | 6 | 是 |
| 已安装技能读取和调用 | install | 19 | 13 | 是 |
| 自定义技能创建与编辑 | install | 19 | 13 | 是 |
| 上传 SKILL.md 与 ZIP 技能包 | install | 19 | 13 | 是 |
| Work 对话创建并写入技能目录 | install | 19 | 13 | 是 |
| 技能中文显示名称 | install | 19 | 13 | 是 |
| 新技能创建时填写中文名 | install | 19 | 13 | 是 |
| 输入框与历史消息显示中文名 | LocalMessageRow | 2 | 1 | 尚未发现 |
| JSON 结构查询 | resolve | 42 | 34 | 是 |
| 长输出分页读取 | resolve | 42 | 34 | 是 |
| GitHub 工具调用 | githubConfigured | 8 | 1 | 是 |
| 高权限调用审批 | metadata | 52 | 13 | 是 |
| 网页搜索 | request | 132 | 48 | 是 |
| 网页抓取与页面正文获取 | request | 132 | 48 | 是 |
| HTTP 请求 | request | 132 | 48 | 是 |
| 图片理解和视觉模型调用 | install | 19 | 13 | 是 |
| 截图和媒体附件处理 | install | 19 | 13 | 是 |
| 图像分析缓存 | LocalVisionAnalysisCache | 1 | 1 | 尚未发现 |
| 副作用识别 | metadata | 52 | 13 | 是 |
| 自动、手动和默认审批 | metadata | 52 | 13 | 是 |
| ChatGPT 账户授权登录 | summary | 52 | 18 | 尚未发现 |
| ChatGPT 套餐模型目录与账号状态 | parseChatGptPlanModels | 1 | 1 | 尚未发现 |
| 推理档位及温度上限对多入口的约束 | current | 202 | 39 | 是 |
| 长期事实来源关联与显式纠正停用 | LocalMemorySettingsCard | 1 | 0 | 尚未发现 |
| 长期事实修改后的来源失效待办 | remember | 66 | 8 | 是 |
| 自定义聊天背景 | WallpaperSurfaceLevel | 37 | 0 | 尚未发现 |
| 任务相关配置状态与执行授权分开提示 | project | 27 | 31 | 是 |
| 无障碍与通知监听实时服务状态提示 | refresh | 24 | 6 | 尚未发现 |
| 屏幕共享和录制逐次系统授权提示 | project | 27 | 31 | 是 |
| 消息／工具／上下文表面记录 | snapshot | 143 | 50 | 尚未发现 |
| 请求证据重建与摘要校验 | surface | 60 | 7 | 尚未发现 |
| 状态投影注册、版本和事件水位 | fileFor | 4 | 0 | 尚未发现 |
| 工具活动只读投影 | snapshot | 143 | 50 | 是 |
| 持久会话登记与摘要投影索引分工 | summaries | 27 | 8 | 是 |
| 批次投影和中断后重建 | request | 132 | 48 | 尚未发现 |
| 单个会话备份损坏不阻断其他会话 | summaries | 27 | 8 | 是 |
| Anthropic 消息协议 | complete | 63 | 33 | 尚未发现 |
| DeepSeek 兼容协议 | complete | 63 | 33 | 尚未发现 |
| ChatGPT 套餐专用协议分流 | refresh | 24 | 6 | 尚未发现 |
| ChatGPT OAuth 授权回调 | ChatGptOAuthCallbackServer | 1 | 3 | 尚未发现 |
| 运行中配置变化隔离 | LocalModelRunContext | 5 | 1 | 尚未发现 |
| 连接健康诊断和重试约束 | LocalModelConnectionTester | 2 | 0 | 尚未发现 |
| 模型路由的推理及温度上限统一生效 | complete | 63 | 33 | 是 |
| 请求封装和入场校验 | snapshot | 143 | 50 | 尚未发现 |
| 图片等多模态输入 | prepareLocalMultimodalMessages | 4 | 2 | 尚未发现 |
| 记忆库只读分类投影 | memoryVaultFolders | 1 | 1 | 尚未发现 |
| 来源失效待办持久化及清理确认 | remember | 66 | 8 | 是 |
| 按来源精准失效派生日记并幂等恢复 | recoverPendingDiaryInvalidations | 3 | 0 | 是 |
| 在线更新检测 | UpdateChecker | 1 | 2 | 尚未发现 |
| 增量补丁下载与校验 | downloadVerifyAndLaunch | 1 | 0 | 是 |
| 安全安装和失败反馈 | downloadVerifyAndLaunch | 1 | 0 | 是 |

其中“尚未发现共用源文件”不意味着拥有独立产品入口，其他源码/测试文件含同名符号也不证明调用链真正可达。

## 最终边界

当前可以确认部分功能是现行子能力、部分是历史兼容、部分只是回归测试范围。真实独立可用功能总数仍需要交互路径、设备/服务及模型执行结果支持。旧树中的14项和76项在具备充分退役依据前都保留，不自动删除。
