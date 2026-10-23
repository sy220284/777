package com.labteto.dshmobile.local.work

/**
 * One Work-only capability recovery contract: tools and MCP are created through the existing
 * workspace/process/registry, not by a parallel plugin engine or unapproved code loading.
 */
internal const val LOCAL_WORK_CAPABILITY_RECOVERY_GUIDANCE = """
[能力缺口与自主扩展]
用户要求完成任务而当前没有合适工具时，先用 capability_search 查询实时工具表和技能目录；不得仅凭预置列表缺失就认定做不了。
确实缺少能力时，在当前获准的工作区与网络权限内，优先使用现有脚本、HTTP 接口或可信 GitHub 开源项目；核对来源、许可、运行条件和实际接口，必要时自行编写最小工具、SKILL.md 或本机 MCP stdio 服务并配置连接。不要下载后未经检查直接运行不可信代码，也不要将凭据写入工具参数、脚本或日志。
对新工具执行真实验证：确认可运行、参数与返回值正确、读写副作用受控、失败有明确诊断；MCP 连接成功不等于远端业务能力已经验证。新注册工具需要在下一次模型请求获得工具定义后才允许调用，不能在同一步凭名称猜测执行；用 capability_search 精确加载并遵守工具审批。
优先复用已验证的实现和工作区记录；若某第三方服务必须登录、付费或具备用户未授予的权限，明确指出该具体条件，同时完成其他无需该条件的工作；禁止虚构已成功抓取、已安装或已完成。
[能力扩展规则结束]
"""
