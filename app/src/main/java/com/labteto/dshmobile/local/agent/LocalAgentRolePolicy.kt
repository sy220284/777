package com.labteto.dshmobile.local.agent

/**
 * Versioned role presets affect the method of work, never the authority of the parent.
 * All write capability is still constrained by the actual execution grant and ToolPolicy.
 */
internal enum class LocalAgentSpecialty(val wireName: String, val permitsMutation: Boolean, val guidance: String) {
    GENERAL("general", true, ""),
    EXPLORER("explore", false,
        "先追踪实际来源与调用链；区分证据、推断和未知；只返回可核实的发现，避免提出未验证的完成声明。"),
    PLANNER("plan", false,
        "输出依赖明确的计划、风险和验收条件；不擅自开始实现，也不把计划视为已完成工作。"),
    EXECUTOR("execute", true,
        "按当前任务授权执行并保留真实工具结果与产物版本；无法核验时明确标记未完成。"),
    VERIFIER("verify", false,
        "独立检查前序代理的结论，尽力寻找反例；能够执行的检查需提供实际回执，不能执行的标记 PARTIAL，不凭文字断言 PASS。"),
    REVIEWER("review", false,
        "独立审查正确性、并发性、边界与安全风险；给出文件位置及可验证的复现条件。"),
    INTEGRATOR("integrate", true,
        "核对多份结果及任务版本，处理冲突；没有真实证据不得把多个局部结果合并为已验收的整体成果。");

    companion object {
        fun fromWire(raw: String?): LocalAgentSpecialty = when (raw?.trim()?.lowercase().orEmpty()) {
            "", "general" -> GENERAL
            "explore", "explorer", "research" -> EXPLORER
            "plan", "planner" -> PLANNER
            "execute", "executor" -> EXECUTOR
            "verify", "verifier" -> VERIFIER
            "review", "reviewer" -> REVIEWER
            "integrate", "integrator" -> INTEGRATOR
            else -> throw IllegalArgumentException("AGENT_ROLE_UNKNOWN：不支持的代理角色：$raw")
        }
    }
}

internal data class LocalAgentRoleBinding(
    val role: LocalAgentSpecialty,
    val instructions: String,
    val allowMutation: Boolean,
)

internal fun bindLocalAgentRole(
    requested: String?,
    userInstructions: String,
    parentMutationAllowed: Boolean,
): LocalAgentRoleBinding {
    val role = LocalAgentSpecialty.fromWire(requested)
    // An execution/integration role cannot elevate a read-only dispatch.
    require(parentMutationAllowed || role !in setOf(
        LocalAgentSpecialty.EXECUTOR, LocalAgentSpecialty.INTEGRATOR,
    )) {
        "AGENT_ROLE_REQUIRES_WRITABLE_DELEGATION"
    }
    val writable = parentMutationAllowed && role.permitsMutation
    val guidance = if (role == LocalAgentSpecialty.GENERAL) userInstructions else {
        "【专业代理角色 v1：${role.wireName}】\n${role.guidance}\n" +
            "角色仅规定工作方法，不授予额外工具、用户授权或文件权限。\n" +
            userInstructions
    }
    require(guidance.length <= 16_384) { "SUBAGENT_INSTRUCTIONS_TOO_LARGE" }
    return LocalAgentRoleBinding(role, guidance, writable)
}
