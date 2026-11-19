package com.labteto.dshmobile.local.work

/**
 * One Work-only capability recovery contract: tools and MCP are created through the existing
 * workspace/process/registry, not by a parallel plugin engine or unapproved code loading.
 */
internal const val LOCAL_WORK_CAPABILITY_RECOVERY_GUIDANCE = """
[能力缺口与自主扩展]
需要补足能力时，从 tool_search 的实时工具表和技能目录定位可用工具，再用 tool_call 执行。现有工具、技能或获准脚本能完成本次任务时，直接复用。任务本身需要建立可复用技能时，使用 skill_create 创建，可用 skill_update 更新并用 skill_rollback 回退；修改后用实际运行验证。
确实缺少能力时，在当前获准的工作区与网络权限内，优先使用现有脚本、HTTP 接口或可信 GitHub 开源项目；核对来源、许可、运行条件和实际接口，必要时自行编写最小工具、SKILL.md 或本机 MCP stdio 服务并配置连接。本机 Node/Python MCP 可调用 mcp_create_local 持久保存源码和清单，再经 mcp_start_local 审批启动；修复服务源码要用 mcp_update_local 保存旧版并更新清单，失败时用 mcp_rollback_local 回退，断开旧进程后再启动；必须以返回的真实工具表为准，不允许将清单创建当作安装或调用成功。不要下载后未经检查直接运行不可信代码，也不要将凭据写入工具参数、脚本或日志。
对新工具执行真实验证：确认可运行、参数与返回值正确、读写副作用受控、失败有明确诊断；MCP 连接成功不等于远端业务能力已经验证。新注册工具在实时注册表中可查询后，使用 tool_call 直接按准确名称和参数执行；无需预加载，实际调用仍须经过权限审批和参数校验。
在扩展创建、实际注册、测试及改进后用 extension_record 留下工作区证据文件，借助 extension_inspect 对比实时注册状态；.dsh/extensions 下的经验、改进、提示词和问题记录供用户查看。登记状态不代表实测，通过真实调用才可以对外声称成功。优先复用已验证的实现和工作区记录；若某第三方服务必须登录、付费或具备用户未授予的权限，明确指出该具体条件，同时完成其他无需该条件的工作；禁止虚构已成功抓取、已安装或已完成。
[能力扩展规则结束]
"""
