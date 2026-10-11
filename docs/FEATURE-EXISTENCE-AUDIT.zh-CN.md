# 777 功能独立存在与可用性调查

对上一轮14项无确认入口和76项仅有文件级线索进行重新检查。这是代码级证据报告，运行验收不足时将状态标为待验证。

## 概览

- 原14项不明：7项具有具名生产实现/兼容入口，7项实质为工程回归测试范围。
- 已确认退出主动使用的旧控制器：1项，仅保留数据兼容；部分实现范围尚不完整的描述：1项。
- 文件级弱证据：76项；有可直接定位的主要类/对象声明53项、在其他生产文件发现同名符号引用52项、在测试文件发现同名符号33项。
- 49项弱证据条目与其他树叶共用同一生产文件。共用源码本身不能证明某项功能独立或失效。

## 原14项逐项独立性与使用判断

| 功能描述 | 独立产品入口 | 代码层判断 |
|---|---|---|
| 16类人物资料持续可见 | 未发现单独入口 | integrated_character_profile；code_and_tests_present_execution_unverified |
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

其中所有条目都已记录真实生产文件、文件独占/共享情况、主声明、其他生产代码与测试文件的静态引用。这些静态指标只决定复核优先级；不能据它们自动把功能标为可用或删除。

## 最终边界

当前可以确认部分功能是现行子能力、部分是历史兼容、部分只是回归测试范围。真实独立可用功能总数仍需要交互路径、设备/服务及模型执行结果支持。旧树中的14项和76项在具备充分退役依据前都保留，不自动删除。
